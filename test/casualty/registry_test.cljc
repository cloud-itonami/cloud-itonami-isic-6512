(ns casualty.registry-test
  (:require [clojure.test :refer [deftest is]]
            [casualty.registry :as r]))

;; ----------------------------- register-binding -----------------------------

(deftest binding-is-a-draft-not-a-real-binding
  (let [result (r/register-binding "party-1" "1978 Toyota Corolla" "motor" 3000000 "JPN" 1)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest binding-assigns-policy-number
  (let [result (r/register-binding "party-1" "1978 Toyota Corolla" "motor" 3000000 "JPN" 7)]
    (is (= (get result "policy_number") "JPN-00000007"))
    (is (= (get-in result ["record" "immutable"]) true))
    (is (= (get-in result ["record" "kind"]) "binding-draft"))
    (is (= (get-in result ["record" "coverage_type"]) "motor"))))

(deftest binding-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-binding "" "car" "motor" 0 "JPN" 1)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-binding "party-1" "" "motor" 0 "JPN" 1)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-binding "party-1" "car" "" 0 "JPN" 1)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-binding "party-1" "car" "motor" -1 "JPN" 1)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-binding "party-1" "car" "motor" 0 "" 1)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-binding "party-1" "car" "motor" 0 "JPN" -1))))

;; ----------------------------- register-claim-settlement -----------------------------

(deftest claim-settlement-is-a-draft-not-a-real-payment
  (let [result (r/register-claim-settlement "JPN-00000000" "claim-1" 500000 "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest claim-settlement-assigns-settlement-number
  (let [result (r/register-claim-settlement "JPN-00000000" "claim-1" 500000 "JPN" 7)]
    (is (= (get result "settlement_number") "JPN-CLAIM-000007"))
    (is (= (get-in result ["record" "policy_number"]) "JPN-00000000"))
    (is (= (get-in result ["record" "claim_id"]) "claim-1"))
    (is (= (get-in result ["record" "kind"]) "claim-settlement-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest claim-settlement-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-claim-settlement "" "claim-1" 500000 "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-claim-settlement "JPN-00000000" "" 500000 "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-claim-settlement "JPN-00000000" "claim-1" -1 "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-claim-settlement "JPN-00000000" "claim-1" 500000 "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-claim-settlement "JPN-00000000" "claim-1" 500000 "JPN" -1))))

(deftest claim-history-is-append-only
  (let [c1 (r/register-claim-settlement "JPN-00000000" "claim-1" 500000 "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-claim-settlement "JPN-00000000" "claim-2" 100000 "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-CLAIM-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-CLAIM-000001" (get-in hist2 [1 "record_id"])))))
