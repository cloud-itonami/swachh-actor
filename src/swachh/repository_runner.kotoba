(ns swachh.repository-runner
  "Repository-backed operational entrypoint. The deterministic advisor is
  still governed by the same graph; only the state host differs from the
  in-memory demo. KOTOBA_REPOSITORY_STATE_FILE is required."
  (:require [langchain.edn-persist :as edn-persist]
            [langgraph.graph :as graph]
            [swachh.operation :as operation]
            [swachh.store :as store])
  (:gen-class))

(defn -main [& [zone-id capacity]]
  (let [zone-id (or zone-id "z-001")
        capacity (or (some-> capacity parse-double) 25.0)
        state (store/datomic-store
               (store/demo-data)
               (edn-persist/required-persist-from-env "actor/swachh"))
        actor (operation/build state)
        result (graph/run*
                actor
                {:request {:op :collection/dispatch
                           :subject zone-id
                           :patch {:id zone-id
                                   :collection-capacity capacity}}
                 :context {:actor-id "swachh-operator"
                           :actor-role :ward-officer
                           :purpose :ops
                           :consent? true
                           :phase 3}}
                {:thread-id (str "repository-dispatch/" zone-id)})]
    (prn {:status (:status result)
          :disposition (get-in result [:state :disposition])
          :zone (store/zone state zone-id)
          :ledger/count (count (store/ledger state))})))
