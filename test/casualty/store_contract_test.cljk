(ns casualty.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [casualty.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "party-1" (:policyholder (store/policy s "pol-1"))))
      (is (= "JPN" (:jurisdiction (store/policy s "pol-1"))))
      (is (= "motor" (:coverage-type (store/policy s "pol-1"))))
      (is (= "田中 花子" (:name (store/party s "party-1"))))
      (is (false? (:sanctions-hit? (store/party s "party-1"))))
      (is (true? (:sanctions-hit? (store/party s "party-3"))))
      (is (= ["pol-1" "pol-2"] (mapv :id (store/all-policies s))))
      (is (nil? (store/claim s "claim-1")))
      (is (nil? (store/kyc-of s "party-1")))
      (is (nil? (store/assessment-of s "pol-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/binding-history s)))
      (is (= [] (store/claim-history s)))
      (is (zero? (store/next-sequence s "JPN")))
      (is (zero? (store/claim-sequence s "JPN")))
      (is (false? (store/claim-already-settled? s "claim-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :policy/upsert
                                 :value {:id "pol-1" :status :ready}})
        (is (= :ready (:status (store/policy s "pol-1"))))
        (is (= "party-1" (:policyholder (store/policy s "pol-1"))) "policyholder preserved"))
      (testing "assessment / kyc payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["pol-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "pol-1")))
        (store/commit-record! s {:effect :kyc/set :path ["party-1"]
                                 :payload {:party-id "party-1" :verdict :clear}})
        (is (= {:party-id "party-1" :verdict :clear} (store/kyc-of s "party-1"))))
      (testing "binding drafts a policy record and advances the sequence"
        (store/commit-record! s {:effect :policy/mark-bound :path ["pol-1"]})
        ;; binding-history holds the inner "record" sub-map (registry/append's
        ;; convention), whose record-id key is "record_id".
        (is (= "JPN-00000000" (get (first (store/binding-history s)) "record_id")))
        (is (= "binding-draft" (get (first (store/binding-history s)) "kind")))
        (is (= :bound (:status (store/policy s "pol-1"))))
        (is (= 1 (count (store/binding-history s))))
        (is (= 1 (store/next-sequence s "JPN"))))
      (testing "claim filing writes a plain claim record (no draft/certificate -- filing moves no capital)"
        (store/commit-record! s {:effect :claim/filed
                                 :payload {:id "claim-1" :policy-id "pol-1" :claimant "party-1"
                                          :claimed-amount 500000 :loss-date "2026-06-01"
                                          :loss-description "test loss" :status :filed}})
        (is (= :filed (:status (store/claim s "claim-1"))))
        (is (= 500000 (:claimed-amount (store/claim s "claim-1")))))
      (testing "claim settlement drafts a settlement record and advances the claim sequence"
        (store/commit-record! s {:effect :claim/mark-settled :path ["claim-1"]})
        (is (= "JPN-CLAIM-000000" (get (first (store/claim-history s)) "record_id")))
        (is (= "claim-settlement-draft" (get (first (store/claim-history s)) "kind")))
        (is (= :settled (:status (store/claim s "claim-1"))))
        (is (= 1 (count (store/claim-history s))))
        (is (= 1 (store/claim-sequence s "JPN")))
        (is (true? (store/claim-already-settled? s "claim-1")))
        (is (false? (store/claim-already-settled? s "claim-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/policy s "nope")))
    (is (= [] (store/all-policies s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/binding-history s)))
    (is (= [] (store/claim-history s)))
    (is (zero? (store/next-sequence s "JPN")))
    (is (zero? (store/claim-sequence s "JPN")))
    (store/with-policies s {"x" {:id "x" :policyholder "p" :jurisdiction "JPN"
                                 :insured-property "widget" :coverage-type "fire"
                                 :coverage-amount 0 :currency "JPY" :status :intake}})
    (is (= "p" (:policyholder (store/policy s "x"))))))
