(ns swachh.report
  "ReportActor — 帳票/CSV output as a GOVERNED read. The column set is not
  chosen here; it is whatever the SanitationGovernor's minimal-disclosure
  gate approved for the declared purpose (see :report/export). This
  namespace only renders the approved columns, so a report can never
  disclose more than policy allows — worker PII (`:collector-*`) only
  appears if a future, narrowly-scoped purpose explicitly allows it."
  (:require [kotoba.lang.text :as str]
            [swachh.store :as store]))

(defn- collector-cell [z c]
  (case c
    :collector-name          (get-in z [:collector :name])
    :collector-aadhaar       (get-in z [:collector :aadhaar])
    :collector-home-address  (get-in z [:collector :home-address])
    nil))

(defn render-csv
  "Render zones as CSV over exactly `columns` (already policy-approved)."
  [db columns]
  (let [zones (store/all-zones db)
        cell (fn [z c] (str (or (get z c) (collector-cell z c) "")))
        head (str/join "," (map name columns))
        rows (map (fn [z] (str/join "," (map #(cell z %) columns))) zones)]
    (str/join "\n" (cons head rows))))

(defn zone-status-text
  "Plain-text ward status line — the collection/sanitation-dashboard view."
  [db zone-id]
  (let [z (store/zone db zone-id)]
    (str "└ " (:name z) " (" (:ward z) ") — 未収集 " (:observed-backlog z)
         " / 収集能力 " (:collection-capacity z))))
