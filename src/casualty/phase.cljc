(ns casualty.phase
  "Phase 0->3 staged rollout -- the non-life-insurance analog of
  `cloud-itonami-isic-6511`'s `underwriting.phase`.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- policy intake allowed, every write needs
                                 human approval.
    Phase 2  assisted-assess  -- adds jurisdiction assessment + KYC
                                 screening writes, still approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:policy/intake`/`:claim/file` (no
                                 capital risk yet) may auto-commit.
                                 `:policy/bind`/`:claim/settle` NEVER
                                 auto-commit, at any phase.

  `:policy/bind`/`:claim/settle` are deliberately ABSENT from every
  phase's `:auto` set, including phase 3 -- a permanent structural fact,
  not a rollout milestone still to come. Binding real coverage and
  settling a real claim payout are the two real-world legal/financial
  acts this actor performs; both are always a human underwriter's/
  adjuster's call. `casualty.governor`'s `:actuation/bind`/`:actuation/
  settle-claim` high-stakes gate enforces the same invariant
  independently -- two layers, not one, agree on this. `:policy/intake`/
  `:claim/file` move no capital yet (still HARD-gated in `casualty.
  governor`, but never `high-stakes`), so both ARE auto-eligible at
  phase 3, the same multi-auto-op posture `cloud-itonami-isic-6499`'s
  `vcfund.phase` already establishes.

  The decision core is delegated to the safety kernel
  `casualty.kernels.gate` (integer-coded, fail-closed, safe-kotoba
  subset); this namespace keeps the human-readable phase table (the
  documentation and structural-invariant tests read it) and does the
  keyword<->wire-code mapping at the boundary. The kernel's own battery
  and the parity matrix in `casualty.kernels.gate-test` pin the two
  representations together."
  (:require [casualty.kernels.gate :as kernel]))

(def read-ops  #{})
(def write-ops #{:policy/intake :jurisdiction/assess :kyc/screen
                 :policy/bind :claim/file :claim/settle})

;; NOTE the invariant: `:policy/bind`/`:claim/settle` are members of
;; `write-ops` (governor-gated like any write) but are NEVER members of
;; any phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                                    :auto #{}}
   1 {:label "assisted-intake" :writes #{:policy/intake}                                      :auto #{}}
   2 {:label "assisted-assess" :writes #{:policy/intake :jurisdiction/assess :kyc/screen}      :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:policy/intake :claim/file}}})

(def default-phase 3)

;; ---- kernel wire-code bridges (façade-side, not kernel vocabulary) ----

(defn- op->code
  "Kernel op wire code. This actor has no read ops today, so nothing
  maps to the kernel's reserved read code 0. Unknown ops map to 7
  (unknown write) — the kernel never write-enables it, so an
  unrecognized op fails closed to HOLD exactly as the old
  set-membership logic did."
  [op]
  (cond
    (contains? read-ops op)      0
    (= op :policy/intake)        1
    (= op :jurisdiction/assess)  2
    (= op :kyc/screen)           3
    (= op :policy/bind)          4
    (= op :claim/file)           5
    (= op :claim/settle)         6
    :else                        7))

(defn- disposition->code [d]
  (cond (= d :commit) 0 (= d :escalate) 1 (= d :hold) 2 :else 2))

(defn- code->disposition [c]
  (if (= c 0) :commit (if (= c 1) :escalate :hold)))

(defn- code->reason [c]
  (if (= c 1) :phase-disabled (if (= c 2) :phase-approval nil)))

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:policy/bind`/`:claim/settle` are never auto-eligible at any phase,
    so they always escalate once the governor clears them (or hold if
    the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [p (if (contains? phases phase) phase default-phase)
        op-code (op->code op)
        gov-code (disposition->code governor-disposition)
        d (kernel/phase-disposition p op-code gov-code)
        r (kernel/phase-reason p op-code gov-code)]
    {:disposition (code->disposition d)
     :reason (code->reason r)}))

(defn verdict->disposition
  "Map a Non-Life Insurance Governor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
