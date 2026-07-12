(ns casualty.governor
  "Non-Life Insurance Governor -- the independent compliance layer that
  earns the Underwriter-LLM the right to commit. The LLM has no notion of
  jurisdiction disclosure law, AML/sanctions exposure, whether a claim
  would pay out more than a policy's own coverage limit, or when an act
  stops being a draft and becomes a real-world policy binding or claim
  payout, so this MUST be a separate system able to *reject* a proposal
  and fall back to HOLD -- the non-life-insurance analog of
  `cloud-itonami-isic-6511`'s UnderwritingGovernor.

  Seven checks, in priority order. The first six are HARD violations: a
  human approver CANNOT override them (you don't get to approve your way
  past an AML/sanctions hit, a fabricated underwriting requirement, a
  claim against an unbound policy, or a settlement that would exceed the
  policy's own coverage limit). The last is SOFT: it asks a human to
  look (low confidence / actuation), and the human may approve -- but
  see `casualty.phase`: for `:stake :actuation/bind`/`:actuation/settle-
  claim` (a real policy binding or a real claim payout) NO phase ever
  allows auto-commit either. Two independent layers agree that actuation
  is always a human call.

    1. Spec-basis           -- did the jurisdiction proposal cite an
                                OFFICIAL source (`casualty.facts`), or
                                invent one?
    2. Sanctions hold        -- does THIS proposal itself report a
                                sanctions/PEP hit (a `:kyc/screen` that
                                just found one), or does the
                                policyholder (`:policy/bind`) / claimant
                                (`:claim/settle`) already carry one on
                                file?
    3. Document complete     -- for `:policy/bind`, are the jurisdiction's
                                required underwriting docs actually
                                satisfied?
    4. Policy not bound      -- for `:claim/file`, has the referenced
                                policy actually been bound? A claim
                                cannot be filed against coverage that was
                                never issued.
    5. Claim missing         -- for `:claim/settle`, does the referenced
                                claim actually exist on file?
    6. Claim exceeds coverage -- for `:claim/settle`, does the settlement
                                amount exceed the policy's own recorded
                                coverage-amount? Independently checked
                                against the policy's own limit, never
                                trusting the claimed amount as-is.
    7. Confidence floor / actuation gate -- LLM confidence below
                                threshold, OR the op is `:policy/bind`/
                                `:claim/settle` (REAL legal/financial
                                acts) -> escalate.

  One more guard, double-settlement prevention, is enforced but NOT
  listed as a numbered HARD check above because it needs no upstream/
  policy comparison at all -- `claim-already-settled-violations` refuses
  to settle the SAME claim twice, off this actor's own claim history."
  (:require [casualty.facts :as facts]
            [casualty.kernels.gate :as gate]
            [casualty.store :as store]))

(def confidence-floor
  "Documented threshold. The DECIDING copy is
  `casualty.kernels.gate/confidence-floor-x100` (integer x100 in the
  safety kernel); this def is kept for callers/docs and pinned equal by
  `casualty.kernels.gate-test`."
  0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Binding real property/casualty coverage and settling a real claim
  payout are the two real-world actuation events this actor performs."
  #{:actuation/bind :actuation/settle-claim})

(defn- confidence->x100
  "Host bridge (façade-side, not kernel vocabulary): scale a 0.0..1.0
  advisor confidence to the kernel's integer x100 wire code."
  [c]
  (Math/round (* 100.0 (double c))))

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:policy/bind`) proposal with no
  spec-basis citation is a HARD violation -- never invent a
  jurisdiction's underwriting law."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :policy/bind} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- sanctions-violations
  "A sanctions/PEP hit -- reported by THIS proposal (e.g. a `:kyc/screen`
  that itself just found a hit), or already on file in the store for the
  policyholder (`:policy/bind`) / claimant (`:claim/settle`) -- is a
  HARD, un-overridable hold. Real coverage cannot be bound for, and a
  real claim payout cannot be made to, a sanctioned party."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :hit (get-in proposal [:value :verdict]))
        party-id (cond
                   (= op :policy/bind) (:policyholder (store/policy st subject))
                   (= op :claim/settle) (:claimant (store/claim st subject)))
        hit-on-file? (and party-id (= :hit (:verdict (store/kyc-of st party-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :sanctions-hit
        :detail "制裁/PEPリスト一致のある関係者を含む提案は進められない"}])))

(defn- document-violations
  "For `:policy/bind`, the jurisdiction's required underwriting docs must
  actually be satisfied -- do not trust the advisor's self-reported
  confidence alone."
  [{:keys [op subject]} st]
  (when (= op :policy/bind)
    (let [p (store/policy st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-docs-satisfied?
                      (:jurisdiction p) (:checklist assessment)))
        [{:rule :incomplete-documents
          :detail "法域の必要書類が充足していない状態での成立提案"}]))))

(defn- policy-not-bound-violations
  "For `:claim/file`, the referenced policy must actually have been
  bound (`:status :bound`) -- a claim cannot be filed against coverage
  that was never issued."
  [{:keys [op policy-id]} st]
  (when (= op :claim/file)
    (when-not (= :bound (:status (store/policy st policy-id)))
      [{:rule :policy-not-bound
        :detail (str policy-id " は成立(bound)していないため、この保険契約への保険金請求は受理できない")}])))

(defn- claim-missing-violations
  "For `:claim/settle`, the referenced claim must actually exist on
  file -- refuses to settle a fabricated/nonexistent claim id."
  [{:keys [op subject]} st]
  (when (= op :claim/settle)
    (when-not (store/claim st subject)
      [{:rule :claim-missing
        :detail (str subject " という保険金請求は登録されていない")}])))

(defn- claim-exceeds-coverage-violations
  "For `:claim/settle`, the settlement amount must not exceed the
  policy's OWN recorded coverage-amount -- independently checked against
  the policy's own limit, never trusting the claimed amount as-is."
  [{:keys [op subject]} st]
  (when (= op :claim/settle)
    (when-let [c (store/claim st subject)]
      (let [p (store/policy st (:policy-id c))]
        (when (> (double (:claimed-amount c)) (double (:coverage-amount p)))
          [{:rule :claim-exceeds-coverage
            :detail (str subject " の請求額が保険金額の上限を超過している")}])))))

(defn- claim-already-settled-violations
  "For `:claim/settle`, refuses to settle the SAME claim twice, off this
  actor's own claim history -- needs no upstream/policy comparison at
  all."
  [{:keys [op subject]} st]
  (when (= op :claim/settle)
    (when (store/claim-already-settled? st subject)
      [{:rule :claim-already-settled
        :detail (str subject " は既に保険金支払い済み")}])))

(defn check
  "Censors an Underwriter-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}.

   - :hard?       -- at least one HARD violation. Forces HOLD; a human
                    cannot override.
   - :escalate?   -- soft: low confidence OR actuation. A human decides.
   - :ok?         -- clean AND not escalating: safe to auto-commit."
  [request _context proposal st]
  (let [spec-v    (spec-basis-violations request proposal)
        sanc-v    (sanctions-violations request proposal st)
        docs-v    (document-violations request st)
        unbound-v (policy-not-bound-violations request st)
        missing-v (claim-missing-violations request st)
        exceeds-v (claim-exceeds-coverage-violations request st)
        settled-v (claim-already-settled-violations request st)
        hard (into [] (concat spec-v sanc-v docs-v unbound-v missing-v
                              exceeds-v settled-v))
        conf (:confidence proposal 0.0)
        stakes? (boolean (high-stakes (:stake proposal)))
        ;; The decision itself is delegated to the safety kernel
        ;; (casualty.kernels.gate, integer-coded fail-closed core);
        ;; this façade only gathers evidence (violation lists with
        ;; human-readable details) and maps codes back to keywords.
        ;; Kernel is stricter than the old inline logic on ONE case by
        ;; design: an out-of-range confidence (< 0 or > 1.0) now
        ;; escalates instead of counting as high confidence.
        code (gate/verdict-code (if (seq spec-v) 1 0)
                                (if (seq sanc-v) 1 0)
                                (if (seq docs-v) 1 0)
                                (if (seq unbound-v) 1 0)
                                (if (seq missing-v) 1 0)
                                (if (seq exceeds-v) 1 0)
                                (if (seq settled-v) 1 0)
                                (confidence->x100 conf)
                                (if stakes? 1 0))]
    {:ok?          (= 0 code)
     :violations   hard
     :confidence   conf
     :hard?        (= 2 code)
     :escalate?    (= 1 code)
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
