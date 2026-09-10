(ns swachh.wasteops-test
  "The real-inference advisor (langchain.model ChatModel), driven offline by
  langchain's mock-model. Proves: a real LLM proposal is parsed, still fully
  censored by the SanitationGovernor, and that an unparseable/garbage
  response can never auto-commit."
  (:require [clojure.test :refer [deftest is testing]]
            [langchain.model :as model]
            [swachh.wasteops :as wasteops]
            [swachh.governor :as governor]
            [swachh.store :as store]))

(def wo  {:actor-id "wo-900" :actor-role :ward-officer :purpose :ops :consent? true})
(def req {:op :shipment/propose :subject "z-001"})

(defn- advise-with [content]
  (wasteops/-advise (wasteops/llm-advisor (model/mock-model [{:role :assistant :content content}]))
                     (store/seed-db) req))

(deftest clean-llm-proposal-is-parsed-and-accepted
  (let [p (advise-with (str "{:summary \"5単位出荷\" :rationale \"vendor受入資材適合\" "
                            ":cites [:collection-capacity] :effect :set-shipment :stake nil :confidence 0.85}"))]
    (is (= :set-shipment (:effect p)))
    (is (= [:collection-capacity] (:cites p)))
    (is (= 0.85 (:confidence p)))
    (testing "the governor accepts the clean LLM proposal"
      (is (:ok? (governor/check req wo p (store/seed-db)))))))

(deftest llm-citing-worker-privacy-is-rejected
  (testing "even a confident LLM can't cite the collector's identity as basis — worker-privacy gate holds"
    (let [p (advise-with (str "{:summary \"出荷提案\" :rationale \"担当収集人の実績に基づく\" "
                              ":cites [:collection-capacity :collector] :effect :set-shipment :confidence 0.9}"))
          v (governor/check req wo p (store/seed-db))]
      (is (:hard? v))
      (is (some #{:worker-privacy} (map :rule (:violations v)))))))

(deftest unparseable-llm-output-never-auto-commits
  (testing "garbage / refusal → safe noop at confidence 0 → governor won't pass it"
    (let [p (advise-with "すみません、その操作には対応できません。")]
      (is (= :noop (:effect p)))
      (is (= 0.0 (:confidence p)))
      (is (not (:ok? (governor/check req wo p (store/seed-db))))))))
