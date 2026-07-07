(ns casualty.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:policy/bind`/`:claim/settle` must NEVER be a member of
  any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [casualty.phase :as phase]))

(deftest policy-bind-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real binding"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :policy/bind))
          (str "phase " n " must not auto-commit :policy/bind")))))

(deftest claim-settle-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-settles a real claim payout"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :claim/settle))
          (str "phase " n " must not auto-commit :claim/settle")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":policy/intake and :claim/file move no capital -- auto-eligible"
    (is (= #{:policy/intake :claim/file} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :policy/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :policy/bind} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :claim/settle} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :policy/intake} :commit)))))
