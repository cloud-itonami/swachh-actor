(ns swachh.phase-test
  "Phase 0→3 staged rollout through the OperationActor. The phase can only
  make the actor MORE conservative than policy: hold writes that aren't
  enabled yet, force human approval before auto-commit is unlocked."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [swachh.store :as store]
            [swachh.operation :as op]))

(def wo {:actor-id "wo-900" :actor-role :ward-officer :purpose :ops :consent? true})
(def dispatch {:op :collection/dispatch :subject "z-001"
               :patch {:id "z-001" :collection-capacity 99.0}})

(defn- run [phase req ctx]
  (let [s (store/seed-db)
        actor (op/build s)]
    [s (g/run* actor {:request req :context (assoc ctx :phase phase)}
               {:thread-id (str "ph-" phase "-" (:op req))})]))

(deftest phase0-holds-all-writes
  (let [[s res] (run 0 dispatch wo)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) first :phase-reason)))
    (is (= 20.0 (:collection-capacity (store/zone s "z-001"))) "SSoT untouched in phase 0")))

(deftest phase0-allows-governed-reads
  (testing "report/export is a read → phase 0 lets it through (policy still applies)"
    (let [[_ res] (run 0 {:op :report/export :subject "*"}
                       (assoc wo :purpose :public-dashboard))]
      (is (= :commit (get-in res [:state :disposition]))))))

(deftest phase1-forces-approval-on-clean-write
  (testing "a clean dispatch that auto-commits in phase 3 must go to a human in phase 1"
    (let [[_ res] (run 1 dispatch wo)]
      (is (= :interrupted (:status res)))
      (is (= :phase-approval (-> res :state :audit last :reason))))))

(deftest phase2-enables-shipment-writes-under-approval
  (let [[_ res] (run 2 {:op :shipment/propose :subject "z-001"} wo)]
    ;; z-001 shipment proposal is policy-clean (licensed vendor), but phase 2
    ;; still requires approval.
    (is (= :interrupted (:status res)))
    (is (= :phase-approval (-> res :state :audit last :reason)))))

(deftest phase3-auto-commits-clean-write
  (let [[s res] (run 3 dispatch wo)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 99.0 (:collection-capacity (store/zone s "z-001"))))))

(deftest policy-hold-beats-phase
  (testing "a hard policy violation holds even in the most permissive phase"
    (let [[_ res] (run 3 {:op :shipment/propose :subject "z-001" :bias? true} wo)]
      (is (= :hold (get-in res [:state :disposition]))))))
