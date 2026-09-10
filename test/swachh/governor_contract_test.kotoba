(ns swachh.governor-contract-test
  "The governor contract as executable tests — the sanitation analog of
  robotaxi's safety_contract_test / talent's policy_contract_test. The
  single invariant under test:

    waste-ops-LLM never writes/discloses a record the SanitationGovernor
    would reject, and every decision (commit OR hold) leaves exactly one
    ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [swachh.store :as store]
            [swachh.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def wo {:actor-id "wo-900" :actor-role :ward-officer :purpose :ops :consent? true})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(deftest authorized-dispatch-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :collection/dispatch :subject "z-001"
                   :patch {:id "z-001" :collection-capacity 25.0}} wo)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 25.0 (:collection-capacity (store/zone db "z-001"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))
    (is (= :commit (-> (store/ledger db) first :disposition)))))

(deftest unauthorized-role-is-held
  (testing "a :field-worker role has no dispatch permission → HOLD, no write"
    (let [[db actor] (fresh)
          before (store/zone db "z-001")
          res (exec-op actor "t2"
                    {:op :collection/dispatch :subject "z-001"
                     :patch {:id "z-001" :collection-capacity 99.0}}
                    {:actor-id "fw-001" :actor-role :field-worker
                     :purpose :ops :consent? true})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (= before (store/zone db "z-001")) "SSoT unchanged")
      (is (= [:rbac] (-> (store/ledger db) first :basis))))))

(deftest supervisor-cannot-act-outside-their-zones
  (testing "a collection-supervisor may only act on zones they supervise"
    (let [[_ actor] (fresh)
          ;; z-001 is supervised by sup-100, not sup-200.
          res (exec-op actor "t3"
                    {:op :shipment/propose :subject "z-001"}
                    {:actor-id "sup-200" :actor-role :collection-supervisor
                     :purpose :ops :consent? true})]
      (is (= :hold (get-in res [:state :disposition]))))))

(deftest worker-privacy-violation-is-held
  (testing "a shipment proposal citing the collector's identity as basis → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t4"
                    {:op :shipment/propose :subject "z-001" :bias? true} wo)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:worker-privacy} (-> (store/ledger db) first :basis))
          "worker-privacy is the basis for the hold")
      (is (nil? (store/shipment-of db "z-001")) "no shipment written"))))

(deftest vendor-eligibility-violation-is-held
  (testing "a shipment proposal recommending an unlicensed vendor → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :shipment/propose :subject "z-001" :unlicensed? true} wo)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:vendor-eligibility} (-> (store/ledger db) first :basis)))
      (is (nil? (store/shipment-of db "z-001")) "no shipment written"))))

(deftest over-disclosure-is-held
  (testing "a report pulling worker PII columns beyond the purpose → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :report/export :subject "*" :greedy? true}
                    (assoc wo :purpose :public-dashboard))]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:minimal-disclosure} (-> (store/ledger db) first :basis))))))

(deftest high-stakes-low-confidence-escalates-then-human-decides
  (testing "a high backlog/capacity ratio route proposal interrupts for human approval"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t7" {:op :route/propose :subject "z-002"} wo)]
      (is (= :interrupted (:status r1)) "pauses for human approval")
      (testing "approve → commit"
        (let [r2 (g/run* actor {:approval {:status :approved :by "wo-900"}}
                         {:thread-id "t7" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :commit (-> (store/ledger db) last :disposition)))))))
  (testing "reject → hold"
    (let [[db actor] (fresh)
          _  (exec-op actor "t8" {:op :route/propose :subject "z-002"} wo)
          r2 (g/run* actor {:approval {:status :rejected :by "wo-900"}}
                     {:thread-id "t8" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (nil? (:route-priority (store/zone db "z-002"))) "no route-priority written on reject"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations → N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :collection/dispatch :subject "z-001"
                       :patch {:id "z-001" :collection-capacity 12.0}} wo)
      (exec-op actor "b" {:op :shipment/propose :subject "z-001" :bias? true} wo)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
