(ns casualty.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout, iteration 11): this repo previously had NO demo page and
  no generator at all. This namespace drives the REAL actor stack
  (`casualty.operation` -> `casualty.governor` -> `casualty.store`)
  through a scenario adapted from this repo's own `casualty.sim` demo
  driver (`clojure -M:run`, confirmed by actually running it before this
  file was written -- unlike `cloud-itonami-isic-851`'s `schoolops.sim`,
  this repo's own sim driver uses ids that DO match `casualty.store/
  demo-data`'s seeded policies/parties exactly, and every disposition it
  produces (commit / escalate+approve / HARD hold, and the exact `:rule`
  on each hold) matches `casualty.governor`'s own documented checks
  precisely, so it was safe to trim rather than author from scratch),
  trimmed to a representative subset (two clean phase-3 auto-commits, the
  full policy-binding/claim-settlement actuation lifecycle for one policy
  -- both of which ALWAYS escalate, never auto, at any phase -- and three
  distinct HARD-hold reasons that never reach a human) and rendered
  deterministically -- no invented numbers, no timestamps in the page
  content, byte-identical across reruns against the same seed (verified
  by diffing two consecutive runs before shipping).

  This renderer only drives the core `casualty.operation` ->
  `casualty.governor` -> `casualty.store` path -- it deliberately does
  not touch `casualty.corporate-intel`, `casualty.kernels.gate` (used
  transitively via governor/phase already), `casualty.underwriterllm`'s
  LLM-backed advisor, or the `wasm/` claim-coverage module; those are out
  of scope for the flagship operator-console generator.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [casualty.store :as store]
            [casualty.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness (unchanged across every repo
;; in this cluster -- do not rewrite, only copy) -----------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :underwriter :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach, using ONLY real ids from `casualty.store/
  demo-data`:

  pol-1 (JPN, policyholder party-1, motor, coverage-amount 3,000,000 JPY,
  clean) walks the full clean lifecycle: a `:policy/intake`
  directory-normalization patch is a phase-3, no-capital-risk
  auto-commit (governor clean, `:policy/intake` is in phase 3's `:auto`
  set); `:jurisdiction/assess` (JPN has a real spec-basis in
  `casualty.facts`) and `:kyc/screen` on party-1 (clean) each ALWAYS
  escalate (neither op is ever auto-eligible, at any phase) and are
  approved by a human underwriter; `:policy/bind` -- one of the two
  REAL-WORLD actuation events this actor performs (real coverage issued,
  premium obligations begin) -- ALSO ALWAYS escalates (the governor's own
  `high-stakes` gate AND the phase table agree, independently, that
  actuation is never auto, at any phase) and is approved, producing one
  draft policy-binding record. claim-1, filed against the now-bound
  pol-1 for 500,000 JPY (well within the 3,000,000 coverage limit), is
  itself a phase-3 auto-commit (filing moves no capital); `:claim/settle`
  on claim-1 -- the other REAL-WORLD actuation event (real money changes
  hands) -- ALWAYS escalates and is approved, producing one draft
  claim-settlement record.

  Then three DISTINCT HARD-hold reasons, none of which ever reach a
  human (a human approver cannot override a HARD violation):
    - party-3 (seeded with `:sanctions-hit? true`): `:kyc/screen`
      HARD-holds on `:sanctions-hit` -- a real policy cannot be bound
      for, nor a real claim paid to, a sanctioned party.
    - pol-2 (jurisdiction ATL, not in `casualty.facts/catalog`):
      `:jurisdiction/assess` HARD-holds on `:no-spec-basis` -- the
      advisor may not invent a jurisdiction's insurance-underwriting
      requirements.
    - claim-3, filed against the same bound pol-1 for 5,000,000 JPY
      (filing itself auto-commits -- filing moves no capital, so this
      is evidence-gathering only): `:claim/settle` on claim-3 HARD-holds
      on `:claim-exceeds-coverage` -- the governor independently
      recomputes the settlement amount against pol-1's own recorded
      3,000,000 coverage-amount and refuses to trust the claimed figure.

  Returns the resulting store -- every field `render` below reads is
  real governor/store output, not a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]

    ;; pol-1: clean directory-normalization patch -- phase-3 auto-commit,
    ;; no capital risk yet.
    (exec! actor "p1-intake" {:op :policy/intake :subject "pol-1"
                               :patch {:id "pol-1" :status :ready}})

    ;; pol-1: jurisdiction underwriting-requirement assessment (JPN has a
    ;; real spec-basis) -- ALWAYS escalates, approved by a human.
    (exec! actor "p1-assess" {:op :jurisdiction/assess :subject "pol-1"})
    (approve! actor "p1-assess")

    ;; party-1: KYC/sanctions screening, clean -- ALWAYS escalates,
    ;; approved by a human.
    (exec! actor "p1-kyc" {:op :kyc/screen :subject "party-1"})
    (approve! actor "p1-kyc")

    ;; pol-1: REAL policy binding (actuation/bind, real coverage issued) --
    ;; ALWAYS escalates regardless of phase or confidence, approved by a
    ;; human underwriter.
    (exec! actor "p1-bind" {:op :policy/bind :subject "pol-1"})
    (approve! actor "p1-bind")

    ;; claim-1 against the now-bound pol-1, 500,000 JPY of 3,000,000
    ;; coverage -- filing itself is a phase-3 auto-commit, no capital
    ;; risk yet.
    (exec! actor "c1-file" {:op :claim/file :subject "claim-1" :policy-id "pol-1"
                             :claimant "party-1" :claimed-amount 500000
                             :loss-date "2026-06-01" :loss-description "追突事故による損傷"})

    ;; claim-1: REAL claim settlement (actuation/settle-claim, real money
    ;; changes hands) -- ALWAYS escalates, approved by a human claims
    ;; adjuster.
    (exec! actor "c1-settle" {:op :claim/settle :subject "claim-1"})
    (approve! actor "c1-settle")

    ;; party-3: seeded with a sanctions/PEP hit -> HARD hold on
    ;; :sanctions-hit, never reaches a human.
    (exec! actor "p3-kyc" {:op :kyc/screen :subject "party-3"})

    ;; pol-2 (ATL): no official spec-basis in casualty.facts -> HARD hold
    ;; on :no-spec-basis, never reaches a human.
    (exec! actor "p2-assess" {:op :jurisdiction/assess :subject "pol-2" :no-spec? true})

    ;; claim-3 against the same bound pol-1, 5,000,000 JPY -- filing
    ;; itself still auto-commits (no capital risk in filing).
    (exec! actor "c3-file" {:op :claim/file :subject "claim-3" :policy-id "pol-1"
                             :claimant "party-1" :claimed-amount 5000000
                             :loss-date "2026-06-15" :loss-description "全損事故"})

    ;; claim-3: claimed amount (5,000,000) exceeds pol-1's own recorded
    ;; coverage-amount (3,000,000) -> HARD hold on
    ;; :claim-exceeds-coverage, never reaches a human.
    (exec! actor "c3-settle" {:op :claim/settle :subject "claim-3"})

    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- policy-row [ledger {:keys [id policyholder insured-property coverage-type
                                   coverage-amount currency jurisdiction status policy-number]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s %s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc policyholder) (esc insured-property) (esc coverage-type)
          (esc coverage-amount) (esc currency) (esc jurisdiction) (esc (name (or status :n-a)))
          (if policy-number (esc policy-number) "<span class=\"muted\">n/a</span>")
          (status-cell ledger id)))

;; casualty.store's Store protocol has no "list all claims" method
;; (unlike `all-policies`) -- only point lookup by id (`claim`). These
;; are the exact, real claim ids `run-demo!` above filed against pol-1;
;; echoing them here to look up their real committed state is not
;; inventing data, it is the only way to enumerate this scenario's own
;; claims through the protocol as it exists.
(def ^:private claim-ids ["claim-1" "claim-3"])

(defn- claim-row [db ledger claim-id]
  (let [{:keys [id policy-id claimant claimed-amount loss-date loss-description
                status settlement-number]} (store/claim db claim-id)]
    (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
            (esc id) (esc policy-id) (esc claimant) (esc claimed-amount) (esc loss-date)
            (esc loss-description) (esc (name (or status :n-a)))
            (if settlement-number (esc settlement-number) "<span class=\"muted\">n/a</span>")
            (status-cell ledger id))))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map #(if (keyword? %) (name %) %)) (str/join ", "))
                    (some-> disposition name) ""))))

(defn- binding-record-row [{:strs [record_id policyholder insured_property jurisdiction immutable]}]
  (format "        <tr><td>binding</td><td>%s</td><td>%s &middot; %s</td><td>%s</td><td>%s</td></tr>"
          (esc record_id) (esc policyholder) (esc insured_property) (esc jurisdiction)
          (if immutable "<span class=\"ok\">immutable draft</span>" "<span class=\"muted\">n/a</span>")))

(defn- settlement-record-row [{:strs [record_id policy_number claim_id jurisdiction immutable]}]
  (format "        <tr><td>settlement</td><td>%s</td><td>%s &middot; %s</td><td>%s</td><td>%s</td></tr>"
          (esc record_id) (esc policy_number) (esc claim_id) (esc jurisdiction)
          (if immutable "<span class=\"ok\">immutable draft</span>" "<span class=\"muted\">n/a</span>")))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract
  ;; (`casualty.governor`/`casualty.phase`) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately hand-
  ;; described rather than derived from a live run.
  ["        <tr><td><code>:policy/intake</code></td><td><span class=\"ok\">phase-3 auto-commit when clean, no capital risk yet</span></td></tr>"
   "        <tr><td><code>:jurisdiction/assess</code></td><td><span class=\"warn\">ALWAYS human approval when clean &middot; spec-basis independently checked against <code>casualty.facts</code>, never fabricated</span></td></tr>"
   "        <tr><td><code>:kyc/screen</code></td><td><span class=\"warn\">ALWAYS human approval when clean &middot; a sanctions/PEP hit is a HARD, un-overridable hold instead</span></td></tr>"
   "        <tr><td><code>:policy/bind</code></td><td><span class=\"warn\">ALWAYS human approval &middot; real coverage issued (actuation/bind) &middot; underwriting-document completeness independently checked, never auto at any phase</span></td></tr>"
   "        <tr><td><code>:claim/file</code></td><td><span class=\"ok\">phase-3 auto-commit when clean, no capital risk yet &middot; referenced policy must actually be bound, independently checked</span></td></tr>"
   "        <tr><td><code>:claim/settle</code></td><td><span class=\"warn\">ALWAYS human approval &middot; real claim payout (actuation/settle-claim) &middot; settlement amount independently recomputed against the policy's own coverage limit, plus a double-settlement guard, never auto at any phase</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        policies (store/all-policies db)
        policy-rows (str/join "\n" (map (partial policy-row ledger) policies))
        claim-rows (str/join "\n" (map (partial claim-row db ledger) claim-ids))
        binding-rows (str/join "\n" (map binding-record-row (store/binding-history db)))
        settlement-rows (str/join "\n" (map settlement-record-row (store/claim-history db)))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-6512 &middot; non-life (property &amp; casualty) insurance underwriting</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 1080px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Non-life (property &amp; casualty) insurance underwriting (ISIC 6512) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · policy binding/claim settlement always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Policies</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>casualty.store</code> via <code>casualty.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Policy</th><th>Policyholder</th><th>Insured property</th><th>Coverage type</th><th>Coverage amount</th><th>Jurisdiction</th><th>Status</th><th>Policy number</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     policy-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Claims</h2>\n"
     "    <table>\n"
     "      <thead><tr><th>Claim</th><th>Policy</th><th>Claimant</th><th>Claimed amount</th><th>Loss date</th><th>Loss description</th><th>Status</th><th>Settlement number</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     claim-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Draft policy-binding / claim-settlement records</h2>\n"
     "    <p class=\"muted\">Unsigned drafts only — the licensed underwriter's/adjuster's own act of signing is outside this actor's authority (see README <code>Actuation</code>).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Kind</th><th>Record id</th><th>Subject</th><th>Jurisdiction</th><th>Status</th></tr></thead>\n"
     "      <tbody>\n"
     binding-rows (when (seq binding-rows) "\n")
     settlement-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Non-Life Insurance Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden by a human approver. Jurisdiction spec-basis, underwriting-document evidence completeness, claim-settlement arithmetic (against the policy's own coverage limit) and sanctions/PEP status are independently recomputed, never trusted from the advisor's proposal; a real policy binding or claim settlement is always a human underwriter's/adjuster's call, at every rollout phase.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/binding-history db)) "binding drafts,"
             (count (store/claim-history db)) "settlement drafts )")))
