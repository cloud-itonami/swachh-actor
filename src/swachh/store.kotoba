(ns swachh.store
  "SSoT for the swachh actor, behind a `Store` protocol so the backend is a
  swap, not a rewrite:

    - `MemStore`     — atom of Datomic-shaped EDN. The deterministic default
                       for dev/tests/demo (no deps).
    - `DatomicStore` — backed by `langchain.db`, a Datomic-API-compatible EAV
                       store (datalog q / pull / ref attrs / upsert). Pure
                       `.cljc`, so it runs offline AND can be pointed at a real
                       Datomic Local or a kotoba-server pod by swapping
                       `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/swachh/store_contract_test.cljc), which is the whole point: the
  actor, the SanitationGovernor and the audit ledger never know which SSoT
  they run on.

  Scope note (ADR-2607113000 + docs/adr/0001-architecture.md): this actor is
  a BUSINESS-EXECUTION actor (collection routing, shipment/recycling
  dispatch) — it does NOT model street-level waste stock/flow dynamics
  itself. `:insight-of` is exactly the seam for that: it holds whatever a
  separate ANALYSIS actor (`junkan`, `orgs/etzhayyim/root/20-actors/junkan`,
  extended by `junkan/methods/waste_dynamics.cljc`) last computed for a zone
  (e.g. a vicious-cycle/leverage-point verdict over the street-level
  uncollected-waste backlog) — swachh's waste-ops-LLM reads it as one more
  fact, the same way it reads `:observed-backlog` (a supervisor-reported
  number, not a model output). swachh never runs that model itself.

  The ledger stays append-only on every backend — 'who proposed/shipped/
  disclosed what for which zone, on what basis' is always a query over an
  immutable log."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [kotoba.lang.text :as str]
            [langchain.db :as d]))

(defprotocol Store
  (zone [s id])
  (all-zones [s])
  (supervised-zones [s supervisor-id] "zones a collection-supervisor oversees")
  (vendor [s id])
  (licensed-vendor? [s id])
  (shipment-of [s zone-id] "committed shipment payload for a zone, or nil")
  (insight-of [s zone-id]
    "last analysis payload for a zone, or nil — this is where a decision
    input FROM junkan (a separate analysis actor; see ns docstring) would be
    read from. swachh only reads/stores this map; it never computes it.")
  (ledger [s])
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-zones [s zones]     "replace/seed zones (map id→zone)")
  (with-vendors [s vendors] "replace/seed vendors (map id→vendor)"))

;; ───────────────────────── demo data ─────────────────────────

(defn demo-data
  "A small, self-contained pair of municipal zones so the actor + tests run
  offline. In prod, seed from a municipal GIS/route-management export.

  Each zone carries `:collection-capacity` (fleet capacity, an operational
  fact — NOT a stock/flow model) and `:observed-backlog` (a supervisor-
  reported street-level backlog reading — a plain observation, not a
  simulated stock), plus a `:collector` map — the informal waste-picker
  (kabadiwala) assigned to that zone. `:collector` exists precisely so the
  SanitationGovernor's worker-privacy gate has something real to defend:
  informal-sector workers' identity/address must never become a citation
  basis or an over-disclosed report column (this is the concrete, socially
  load-bearing analog of gftd-talent-actor's protected-attribute gate)."
  []
  {:zones
   {"z-001" {:id "z-001" :name "Andheri East, Zone 3" :ward "Andheri"
             :supervisor "sup-100" :collection-capacity 20.0
             :observed-backlog 15.0
             :collector {:id "w-01" :name "Ramesh K." :aadhaar "xxxx-xxxx-1234"
                         :home-address "Andheri East, chawl 4"}}
    "z-002" {:id "z-002" :name "Dharavi, Zone 7" :ward "Dharavi"
             :supervisor "sup-100" :collection-capacity 8.0
             :observed-backlog 60.0
             :collector {:id "w-02" :name "Sunita P." :aadhaar "xxxx-xxxx-5678"
                         :home-address "Dharavi, 90 Feet Road"}}}
   :vendors
   {"v-10" {:id "v-10" :name "Sahakari Bhandar MRF" :licensed? true
            :materials #{:plastic :paper :metal}}
    "v-99" {:id "v-99" :name "unregistered scrap yard" :licensed? false
            :materials #{:plastic :metal}}}})

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (zone [_ id] (get-in @a [:zones id]))
  (all-zones [_] (sort-by :id (vals (:zones @a))))
  (supervised-zones [_ supervisor-id]
    (->> (vals (:zones @a)) (filter #(= supervisor-id (:supervisor %))) (sort-by :id)))
  (vendor [_ id] (get-in @a [:vendors id]))
  (licensed-vendor? [_ id] (boolean (get-in @a [:vendors id :licensed?])))
  (shipment-of [_ id] (get-in @a [:shipments id]))
  (insight-of [_ id] (get-in @a [:insights id]))
  (ledger [_] (:ledger @a))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :upsert-zone   (swap! a update-in [:zones (:id value)] merge value)
      :set-shipment  (swap! a assoc-in [:shipments (first path)] payload)
      :store-insight (swap! a assoc-in [:insights (first path)] payload)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-zones [s zs]   (when (seq zs) (swap! a assoc :zones zs)) s)
  (with-vendors [s vs] (when (seq vs) (swap! a assoc :vendors vs)) s))

(defn seed-db
  "A MemStore seeded with the demo zones/vendors. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :shipments {} :insights {} :ledger []))))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────────────

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (:collector, :materials, payloads, ledger facts) are
  stored as EDN strings so `langchain.db` doesn't expand them into
  sub-entities."
  {:zone/id      {:db/unique :db.unique/identity}
   :vendor/id    {:db/unique :db.unique/identity}
   :shipment/zone  {:db/valueType :db.type/ref :db/unique :db.unique/identity}
   :insight/zone   {:db/valueType :db.type/ref :db/unique :db.unique/identity}
   :ledger/seq   {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- zone->tx [{:keys [id name ward supervisor collection-capacity
                          observed-backlog collector]}]
  (cond-> {:zone/id id}
    name                (assoc :zone/name name)
    ward                (assoc :zone/ward ward)
    supervisor          (assoc :zone/supervisor supervisor)
    collection-capacity (assoc :zone/collection-capacity collection-capacity)
    observed-backlog    (assoc :zone/observed-backlog observed-backlog)
    collector           (assoc :zone/collector (enc collector))))

(defn- pull->zone [m]
  (when (:zone/id m)
    {:id (:zone/id m) :name (:zone/name m) :ward (:zone/ward m)
     :supervisor (:zone/supervisor m)
     :collection-capacity (:zone/collection-capacity m)
     :observed-backlog (:zone/observed-backlog m)
     :collector (or (dec* (:zone/collector m)) {})}))

(def ^:private zone-pull
  [:zone/id :zone/name :zone/ward :zone/supervisor :zone/collection-capacity
   :zone/observed-backlog :zone/collector])

(defn- vendor->tx [{:keys [id name licensed? materials]}]
  (cond-> {:vendor/id id}
    name       (assoc :vendor/name name)
    (some? licensed?) (assoc :vendor/licensed? licensed?)
    materials  (assoc :vendor/materials (enc materials))))

(defn- pull->vendor [m]
  (when (:vendor/id m)
    {:id (:vendor/id m) :name (:vendor/name m)
     :licensed? (boolean (:vendor/licensed? m))
     :materials (or (dec* (:vendor/materials m)) #{})}))

(def ^:private vendor-pull [:vendor/id :vendor/name :vendor/licensed? :vendor/materials])

(defrecord DatomicStore [conn]
  Store
  (zone [_ id] (pull->zone (d/pull (d/db conn) zone-pull [:zone/id id])))
  (all-zones [_]
    (->> (d/q '[:find [?id ...] :where [?e :zone/id ?id]] (d/db conn))
         (map #(pull->zone (d/pull (d/db conn) zone-pull [:zone/id %])))
         (sort-by :id)))
  (supervised-zones [_ supervisor-id]
    (->> (d/q '[:find [?id ...] :in $ ?sup
                :where [?e :zone/supervisor ?sup] [?e :zone/id ?id]]
              (d/db conn) supervisor-id)
         (map #(pull->zone (d/pull (d/db conn) zone-pull [:zone/id %])))
         (sort-by :id)))
  (vendor [_ id] (pull->vendor (d/pull (d/db conn) vendor-pull [:vendor/id id])))
  (licensed-vendor? [_ id]
    (boolean (:licensed? (pull->vendor (d/pull (d/db conn) vendor-pull [:vendor/id id])))))
  (shipment-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?zid
                 :where [?e :zone/id ?zid] [?s :shipment/zone ?e] [?s :shipment/payload ?p]]
               (d/db conn) id)))
  (insight-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?zid
                 :where [?e :zone/id ?zid] [?v :insight/zone ?e] [?v :insight/payload ?p]]
               (d/db conn) id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :upsert-zone   (d/transact! conn [(zone->tx value)])
      :set-shipment  (d/transact! conn [{:shipment/zone [:zone/id (first path)]
                                         :shipment/payload (enc payload)}])
      :store-insight (d/transact! conn [{:insight/zone [:zone/id (first path)]
                                         :insight/payload (enc payload)}])
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-zones [s zs]
    (when (seq zs) (d/transact! conn (mapv zone->tx (vals zs)))) s)
  (with-vendors [s vs]
    (when (seq vs) (d/transact! conn (mapv vendor->tx (vals vs)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:zones .. :vendors ..}); empty when omitted. PERSIST is the optional
  sealed transaction append/read port; all Datalog query execution remains
  in this process."
  ([] (datomic-store {} nil))
  ([data] (datomic-store data nil))
  ([{:keys [zones vendors]} persist]
   (let [s (->DatomicStore (d/create-conn schema persist))]
     (-> s (with-zones zones) (with-vendors vendors)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo zones/vendors — the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))

;; ───────────────────────── ledger formatting ─────────────────────────

(defn ledger-line
  "Human-readable one-liner for a ledger fact (used by the demo)."
  [{:keys [op actor subject disposition basis]}]
  (str/join " · "
            [(name disposition)
             (str "op=" op)
             (str "actor=" actor)
             (str "zone=" subject)
             (str "basis=" (pr-str basis))]))
