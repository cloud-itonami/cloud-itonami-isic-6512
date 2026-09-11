(ns casualty.store
  "SSoT for the non-life (property & casualty) insurance actor, behind a
  `Store` protocol so the backend is a swap, not a rewrite -- the same
  seam `cloud-itonami-isic-6511`'s `underwriting.store` uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/casualty/store_contract_test.clj), which is the whole point: the
  actor, the Non-Life Insurance Governor and the audit ledger never know
  which SSoT they run on.

  The ledger stays append-only on every backend: 'who bound what coverage
  for which policyholder on what jurisdictional basis, which claim was
  filed and settled against which policy, approved by whom' is always a
  query over an immutable log -- the audit trail a policyholder trusting
  an operator with their coverage needs, and the evidence an operator
  needs if a binding or a settlement is later disputed."
  (:require [casualty.registry :as registry]
            [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (policy [s id])
  (all-policies [s])
  (party [s id])
  (claim [s id])
  (kyc-of [s party-id] "committed KYC screening verdict for a party, or nil")
  (assessment-of [s policy-id] "committed jurisdiction underwriting-requirement assessment, or nil")
  (ledger [s])
  (binding-history [s] "the append-only policy-binding history (casualty.registry drafts)")
  (claim-history [s] "the append-only claim-settlement history (casualty.registry drafts)")
  (next-sequence [s jurisdiction] "next policy-number sequence for a jurisdiction")
  (claim-sequence [s jurisdiction] "next claim-settlement-number sequence for a jurisdiction")
  (claim-already-settled? [s claim-id] "has this claim already been settled?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-policies [s policies] "replace/seed the policy directory (map id->policy)")
  (with-parties [s parties] "replace/seed the party directory (map id->party)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained policyholder/policy set so the actor + tests
  run offline."
  []
  {:policies
   {"pol-1" {:id "pol-1" :policyholder "party-1" :insured-property "1978 Toyota Corolla (motor)"
             :coverage-type "motor" :coverage-amount 3000000 :currency "JPY" :jurisdiction "JPN" :status :intake}
    "pol-2" {:id "pol-2" :policyholder "party-3" :insured-property "123 Main St, Anytown (fire)"
             :coverage-type "fire" :coverage-amount 100 :currency "USD" :jurisdiction "ATL" :status :intake}}
   :parties
   {"party-1" {:id "party-1" :name "田中 花子" :role :policyholder :sanctions-hit? false :id-doc "passport-jp-****9012"}
    "party-3" {:id "party-3" :name "J. Doe" :role :policyholder :sanctions-hit? true :id-doc nil}
    ;; deliberately clean on EVERY local field (no :sanctions-hit?, has an
    ;; id-doc) -- this name is shared with cloud-itonami-isic-8291's own
    ;; demo data (a sanctions-flagged official), so it exists purely to
    ;; prove casualty.corporate-intel's cross-reference catches a hit
    ;; this actor's local-only checks would otherwise miss entirely. Not
    ;; attached to any policy by default.
    "party-4" {:id "party-4" :name "Jane Smith (demo)" :role :policyholder
               :sanctions-hit? false :id-doc "passport-uk-****5678"}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- bind!
  "Backend-agnostic `:policy/mark-bound` -- looks up the policy via the
  protocol, drafts the policy-binding record, and returns
  {:result .. :policy-patch ..} for the caller to persist. Pure w.r.t.
  any particular backend's transaction mechanics."
  [s policy-id]
  (let [p (policy s policy-id)
        seq-n (next-sequence s (:jurisdiction p))
        result (registry/register-binding
                (:policyholder p) (:insured-property p) (:coverage-type p)
                (:coverage-amount p) (:jurisdiction p) seq-n)]
    {:result result
     :policy-patch {:status :bound
                   :policy-number (get result "policy_number")}}))

(defn- settle-claim!
  "Backend-agnostic `:claim/mark-settled` -- looks up the claim + its
  policy via the protocol, drafts the claim-settlement record, and
  returns {:result .. :claim-patch ..} for the caller to persist."
  [s claim-id]
  (let [c (claim s claim-id)
        p (policy s (:policy-id c))
        seq-n (claim-sequence s (:jurisdiction p))
        result (registry/register-claim-settlement
                (:policy-number p) claim-id (:claimed-amount c) (:jurisdiction p) seq-n)]
    {:result result
     :claim-patch {:status :settled
                  :settlement-number (get result "settlement_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (policy [_ id] (get-in @a [:policies id]))
  (all-policies [_] (sort-by :id (vals (:policies @a))))
  (party [_ id] (get-in @a [:parties id]))
  (claim [_ id] (get-in @a [:claims id]))
  (kyc-of [_ id] (get-in @a [:kyc id]))
  (assessment-of [_ policy-id] (get-in @a [:assessments policy-id]))
  (ledger [_] (:ledger @a))
  (binding-history [_] (:bindings @a))
  (claim-history [_] (:settlements @a))
  (next-sequence [_ jurisdiction] (get-in @a [:sequences jurisdiction] 0))
  (claim-sequence [_ jurisdiction] (get-in @a [:claim-sequences jurisdiction] 0))
  (claim-already-settled? [_ claim-id] (= :settled (get-in @a [:claims claim-id :status])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :policy/upsert
      (swap! a update-in [:policies (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :kyc/set
      (swap! a assoc-in [:kyc (first path)] payload)

      :policy/mark-bound
      (let [policy-id (first path)
            {:keys [result policy-patch]} (bind! s policy-id)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences (:jurisdiction (get-in state [:policies policy-id]))] (fnil inc 0))
                       (update-in [:policies policy-id] merge policy-patch)
                       (update :bindings registry/append result))))
        result)

      :claim/filed
      (swap! a assoc-in [:claims (:id payload)] payload)

      :claim/mark-settled
      (let [claim-id (first path)
            {:keys [result claim-patch]} (settle-claim! s claim-id)
            jurisdiction (:jurisdiction (policy s (:policy-id (claim s claim-id))))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:claim-sequences jurisdiction] (fnil inc 0))
                       (update-in [:claims claim-id] merge claim-patch)
                       (update :settlements registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-policies [s policies] (when (seq policies) (swap! a assoc :policies policies)) s)
  (with-parties [s parties] (when (seq parties) (swap! a assoc :parties parties)) s))

(defn seed-db
  "A MemStore seeded with the demo policy/policyholder set. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :kyc {} :ledger [] :sequences {}
                           :bindings [] :claims {} :claim-sequences {} :settlements []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (KYC/assessment payloads, ledger facts, binding/
  settlement records) are stored as EDN strings so `langchain.db`
  doesn't expand them into sub-entities -- the same convention
  `underwriting.store` uses."
  {:policy/id                 {:db/unique :db.unique/identity}
   :party/id                  {:db/unique :db.unique/identity}
   :claim/id                  {:db/unique :db.unique/identity}
   :kyc/party-id              {:db/unique :db.unique/identity}
   :assessment/policy-id      {:db/unique :db.unique/identity}
   :ledger/seq                {:db/unique :db.unique/identity}
   :binding/seq               {:db/unique :db.unique/identity}
   :settlement/seq            {:db/unique :db.unique/identity}
   :sequence/jurisdiction     {:db/unique :db.unique/identity}
   :claim-sequence/jurisdiction {:db/unique :db.unique/identity}})

(defn- policy->tx [{:keys [id policyholder insured-property coverage-type coverage-amount
                          currency jurisdiction status policy-number]}]
  (cond-> {:policy/id id}
    policyholder      (assoc :policy/policyholder policyholder)
    insured-property  (assoc :policy/insured-property insured-property)
    coverage-type     (assoc :policy/coverage-type coverage-type)
    coverage-amount   (assoc :policy/coverage-amount coverage-amount)
    currency          (assoc :policy/currency currency)
    jurisdiction      (assoc :policy/jurisdiction jurisdiction)
    status            (assoc :policy/status status)
    policy-number     (assoc :policy/policy-number policy-number)))

(def ^:private policy-pull
  [:policy/id :policy/policyholder :policy/insured-property :policy/coverage-type
   :policy/coverage-amount :policy/currency :policy/jurisdiction :policy/status :policy/policy-number])

(defn- pull->policy [m]
  (when (:policy/id m)
    {:id (:policy/id m) :policyholder (:policy/policyholder m)
     :insured-property (:policy/insured-property m) :coverage-type (:policy/coverage-type m)
     :coverage-amount (:policy/coverage-amount m) :currency (:policy/currency m)
     :jurisdiction (:policy/jurisdiction m) :status (:policy/status m)
     :policy-number (:policy/policy-number m)}))

(defn- party->tx [{:keys [id name role sanctions-hit? id-doc]}]
  (cond-> {:party/id id}
    name (assoc :party/name name)
    role (assoc :party/role role)
    (some? sanctions-hit?) (assoc :party/sanctions-hit? sanctions-hit?)
    id-doc (assoc :party/id-doc id-doc)))

(defn- pull->party [m]
  (when (:party/id m)
    {:id (:party/id m) :name (:party/name m) :role (:party/role m)
     :sanctions-hit? (boolean (:party/sanctions-hit? m)) :id-doc (:party/id-doc m)}))

(defn- claim->tx [{:keys [id policy-id claimant claimed-amount loss-date loss-description status settlement-number]}]
  (cond-> {:claim/id id}
    policy-id          (assoc :claim/policy-id policy-id)
    claimant           (assoc :claim/claimant claimant)
    claimed-amount     (assoc :claim/claimed-amount claimed-amount)
    loss-date          (assoc :claim/loss-date loss-date)
    loss-description   (assoc :claim/loss-description loss-description)
    status             (assoc :claim/status status)
    settlement-number  (assoc :claim/settlement-number settlement-number)))

(def ^:private claim-pull
  [:claim/id :claim/policy-id :claim/claimant :claim/claimed-amount
   :claim/loss-date :claim/loss-description :claim/status :claim/settlement-number])

(defn- pull->claim [m]
  (when (:claim/id m)
    {:id (:claim/id m) :policy-id (:claim/policy-id m) :claimant (:claim/claimant m)
     :claimed-amount (:claim/claimed-amount m) :loss-date (:claim/loss-date m)
     :loss-description (:claim/loss-description m) :status (:claim/status m)
     :settlement-number (:claim/settlement-number m)}))

(defrecord DatomicStore [conn]
  Store
  (policy [_ id]
    (pull->policy (d/pull (d/db conn) policy-pull [:policy/id id])))
  (all-policies [_]
    (->> (d/q '[:find [?id ...] :where [?e :policy/id ?id]] (d/db conn))
         (map #(pull->policy (d/pull (d/db conn) policy-pull [:policy/id %])))
         (sort-by :id)))
  (party [_ id]
    (pull->party (d/pull (d/db conn)
                         [:party/id :party/name :party/role :party/sanctions-hit? :party/id-doc]
                         [:party/id id])))
  (claim [_ id]
    (pull->claim (d/pull (d/db conn) claim-pull [:claim/id id])))
  (kyc-of [_ id]
    (ls/dec* (d/q '[:find ?p . :in $ ?pid
                :where [?k :kyc/party-id ?pid] [?k :kyc/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ policy-id]
    (ls/dec* (d/q '[:find ?p . :in $ ?pid
                :where [?a :assessment/policy-id ?pid] [?a :assessment/payload ?p]]
              (d/db conn) policy-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (binding-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :binding/seq ?s] [?e :binding/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (claim-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :settlement/seq ?s] [?e :settlement/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (next-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :sequence/jurisdiction ?j] [?e :sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (claim-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :claim-sequence/jurisdiction ?j] [?e :claim-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (claim-already-settled? [s claim-id]
    (= :settled (:status (claim s claim-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :policy/upsert
      (d/transact! conn [(policy->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/policy-id (first path) :assessment/payload (ls/enc payload)}])

      :kyc/set
      (d/transact! conn [{:kyc/party-id (first path) :kyc/payload (ls/enc payload)}])

      :policy/mark-bound
      (let [policy-id (first path)
            {:keys [result policy-patch]} (bind! s policy-id)
            jurisdiction (:jurisdiction (policy s policy-id))
            next-n (inc (next-sequence s jurisdiction))]
        (d/transact! conn
                     [(policy->tx (assoc policy-patch :id policy-id))
                      {:sequence/jurisdiction jurisdiction :sequence/next next-n}
                      {:binding/seq (count (binding-history s)) :binding/record (ls/enc (get result "record"))}])
        result)

      :claim/filed
      (d/transact! conn [(claim->tx payload)])

      :claim/mark-settled
      (let [claim-id (first path)
            {:keys [result claim-patch]} (settle-claim! s claim-id)
            jurisdiction (:jurisdiction (policy s (:policy-id (claim s claim-id))))
            next-n (inc (claim-sequence s jurisdiction))]
        (d/transact! conn
                     [(claim->tx (assoc claim-patch :id claim-id))
                      {:claim-sequence/jurisdiction jurisdiction :claim-sequence/next next-n}
                      {:settlement/seq (count (claim-history s)) :settlement/record (ls/enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (ls/enc fact)}])
    fact)
  (with-policies [s policies]
    (when (seq policies) (d/transact! conn (mapv policy->tx (vals policies)))) s)
  (with-parties [s parties]
    (when (seq parties) (d/transact! conn (mapv party->tx (vals parties)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:policies .. :parties ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [policies parties]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-policies policies) (with-parties parties)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo policy/policyholder set -- the
  Datomic-backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
