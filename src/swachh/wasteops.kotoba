(ns swachh.wasteops
  "waste-ops-LLM client — the *contained intelligence node*.

  It drafts collection/dispatch patches, proposes which recycling vendor a
  sorted-material batch should ship to, proposes a collection-route
  priority change, and proposes report columns. CRITICAL: it is a smart-
  but-untrusted advisor. It returns a *proposal* (with a rationale + the
  fields/facts it cited), never a committed record. Every output is
  censored downstream by `swachh.governor` before anything touches the SSoT.

  Scope note: this actor does NOT run a system-dynamics stock/flow model
  itself. `propose-route` (below) reads `:observed-backlog` (a plain
  supervisor-reported number) and, when present, `(store/insight-of ..)` —
  the last analysis payload written by a separate ANALYSIS actor, `junkan`
  (`orgs/etzhayyim/root/20-actors/junkan`, extended by
  `junkan/methods/waste_dynamics.cljc` for this domain per ADR-2607113000).
  junkan projects the street-level uncollected-waste stock/flow trajectory
  and may flag a vicious-cycle/leverage-point; swachh treats that verdict as
  one more input fact for a ROUTING/DISPATCH decision, the same way it reads
  the zone's raw `:observed-backlog` — it never computes the projection.

  Like talent.hrllm / robotaxi.ar1, this is a deterministic mock so the
  actor graph runs offline and the governor contract is exercised end-to-
  end. In production this calls a real LLM (kotoba-llm) with the same
  proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why — SCANNED by the worker-privacy gate
     :cites      [kw|str ..]    ; fields/facts the LLM used — SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :vendor-id  str|nil        ; :shipment/propose only — SCANNED by the
                                 ; vendor-eligibility gate
     :stake      kw|nil         ; :large-shipment/:hygiene-escalation/...
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [kotoba.lang.text :as str]
            [langchain.model :as model]
            [swachh.store :as store]))

(defn- normalize-dispatch
  "Collection/dispatch — the LLM only normalizes/validates the patch; it
  does not invent fields. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "収集ゾーン更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :upsert-zone
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- propose-shipment
  "Shipment/propose — which recycler a sorted-recyclable batch should ship
  to, and how much. `:bias?` injects the failure mode we must defend
  against: citing the zone's informal-worker PII (`:collector`) as a basis
  — the worker-privacy gate must reject this. `:unlicensed?` injects the
  other failure mode: recommending an unregistered scrap vendor — the
  vendor-eligibility gate must reject this."
  [db {:keys [subject bias? unlicensed? quantity] :or {quantity 5.0}}]
  (let [z (store/zone db subject)
        vendor-id (if unlicensed? "v-99" "v-10")]
    (if bias?
      {:summary    (str (:name z) " のバッチを " vendor-id " へ出荷提案。")
       :rationale  (str "担当収集人 " (get-in z [:collector :name])
                        "（" (get-in z [:collector :home-address]) "）の実績に基づき選定。")
       :cites      [:collection-capacity :collector]
       :effect     :set-shipment
       :vendor-id  vendor-id
       :quantity   quantity
       :stake      (when (> quantity 20.0) :large-shipment)
       :confidence 0.85}
      {:summary    (str (:name z) " のリサイクル可能ゴミ " quantity "単位を "
                        vendor-id " へ出荷提案。")
       :rationale  (str "vendor の受入資材適合度と直近スループットに基づく。")
       :cites      [:collection-capacity]
       :effect     :set-shipment
       :vendor-id  vendor-id
       :quantity   quantity
       :stake      (when (> quantity 20.0) :large-shipment)
       :confidence 0.9})))

