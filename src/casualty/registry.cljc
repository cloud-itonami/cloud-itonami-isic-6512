(ns casualty.registry
  "Pure-function policy-binding and claim-settlement record construction --
  an append-only non-life (property & casualty) insurance book-of-record
  draft.

  Like `cloud-itonami-isic-6511`'s `underwriting.registry`, there is no
  single international check-digit standard for a P&C policy number or a
  claim-settlement number -- every insurer/jurisdiction assigns its own
  reference format. This namespace does NOT invent one; it builds a
  jurisdiction-scoped sequence number and validates the record's required
  fields, the same honest, non-fabricating discipline `casualty.facts`
  uses.

  This namespace is pure data + pure functions -- no I/O, no network call
  to any insurer's core-administration or claims system. It builds the
  RECORD an operator would keep, not the act of binding coverage or
  paying a claim itself (those are `casualty.operation`'s `:policy/bind`
  and `:claim/settle`, always human-gated -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  licensed underwriter's/adjuster's act, not this actor's. See README
  `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-binding
  "Validate + construct a non-life policy-binding registration DRAFT. Pure
  function -- does not touch any real insurer system or bind any real
  coverage."
  [policyholder insured-property coverage-type coverage-amount jurisdiction sequence]
  (when-not (and policyholder (not= policyholder ""))
    (throw (ex-info "binding: policyholder required" {})))
  (when-not (and insured-property (not= insured-property ""))
    (throw (ex-info "binding: insured-property required" {})))
  (when-not (and coverage-type (not= coverage-type ""))
    (throw (ex-info "binding: coverage-type required" {})))
  (when (< coverage-amount 0)
    (throw (ex-info "binding: coverage-amount must be >= 0" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "binding: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "binding: sequence must be >= 0" {})))
  (let [policy-number (str (str/upper-case jurisdiction) "-" (zero-pad sequence 8))
        record {"record_id" policy-number
                "kind" "binding-draft"
                "policyholder" policyholder
                "insured_property" insured-property
                "coverage_type" coverage-type
                "coverage_amount" coverage-amount
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "policy_number" policy-number
     "certificate" (unsigned-certificate "PolicyBindingCertificate" policy-number policy-number)}))

(defn register-claim-settlement
  "Validate + construct the claim-SETTLEMENT DRAFT -- the insurer's own
  legal act of paying out a filed claim against a bound policy. Pure
  function -- does not touch any real banking/claims-payment system; it
  builds the RECORD a claims adjuster would keep. `casualty.governor`
  independently re-verifies the settlement amount against the policy's
  own coverage limit, and blocks a double-settlement of the same claim,
  before this is ever allowed to commit."
  [policy-number claim-id settlement-amount jurisdiction sequence]
  (when-not (and policy-number (not= policy-number ""))
    (throw (ex-info "claim-settlement: policy_number required" {})))
  (when-not (and claim-id (not= claim-id ""))
    (throw (ex-info "claim-settlement: claim_id required" {})))
  (when (< settlement-amount 0)
    (throw (ex-info "claim-settlement: settlement-amount must be >= 0" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "claim-settlement: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "claim-settlement: sequence must be >= 0" {})))
  (let [settlement-number (str (str/upper-case jurisdiction) "-CLAIM-" (zero-pad sequence 6))
        record {"record_id" settlement-number
                "kind" "claim-settlement-draft"
                "policy_number" policy-number
                "claim_id" claim-id
                "settlement_amount" settlement-amount
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "settlement_number" settlement-number
     "certificate" (unsigned-certificate "ClaimSettlementCertificate" settlement-number settlement-number)}))

(defn append
  "Append a binding/claim-settlement record, returning a NEW list (never
  mutate history in place)."
  [history result]
  (conj (vec history) (get result "record")))
