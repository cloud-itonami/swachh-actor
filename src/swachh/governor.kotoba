(ns swachh.governor
  "SanitationGovernor — the independent compliance layer that earns the
  waste-ops-LLM the right to commit. The LLM has no notion of permission,
  purpose, vendor legitimacy or PII-disclosure limits, so this MUST be a
  separate system (rules + permission table + protected-entity table) able
  to *reject* a proposal and fall back to HOLD (write nothing) — the
  sanitation analog of robotaxi's Minimal Risk Condition / talent's
  PolicyGovernor.

  Six checks, in priority order. The first four are HARD violations: a
  human approver CANNOT override them (you don't get to approve your way
  past routing waste to an unlicensed handler or exposing an informal
  worker's home address). The last two are SOFT: they only ask a human to
  look (low confidence / high-stakes), and the human may approve.

    1. RBAC                — role × operation × zone-relation permitted?
    2. Purpose limitation  — declared purpose + zone consent/legal basis?
    3. Worker privacy      — did the rationale cite the zone's informal
                              worker's identity as a basis?
    4. Vendor eligibility  — is a proposed shipment vendor licensed?
    5. Minimal disclosure  — does an export exceed the purpose's allowed
                              columns (e.g. worker PII in a public dashboard)?
    6. Confidence floor / high-stakes gate — escalate to a human."
  (:require [clojure.set :as set]
            [swachh.store :as store]))

;; ───────────────────────── policy tables ─────────────────────────

(def worker-privacy-attrs
  "Citing the zone's informal worker's identity as a judgement basis is
  always a hard violation — over 1M informal waste-pickers in India rely on
  not being surfaced in operational decision rationale (harassment/eviction
  risk). `:collector` covers citing the person as a basis; the individual
  PII fields cover over-disclosure in exports (see `purpose-columns`)."
  #{:collector})

(def worker-pii-columns
  #{:collector-name :collector-aadhaar :collector-home-address})

(def confidence-floor 0.6)

(def high-stakes
  "Operations grave enough to always require a human, even when clean."
  #{:large-shipment :hygiene-escalation})

(def permissions
  "role → set of operations it may perform. `:collection-supervisor` is
  further restricted to its own supervised zones by `subject-allowed?`."
  {:ward-officer          #{:collection/dispatch :shipment/propose :route/propose :report/export}
   :collection-supervisor #{:shipment/propose :route/propose}
   :field-worker          #{}})

(def purpose-columns
  "For :report/export — the columns each declared purpose may disclose.
  Anything beyond this is over-disclosure (minimal-disclosure violation).
  Worker PII is never in an allowed set here — a wider purpose (e.g.
  `:worker-payroll`) would need its own explicit, narrowly-scoped entry,
  which this scaffold deliberately does not ship."
  {:public-dashboard #{:id :ward :observed-backlog}
   :internal-ops     #{:id :ward :observed-backlog :collection-capacity}})

;; ───────────────────────── checks ─────────────────────────

(defn- subject-allowed?
  "A collection-supervisor may only act on zones they supervise; a
  ward-officer on any zone."
  [{:keys [actor-role actor-id]} subject st]
  (case actor-role
    :ward-officer true
    :collection-supervisor (= actor-id (:supervisor (store/zone st subject)))
    false))

(defn- rbac-violations [{:keys [op]} {:keys [actor-role] :as ctx} subject st]
  (cond-> []
    (not (contains? (get permissions actor-role #{}) op))
    (conj {:rule :rbac :detail (str actor-role " は " op " の権限を持たない")})
    (and (contains? (get permissions actor-role #{}) op)
         (not (subject-allowed? ctx subject st)))
    (conj {:rule :rbac-subject :detail (str actor-role " は対象ゾーン " subject " に権限が及ばない")})))

(defn- purpose-violations [{:keys [purpose consent?]}]
  (cond-> []
    (nil? purpose)    (conj {:rule :purpose :detail "利用目的が宣言されていない"})
    (false? consent?) (conj {:rule :consent :detail "自治体の同意/法的根拠が無い"})))

(defn- worker-privacy-violations
  "Did the rationale CITE the zone's informal worker's identity as a basis
  for a shipment/route judgement? Report disclosure is governed
  separately by `disclosure-violations`, so reports don't double-count
  here."
  [{:keys [op]} proposal]
  (when (not= op :report/export)
    (let [cited (set (map keyword (:cites proposal)))
          bad   (set/intersection cited worker-privacy-attrs)]
      (when (seq bad)
        [{:rule :worker-privacy :detail (str "収集人の個人情報を判断根拠に使用: " (vec bad))}]))))

(defn- vendor-eligibility-violations
  "A shipment proposal recommending an unlicensed vendor is a hard
  violation — routing municipal waste to an unregistered handler undermines
  the whole recycling-business governance point of this actor."
  [{:keys [op]} proposal st]
  (when (= op :shipment/propose)
    (when-let [vid (:vendor-id proposal)]
      (when-not (store/licensed-vendor? st vid)
        [{:rule :vendor-eligibility :detail (str "未登録業者への出荷提案: " vid)}]))))

(defn- disclosure-violations [{:keys [op]} {:keys [purpose]} proposal]
  (when (= op :report/export)
    (let [allowed (get purpose-columns purpose #{})
          cols    (set (:columns proposal))
          extra   (set/difference cols allowed)]
      (when (seq extra)
        [{:rule :minimal-disclosure
          :detail (str "目的 " purpose " に対し過剰な列: " (vec extra))}]))))

(defn check
  "Censors a waste-ops-LLM proposal against the policy tables. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}.

   - :hard?       — at least one HARD violation (RBAC/purpose/worker-
                    privacy/vendor-eligibility/disclosure). Forces HOLD; a
                    human cannot override.
   - :escalate?   — soft: low confidence OR high-stakes. A human decides.
   - :ok?         — clean AND not escalating: safe to auto-commit."
  [request context proposal st]
  (let [subject (:subject request)
        hard    (into []
                      (concat (rbac-violations request context subject st)
                              (purpose-violations context)
                              (worker-privacy-violations request proposal)
                              (vendor-eligibility-violations request proposal st)
                              (disclosure-violations request context proposal)))
        conf    (:confidence proposal 0.0)
        low?    (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard?   (boolean (seq hard))]
    {:ok?         (and (not hard?) (not low?) (not stakes?))
     :violations  hard
     :confidence  conf
     :hard?       hard?
     ;; soft escalation only matters when there is no hard violation —
     ;; a hard violation always wins and goes straight to HOLD.
     :escalate?   (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD). The
  sanitation analog of robotaxi logging a safety-reject + MRC."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
