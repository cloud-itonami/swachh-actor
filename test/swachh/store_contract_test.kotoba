(ns swachh.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and the
  Datomic-backed (langchain.db) store satisfy the same contract is what
  makes 'swap the SSoT for Datomic' a configuration change, not a rewrite."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [langchain.edn-persist :as edn-persist]
            [swachh.store :as store])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Andheri East, Zone 3" (:name (store/zone s "z-001"))))
      (is (= "sup-100" (:supervisor (store/zone s "z-001"))))
      (is (= ["z-001" "z-002"] (mapv :id (store/supervised-zones s "sup-100"))))
      (is (= 15.0 (:observed-backlog (store/zone s "z-001"))))
      (is (= 60.0 (:observed-backlog (store/zone s "z-002"))))
      (is (= {:id "w-01" :name "Ramesh K." :aadhaar "xxxx-xxxx-1234"
              :home-address "Andheri East, chawl 4"}
             (:collector (store/zone s "z-001")))
          "collector map round-trips (stored as EDN on Datomic, not a sub-entity)")
      (is (true? (store/licensed-vendor? s "v-10")))
      (is (false? (store/licensed-vendor? s "v-99")))
      (is (= ["z-001" "z-002"] (mapv :id (store/all-zones s)))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :upsert-zone
                                 :value {:id "z-001" :collection-capacity 25.0}})
        (is (= 25.0 (:collection-capacity (store/zone s "z-001"))))
        (is (= "Andheri East, Zone 3" (:name (store/zone s "z-001"))) "name preserved")
        (is (= "Andheri" (:ward (store/zone s "z-001"))) "ward preserved"))
      (testing "shipment / insight payloads commit and read back"
        (store/commit-record! s {:effect :set-shipment :path ["z-001"]
                                 :payload {:summary "5単位出荷" :by "wo-900"}})
        (is (= {:summary "5単位出荷" :by "wo-900"} (store/shipment-of s "z-001")))
        (store/commit-record! s {:effect :store-insight :path ["z-002"]
                                 :payload {:hygiene-risk :high}})
        (is (= {:hygiene-risk :high} (store/insight-of s "z-002"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/zone s "nope")))
    (is (= [] (store/all-zones s)))
    (is (= [] (store/ledger s)))
    (store/with-zones s {"x" {:id "x" :name "X" :ward "W" :collection-capacity 1.0}})
    (is (= "X" (:name (store/zone s "x"))))))

(deftest repository-backed-store-restores-after-restart
  (let [dir (.toFile (Files/createTempDirectory
                      "swachh-repository-" (make-array FileAttribute 0)))
        file (io/file dir "state.edn")
        environment {"KOTOBA_REPOSITORY_STATE_FILE" (.getPath file)}
        open-store #(store/datomic-store
                     {}
                     (edn-persist/configured-persist environment
                                                     "actor/swachh"))
        first-process (open-store)]
    (store/with-zones first-process {"z" {:id "z" :name "Persistent"}})
    (store/append-ledger! first-process {:op :dispatch :disposition :commit})
    (let [second-process (open-store)]
      (is (= "Persistent" (:name (store/zone second-process "z"))))
      (is (= [:commit] (mapv :disposition (store/ledger second-process)))))))
