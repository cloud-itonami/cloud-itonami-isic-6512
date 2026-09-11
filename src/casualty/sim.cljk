(ns casualty.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean policy through
  intake -> jurisdiction underwriting assessment -> KYC screening ->
  policy-bind proposal (always escalates) -> human approval -> commit,
  then a clean claim through filing (auto-commits; no capital risk) ->
  claim-settlement proposal (always escalates) -> human approval ->
  commit, then shows six HARD holds (a sanctions hit, a jurisdiction with
  no spec-basis, a claim filed against an unbound policy, a claim
  settlement that would exceed the policy's own coverage limit, a
  settlement of a nonexistent claim, and a double-settlement of an
  already-settled claim) that never reach a human at all, and prints the
  audit ledger + the draft policy-binding and claim-settlement records."
  (:require [langgraph.graph :as g]
            [casualty.store :as store]
            [casualty.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :underwriter :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== policy/intake pol-1 (JPN, clean policyholder) ==")
    (println (exec! actor "t1" {:op :policy/intake :subject "pol-1"
                                :patch {:id "pol-1" :status :ready}} operator))

    (println "== jurisdiction/assess pol-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "pol-1"} operator))
    (println (approve! actor "t2"))

    (println "== kyc/screen party-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :kyc/screen :subject "party-1"} operator))
    (println (approve! actor "t3"))

    (println "== policy/bind pol-1 (always escalates -- actuation/bind) ==")
    (let [r (exec! actor "t4" {:op :policy/bind :subject "pol-1"} operator)]
      (println r)
      (println "-- human operator (licensed underwriter) approves --")
      (println (approve! actor "t4")))

    (println "== claim/file claim-1 against pol-1 (bound; 500,000 JPY of 3,000,000 coverage; auto-commits, no capital risk) ==")
    (println (exec! actor "t5" {:op :claim/file :subject "claim-1" :policy-id "pol-1"
                                :claimant "party-1" :claimed-amount 500000
                                :loss-date "2026-06-01" :loss-description "追突事故による損傷"} operator))

    (println "== claim/settle claim-1 (always escalates -- actuation/settle-claim) ==")
    (let [r (exec! actor "t6" {:op :claim/settle :subject "claim-1"} operator)]
      (println r)
      (println "-- human claims adjuster approves --")
      (println (approve! actor "t6")))

    (println "== kyc/screen party-3 (sanctions hit -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t7" {:op :kyc/screen :subject "party-3"} operator))

    (println "== jurisdiction/assess pol-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t8" {:op :jurisdiction/assess :subject "pol-2" :no-spec? true} operator))

    (println "== claim/file claim-2 against pol-2 (never bound -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t9" {:op :claim/file :subject "claim-2" :policy-id "pol-2"
                                :claimant "party-3" :claimed-amount 50
                                :loss-date "2026-06-01" :loss-description "fire damage"} operator))

    (println "== claim/file claim-3 against pol-1 (5,000,000 JPY, exceeding the 3,000,000 coverage limit; filing itself auto-commits) ==")
    (println (exec! actor "t10a" {:op :claim/file :subject "claim-3" :policy-id "pol-1"
                                  :claimant "party-1" :claimed-amount 5000000
                                  :loss-date "2026-06-15" :loss-description "全損事故"} operator))

    (println "== claim/settle claim-3 (claimed amount exceeds pol-1's own coverage limit -> HARD hold) ==")
    (println (exec! actor "t10" {:op :claim/settle :subject "claim-3"} operator))

    (println "== claim/settle claim-999 (nonexistent claim -> HARD hold) ==")
    (println (exec! actor "t11" {:op :claim/settle :subject "claim-999"} operator))

    (println "== claim/settle claim-1 AGAIN (double-settlement of an already-settled claim -> HARD hold) ==")
    (println (exec! actor "t12" {:op :claim/settle :subject "claim-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft policy-binding records ==")
    (doseq [r (store/binding-history db)] (println r))

    (println "== draft claim-settlement records ==")
    (doseq [r (store/claim-history db)] (println r))))
