(ns puppetlabs.puppetdb.export
  (:require [clj-time.core :refer [now]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.scf.hash :as hash]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.anonymizer :as anon]
            [puppetlabs.puppetdb.archive :as archive]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.catalogs :as catalogs]
            [puppetlabs.puppetdb.factsets :as factsets]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.command :as command]
            [puppetlabs.puppetdb.command.constants :as command-constants]
            [puppetlabs.puppetdb.utils :as utils]
            [clj-time.format :as time-fmt]
            [clj-time.coerce :as time-coerce]
            [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]
            [puppetlabs.i18n.core :refer [trs]]))

(def export-metadata-file-name "export-metadata.json")
(def query-api-version :v4)
(def latest-command-versions command-constants/latest-command-versions)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utility Functions

(def export-file-ext ".json")

(defn export-filename
  "For anything we store historically and need unique names"
  [{:keys [certname producer_timestamp]}]
  (format "%s-%s%s"
          certname
          (-> producer_timestamp
              time-coerce/to-date-time
              hash/generic-identity-hash)
          export-file-ext))

(pls/defn-validated export-datum->tar-item :- utils/tar-item
  "Creates a tar-item map for a PuppetDB entity"
  [entity datum]
  (let [command-file-name
        (case entity
          "factsets" ["facts" (str (:certname datum) export-file-ext)]
          "catalogs" ["catalogs" (export-filename datum)]
          "reports" ["reports" (export-filename datum)])]
    {:file-suffix command-file-name
     :contents (json/generate-pretty-string datum)}))

(defn export-data->tar-items
  [entity data]
  (map #(export-datum->tar-item entity %) data))

(def export-info
  {"catalogs" {:query->wire-fn catalogs/catalogs-query->wire-v9
              :anonymize-fn anon/anonymize-catalog
              :json-encoded-fields [:edges :resources]}
   "reports" {:query->wire-fn reports/reports-query->wire-v8
             :anonymize-fn anon/anonymize-report
             :json-encoded-fields [:metrics :logs :resource_events :resources]}
   "factsets" {:query->wire-fn factsets/factsets-query->wire-v5
              :anonymize-fn anon/anonymize-facts
              :json-encoded-fields [:facts]}})

(defn maybe-anonymize [anonymize-fn anon-config data]
  (if (not= anon-config ::not-found)
    (map (comp clojure.walk/keywordize-keys
               #(anonymize-fn anon-config %)
               clojure.walk/stringify-keys) data)
    data))

(defn add-tar-entries [tar-writer entries]
  (doseq [entry entries]
    (utils/add-tar-entry tar-writer entry)))

(defn decode-json-children [row json-encoded-fields]
  (kitchensink/mapvals sutils/parse-db-json json-encoded-fields row))

(defn export!*
  [tar-writer query-fn anonymize-profile]
  (let [anon-config (get anon/anon-profiles anonymize-profile ::not-found)]
    (doseq [[entity {:keys [json-encoded-fields query->wire-fn anonymize-fn]}] export-info
            :let [query-callback-fn (fn [rows]
                                      (->> rows
                                           (map #(decode-json-children % json-encoded-fields))
                                           query->wire-fn
                                           (maybe-anonymize anonymize-fn anon-config)
                                           (export-data->tar-items entity)
                                           (add-tar-entries tar-writer)))]]
      (query-fn query-api-version ["from" entity] nil query-callback-fn))))

(defn export!
  ([outfile query-fn] (export! outfile query-fn nil))
  ([outfile query-fn anonymize-profile]
   (log/info (trs "Export triggered for PuppetDB"))
   (with-open [tar-writer (archive/tarball-writer outfile)]
     (utils/add-tar-entry
      tar-writer {:file-suffix [export-metadata-file-name]
                  :contents (json/generate-pretty-string {:timestamp (now) :command_versions latest-command-versions})})
     (export!* tar-writer query-fn anonymize-profile))
   (log/info (trs "Finished exporting PuppetDB"))))