(defn- propose-route
  "Route/propose — recommend a collection-priority change for a zone this
  cycle. Reads `:observed-backlog` (a plain supervisor-reported number,
  NOT a simulated stock) relative to `:collection-capacity`, plus — when
  present — `(store/insight-of db subject)`: the last verdict written by
  junkan (a separate analysis actor; see ns docstring), e.g. a vicious-
  cycle/leverage-point flag over the same zone's street-level backlog
  trajectory. This LLM node does not run that projection itself; it only
  reads whatever junkan already wrote and folds it into the routing call.
  A high-risk read is deliberately low-confidence so the governor escalates
  to a human (ward officer) rather than auto-committing."
  [db {:keys [subject]}]
  (let [z         (store/zone db subject)
        insight   (store/insight-of db subject)
        backlog   (:observed-backlog z)
        capacity  (:collection-capacity z)
        days      (if (and capacity (pos? capacity)) (/ backlog capacity) 0.0)
        risk      (cond (>= days 3.0) :high (>= days 1.0) :medium :else :low)
        vicious?  (boolean (:vicious-cycle? insight))
        priority  (if (or (= :high risk) vicious?) :high :normal)]
    {:summary    (str (:name z) ": backlog " backlog "/" capacity
                      " (換算 " days " 日分)・リスク " (name risk)
                      (when vicious? "・junkan: 悪循環判定")
                      " → route-priority " (name priority))
     :rationale  (str "observed-backlog " backlog " / collection-capacity " capacity "。"
                      (if insight
                        (str "junkan insight（別actorの分析出力）を参照: " (pr-str insight))
                        "junkan insight 未取得のためゾーンの生値のみで判定。"))
     :cites      (cond-> [:observed-backlog :collection-capacity]
                   insight (conj :insight))
     :effect     :upsert-zone
     :value      {:id subject :route-priority priority}
     :stake      (when (or (= :high risk) vicious?) :hygiene-escalation)
     ;; a high-risk read is deliberately low-confidence — an escalation
     ;; call should pass a human, not auto-commit.
     :confidence (if (or (= :high risk) vicious?) 0.5 0.85)}))

(defn- propose-columns
  "Report column proposal. `:greedy?` injects over-disclosure (pulls the
  zone's informal-worker PII columns) — the minimal-disclosure gate must
  reject it."
  [_db {:keys [greedy?]}]
  (if greedy?
    {:summary "出力列: id,ward,observed-backlog,collector-name,collector-aadhaar,collector-home-address"
     :rationale "分析に有用そうな列を広めに含めた。"
     :cites [:id :ward :observed-backlog :collector-name :collector-aadhaar :collector-home-address]
     :columns [:id :ward :observed-backlog :collector-name :collector-aadhaar :collector-home-address]
     :effect :report-export :stake nil :confidence 0.9}
    {:summary "出力列: id,ward,observed-backlog"
     :rationale "帳票目的（public-dashboard）に必要な最小列のみ。"
     :cites [:id :ward :observed-backlog]
     :columns [:id :ward :observed-backlog]
     :effect :report-export :stake nil :confidence 0.95}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject zone-id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :collection/dispatch (normalize-dispatch db request)
    :shipment/propose    (propose-shipment db request)
    :route/propose       (propose-route db request)
    :report/export       (propose-columns db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ───────────────────────── Advisor protocol ─────────────────────────
;; The advisor is injected into the OperationActor, so the contained
;; intelligence node is a swap: a deterministic mock for dev/tests, or a real
;; LLM in production. Either way its output is a PROPOSAL the
;; SanitationGovernor still censors — the single invariant never depends on
;; which advisor ran.

(defprotocol Advisor
  (-advise [advisor store request] "store + request → proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは自治体のゴミ収集・分別・リサイクル業務の助言者です。与えられた事実のみ"
       "に基づき、提案を1つだけ EDN マップで返します。説明や前置きは一切書かず、EDN だけ"
       "を出力します。\nキー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) :effect(:upsert-zone|:set-shipment) "
       ":vendor-id(shipment時のみ) :stake(:large-shipment 等/無ければ nil) :confidence(0..1)。\n"
       "重要: ゾーン担当の収集人(:collector)の個人情報(氏名・Aadhaar・住所)を根拠"
       "(:cites/:rationale)にしてはいけません。未登録(unlicensed)業者を出荷先として"
       "推薦してはいけません。街路滞留ゴミの stock/flow 予測は自分で計算せず、"
       "与えられた :insight（別actor junkan の分析出力）があればそれを事実として"
       "参照するだけにしてください。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :shipment/propose {:zone (store/zone st subject)}
    :route/propose    {:zone (store/zone st subject) :insight (store/insight-of st subject)}
    {:zone (store/zone st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure yields
  a safe low-confidence noop so the SanitationGovernor escalates/holds — an
  LLM hiccup can never auto-commit."
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
  "An advisor backed by a `langchain.model/ChatModel` (real inference). Pass
  `model/anthropic-model`, an OpenAI-compatible model (Ollama/vLLM/kotoba), or
  `model/mock-model` for offline tests. `gen-opts` is forwarded to -generate."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\nゾーン: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record — the LLM's interpretable rationale is a
  key asset (shipment disputes, hygiene-escalation audits). Persisted to the
  :audit channel."
  [request proposal]
  {:t          :wasteops-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
