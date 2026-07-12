(ns casualty.governor-contract-test
  "The governor contract as executable tests -- the non-life-insurance
  analog of `cloud-itonami-isic-6511`'s `underwriting.governor-contract-
  test`. The single invariant under test:

    Underwriter-LLM never binds a policy or settles a claim the Non-Life
    Insurance Governor would reject, `:policy/bind`/`:claim/settle`
    NEVER auto-commit at any phase, `:policy/intake`/`:claim/file` (no
    capital risk) MAY auto-commit when clean, and every decision (commit
    OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [casualty.store :as store]
            [casualty.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :underwriter :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- bind-pol1!
  "Walks pol-1 through assess -> approve -> kyc -> approve -> bind ->
  approve, leaving pol-1 :bound. Uses distinct thread-ids per call site
  by suffixing `tid-prefix`."
  [actor tid-prefix]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject "pol-1"} operator)
  (approve! actor (str tid-prefix "-assess"))
  (exec-op actor (str tid-prefix "-kyc") {:op :kyc/screen :subject "party-1"} operator)
  (approve! actor (str tid-prefix "-kyc"))
  (exec-op actor (str tid-prefix "-bind") {:op :policy/bind :subject "pol-1"} operator)
  (approve! actor (str tid-prefix "-bind")))

(defn- file-claim!
  [actor tid claim-id policy-id claimed-amount]
  (exec-op actor tid {:op :claim/file :subject claim-id :policy-id policy-id
                      :claimant "party-1" :claimed-amount claimed-amount
                      :loss-date "2026-06-01" :loss-description "test loss"} operator))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :policy/intake :subject "pol-1"
                   :patch {:id "pol-1" :status :ready}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :ready (:status (store/policy db "pol-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "pol-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "pol-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "pol-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "pol-1")) "no assessment written"))))

(deftest sanctions-hit-is-held-and-unoverridable
  (testing "a sanctions/PEP hit on a party -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :kyc/screen :subject "party-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:sanctions-hit} (-> (store/ledger db) first :basis)))
      (is (nil? (store/kyc-of db "party-3")) "no KYC clearance written"))))

(deftest bind-without-assessment-is-held
  (testing "policy/bind before any jurisdiction assessment -> HOLD (incomplete documents)"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :policy/bind :subject "pol-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:incomplete-documents} (-> (store/ledger db) first :basis))))))

(deftest policy-bind-always-escalates-then-human-decides
  (testing "a clean, fully-assessed binding still ALWAYS interrupts for human approval -- actuation/bind is never auto"
    (let [[db actor] (fresh)
          _ (exec-op actor "t6a" {:op :jurisdiction/assess :subject "pol-1"} operator)
          _ (approve! actor "t6a")
          _ (exec-op actor "t6b" {:op :kyc/screen :subject "party-1"} operator)
          _ (approve! actor "t6b")
          r1 (exec-op actor "t6" {:op :policy/bind :subject "pol-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, policy-binding record drafted"
        (let [r2 (approve! actor "t6")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :bound (:status (store/policy db "pol-1"))))
          (is (= 1 (count (store/binding-history db))) "one draft binding record")))))
  (testing "reject -> hold, nothing bound"
    (let [[db actor] (fresh)
          _ (exec-op actor "t7a" {:op :jurisdiction/assess :subject "pol-1"} operator)
          _ (approve! actor "t7a")
          _ (exec-op actor "t7" {:op :policy/bind :subject "pol-1"} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t7" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/binding-history db)) "nothing bound on reject"))))

(deftest claim-file-against-unbound-policy-is-held
  (testing "a claim filed against a never-bound policy -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (file-claim! actor "t8" "claim-1" "pol-1" 500000)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:policy-not-bound} (-> (store/ledger db) first :basis)))
      (is (nil? (store/claim db "claim-1")) "no claim written"))))

(deftest claim-file-against-bound-policy-auto-commits
  (testing ":claim/file moves no capital yet -- auto-eligible at phase 3, once the policy is bound"
    (let [[db actor] (fresh)
          _ (bind-pol1! actor "t9pre")
          res (file-claim! actor "t9" "claim-1" "pol-1" 500000)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= :filed (:status (store/claim db "claim-1"))) "SSoT actually updated"))))

(deftest claim-settle-with-missing-claim-is-held
  (testing "settling a claim id that was never filed -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t10" {:op :claim/settle :subject "claim-999"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:claim-missing} (-> (store/ledger db) first :basis)))
      (is (empty? (store/claim-history db))))))

(deftest claim-settle-exceeding-coverage-is-held
  (testing "a claim whose claimed-amount exceeds the policy's own coverage-amount -> HOLD"
    (let [[db actor] (fresh)
          _ (bind-pol1! actor "t11pre")
          _ (file-claim! actor "t11file" "claim-1" "pol-1" 5000000)
          res (exec-op actor "t11" {:op :claim/settle :subject "claim-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:claim-exceeds-coverage} (-> (store/ledger db) last :basis)))
      (is (empty? (store/claim-history db))))))

(deftest claim-settle-always-escalates-then-human-decides
  (testing "a clean, in-coverage settlement still ALWAYS interrupts for human approval -- actuation/settle-claim is never auto"
    (let [[db actor] (fresh)
          _ (bind-pol1! actor "t12pre")
          _ (file-claim! actor "t12file" "claim-1" "pol-1" 500000)
          r1 (exec-op actor "t12" {:op :claim/settle :subject "claim-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, claim-settlement record drafted"
        (let [r2 (approve! actor "t12")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :settled (:status (store/claim db "claim-1"))))
          (is (= 1 (count (store/claim-history db))) "one draft settlement record")))))
  (testing "reject -> hold, nothing settled"
    (let [[db actor] (fresh)
          _ (bind-pol1! actor "t13pre")
          _ (file-claim! actor "t13file" "claim-1" "pol-1" 500000)
          _ (exec-op actor "t13" {:op :claim/settle :subject "claim-1"} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t13" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/claim-history db)) "nothing settled on reject"))))

(deftest claim-settle-double-settlement-is-held
  (testing "settling the same claim twice -> HOLD on the second attempt, even though the figures match cleanly"
    (let [[db actor] (fresh)
          _ (bind-pol1! actor "t14pre")
          _ (file-claim! actor "t14file" "claim-1" "pol-1" 500000)
          _ (exec-op actor "t14a" {:op :claim/settle :subject "claim-1"} operator)
          _ (approve! actor "t14a")
          res (exec-op actor "t14" {:op :claim/settle :subject "claim-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:claim-already-settled} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/claim-history db))) "still only the one earlier settlement"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :policy/intake :subject "pol-1"
                          :patch {:id "pol-1" :status :ready}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "pol-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
