(ns casualty.underwriterllm
  "Underwriter-LLM client -- the *contained intelligence node* for the
  non-life (property & casualty) insurance actor.

  It normalizes policy intake, drafts a per-jurisdiction underwriting-
  document checklist, screens policyholders/claimants against a KYC/
  sanctions signal, drafts the policy-binding action, normalizes claim
  filing, and drafts the claim-settlement action. CRITICAL: it is a
  smart-but-untrusted advisor. It returns a *proposal* (with a rationale
  + the fields it cited), never a committed record or a real policy
  binding/claim payout. Every output is censored downstream by
  `casualty.governor` before anything touches the SSoT, and `:policy/
  bind`/`:claim/settle` proposals NEVER auto-commit at any phase -- see
  README `Actuation`.

  Like `cloud-itonami-isic-6511`'s `underwriting.underwriterllm`, this is
  a deterministic mock so the actor graph runs offline and the governor
  contract is exercised end-to-end. In production this calls a real LLM
  (kotoba-llm or equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/bind | :actuation/settle-claim | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [casualty.facts :as facts]
            [casualty.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the policyholder, insured property, coverage type/
  amount or jurisdiction. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "契約申込レコード更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :policy/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction underwriting-document checklist draft. `:no-spec?`
  injects the failure mode we must defend against: proposing a checklist
  for a jurisdiction with NO official spec-basis in `casualty.facts` --
  the Non-Life Insurance Governor must reject this (never invent a
  jurisdiction's law)."
  [db {:keys [subject no-spec?]}]
  (let [p (store/policy db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction p))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "casualty.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-docs sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-docs sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-kyc
  "KYC / sanctions screening draft. `:sanctions-hit?` on the party record
  injects the failure mode: the Non-Life Insurance Governor must HOLD,
  un-overridably, on any sanctions/PEP hit. Missing identification yields
  low confidence -> escalate rather than auto-clear."
  [db {:keys [subject]}]
  (let [p (store/party db subject)]
    (cond
      (nil? p)
      {:summary "対象partyが見つかりません" :rationale "no party record"
       :cites [] :effect :kyc/set :value {:party-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (:sanctions-hit? p)
      {:summary    (str (:name p) ": 制裁/PEPリストと一致")
       :rationale  "スクリーニングが一致を検出。人手確認とホールドが必須。"
       :cites      [:sanctions-list]
       :effect     :kyc/set
       :value      {:party-id subject :verdict :hit}
       :stake      nil
       :confidence 0.95}

      (nil? (:id-doc p))
      {:summary    (str (:name p) ": 本人確認書類が未提出")
       :rationale  "本人確認書類が無いため確信度を上げられない。"
       :cites      [:id-doc]
       :effect     :kyc/set
       :value      {:party-id subject :verdict :incomplete}
       :stake      nil
       :confidence 0.4}

      :else
      {:summary    (str (:name p) ": 制裁リスト一致なし、本人確認書類あり")
       :rationale  "本人確認書類確認 + 制裁リスト非一致。"
       :cites      [:id-doc :sanctions-list]
       :effect     :kyc/set
       :value      {:party-id subject :verdict :clear}
       :stake      nil
       :confidence 0.9})))

(defn- propose-bind
  "Draft the actual policy-binding action -- issuing real property/
  casualty coverage. ALWAYS `:stake :actuation/bind` -- this is a
  REAL-WORLD act (a policyholder becomes covered, premium obligations
  begin), never a draft the actor may auto-run. See README `Actuation`:
  no phase ever adds this op to a phase's `:auto` set
  (`casualty.phase`); the governor also always escalates on
  `:actuation/bind`. Two independent layers agree, deliberately."
  [db {:keys [subject]}]
  (let [p (store/policy db subject)
        assessment (store/assessment-of db subject)
        docs-ok? (and assessment (facts/required-docs-satisfied?
                                  (:jurisdiction p)
                                  (:checklist assessment)))]
    {:summary    (str (:policyholder p) " (" (:jurisdiction p)
                      ") の契約成立準備ができました" (when-not docs-ok? " (書類未充足)"))
     :rationale  (if assessment
                   (str "spec-basis: " (:spec-basis assessment))
                   "assessment未実施")
     :cites      (if assessment [(:spec-basis assessment)] [])
     :effect     :policy/mark-bound
     :value      {:policy-id subject}
     :stake      :actuation/bind
     :confidence (if docs-ok? 0.9 0.3)}))

(defn- propose-claim-filing
  "Directory upsert for a new claim -- the LLM only normalizes/validates
  the filed claim's fields (policy-id, claimant, claimed-amount,
  loss-date, loss-description); it does not invent them. High
  confidence, low stakes -- filing itself moves no capital, unlike
  settling it."
  [_db {:keys [subject policy-id claimant claimed-amount loss-date loss-description]}]
  {:summary    (str subject " (policy " policy-id ") の保険金請求を受付")
   :rationale  "入力された保険金請求事実の正規化のみ。新規事実の生成なし。"
   :cites      [:policy-id :claimed-amount :loss-date]
   :effect     :claim/filed
   :value      {:id subject :policy-id policy-id :claimant claimant
               :claimed-amount claimed-amount :loss-date loss-date
               :loss-description loss-description :status :filed}
   :stake      nil
   :confidence 0.95})

(defn- propose-claim-settlement
  "Draft the actual claim-SETTLEMENT action -- paying out a real claim
  against bound property/casualty coverage. ALWAYS `:stake :actuation/
  settle-claim` -- this is a REAL-WORLD act (real money leaves the
  insurer), never a draft the actor may auto-run. See README
  `Actuation`: no phase ever adds this op to a phase's `:auto` set
  (`casualty.phase`); the governor also always escalates on
  `:actuation/settle-claim`. Two independent layers agree, deliberately."
  [db {:keys [subject]}]
  (let [c (store/claim db subject)
        p (when c (store/policy db (:policy-id c)))
        within-coverage? (and c p (<= (double (:claimed-amount c)) (double (:coverage-amount p))))]
    {:summary    (str subject " 向け保険金支払い提案"
                      (when c (str " (claimed=" (:claimed-amount c) ")")))
     :rationale  (if p
                   (str "policy " (:id p) " coverage_amount=" (:coverage-amount p))
                   "claimまたはpolicyが見つかりません")
     :cites      (if c [(:policy-id c)] [])
     :effect     :claim/mark-settled
     :value      {:claim-id subject}
     :stake      :actuation/settle-claim
     :confidence (if within-coverage? 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :policy/intake        (normalize-intake db request)
    :jurisdiction/assess   (assess-jurisdiction db request)
    :kyc/screen            (screen-kyc db request)
    :policy/bind           (propose-bind db request)
    :claim/file            (propose-claim-filing db request)
    :claim/settle          (propose-claim-settlement db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは損害保険(非生命保険)引受・保険金支払いエージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:policy/upsert|:assessment/set|:kyc/set|:policy/mark-bound|"
       ":claim/filed|:claim/mark-settled) "
       ":stake(:actuation/bind か :actuation/settle-claim か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess {:policy (store/policy st subject)}
    :kyc/screen          {:party (store/party st subject)}
    :policy/bind         {:policy (store/policy st subject)
                          :assessment (store/assessment-of st subject)}
    :claim/settle        {:claim (store/claim st subject)}
    {:policy (store/policy st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Non-Life Insurance Governor
  escalates/holds -- an LLM hiccup can never auto-bind coverage or
  auto-settle a claim."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :underwriterllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
