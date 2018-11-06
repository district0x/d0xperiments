(ns d0xperiments.preindexer
  (:require [datascript.core :as d]
            [bignumber.core :as bn]
            [cljs.nodejs :as nodejs]
            [d0xperiments.core :refer [install-facts-filter! get-past-events]]
            [clojure.core.async :as async]
            [posh.lib.pull-analyze :as posh-pull]
            [posh.lib.q-analyze :as posh-q])
  (:require-macros [d0xperiments.utils :refer [<?]]))

(nodejs/enable-util-print!)

(defonce w3 (nodejs/require "web3"))
(defonce http (nodejs/require "http"))
(defonce zlib (nodejs/require "zlib"))
(defonce Buffer (.-Buffer (nodejs/require "buffer")))
(defonce fs (nodejs/require "fs"))

(defonce web3 (atom nil))
(defonce conn (atom nil))

(defonce last-seen-block (atom 0))

(defn load-schema [file-path]
  (or
   (-> (fs.readFileSync file-path)
       .toString
       cljs.reader/read-string)
   {}))

(defn transact-fact [conn {:keys [entity attribute value block-num] :as fact}]
  (.log js/console (str "[" :db/add " " entity " " attribute " " value "]"))
  (swap! last-seen-block (partial (fnil max 0) block-num))
  (d/transact! conn [[:db/add
                      entity
                      attribute
                      value
                      block-num]]))


;; TODO add tools.cli


(def datoms-for
  (memoize
   (fn[db pulls-and-qs]
     (reduce (fn [datoms-set {:keys [type] :as x}]
               (into datoms-set (case type
                                  :query (-> (posh-q/q-analyze {:q d/q} [:datoms]
                                                               (:query x)
                                                               (into [db] (:vars x)))
                                             :datoms first second)
                                  :pull (->> (posh-pull/pull-affected-datoms d/pull db (:pattern x) (first (:ids x)))
                                             (posh-pull/generate-affected-tx-datoms-for-pull (:schema db)))
                                  nil)))
             #{}
             pulls-and-qs))))

(defn process-req [conn req res]
  (let [headers {"Access-Control-Allow-Origin" "*"
                 "Access-Control-Request-Method" "*"
                 "Access-Control-Allow-Methods" "OPTIONS, GET, POST"
                 "Access-Control-Allow-Headers" "*"}]
    (cond
      (and (= (.-url req) "/db")
           (= (.-method req) "GET"))
      (let [res-map {:db @conn
                     :last-seen-block @last-seen-block}
            res-content (zlib.gzipSync (Buffer.from (prn-str res-map)))]
        (.log js/console "Content got gziped to " (.-length res-content))
        (.writeHead res 200 (clj->js (merge headers
                                            {"Content-Type" "application/edn"
                                             "Content-Encoding" "gzip"})))
        (.write res res-content)
        (.end res))


      (and
       (= (.-url req) "/datoms")
       (= (.-method req) "POST"))
      (let [req-content (atom "")]
        (.on req "data" (fn [chunk] (swap! req-content str chunk)))
        (.on req "end" (fn []
                         (.log js/console "Asked to resolve datoms for" @req-content)
                         (let [datoms-set (->> @req-content
                                               cljs.reader/read-string
                                               :datoms-for
                                               (datoms-for @conn))
                               res-content (-> {:datoms datoms-set}
                                               pr-str)]
                           (.writeHead res 200 (clj->js (merge headers
                                                               {"Content-Type" "application/edn"})))
                           (.write res res-content)
                           (.end res)))))

      (= (.-method req) "OPTIONS")
      (do (.writeHead res 200 (clj->js headers))
          (.end res))

      :else
      (do (.writeHead res 404 (clj->js headers))
          (.end res)))))

(defn -main [facts-db-address port schema-file default-provider-url]
  (let [schema (load-schema schema-file)
        conn-obj (d/create-conn schema)
        ws-provider (new (-> w3 .-providers .-WebsocketProvider) (str "ws://" default-provider-url))
        http-provider (new (-> w3 .-providers .-HttpProvider) (str "http://" default-provider-url))
        web3-http  (new w3 http-provider)
        web3-ws (new w3 ws-provider)]

    (async/go
      (println "Downloading past events, please wait...")
      (let [past-events (<? (get-past-events web3-http facts-db-address 0))
            new-facts-ch (install-facts-filter! web3-ws facts-db-address)]
        (println "Past events downloaded, replaying...")
        ;; transact past facts
        (doseq [f past-events]
          (transact-fact conn-obj f))

        (println "Old events replayed. Watching for more events...")
        ;; keep forever transacting new facts
        (loop [nf (<? new-facts-ch)]
          (transact-fact conn-obj nf)
          (recur (<? new-facts-ch)))))

    (doto (.createServer http (partial process-req conn-obj))
      (.listen port))))


(set! *main-cli-fn* -main)

(comment

  (-main "0x360b6d00457775267aa3e3ef695583c675318c05"
         1234
         "/home/jmonetta/my-projects/district0x/d0xperiments/example-src/d0xperiments/example/db_schema.edn"
         "ws://localhost:8549/")

  (.listen http-server 1234)

  (create-filters "0xbb123fed696a108f1586c21e67b2ef75f210b329")

  (d/pull-many @conn '[*]
               (map first
                    (d/q '[:find ?e :where [?e :person/name]] @conn)))


  (do
    (defonce c (d/create-conn))
    (d/transact! c [[:db/add 1 :person/name "Rich"]
                    [:db/add 1 :person/age 45]
                    [:db/add 2 :person/name "Alex"]
                    [:db/add 2 :person/age 44]
                    [:db/add 4 :person/name "Other"]
                    [:db/add 4 :person/age 100]]))
  (= (datoms-for @c
                 [{:type :query
                   :query '[:find ?eid ?n
                            :in $ ?age
                            :where
                            [?eid :person/name ?n]
                            [?eid :person/age ?age]]
                   :vars [45]}
                  {:type :pull
                   :pattern '[*]
                   :ids [4]}
                  {:type :pull
                   :pattern '[:db/id :person/name]
                   :ids [2]}])
     #{[1 :person/name "Rich"]
       [1 :person/age 45]
       [2 :person/name "Alex"]
       [4 :person/age 100]
       [4 :person/name "Other"]})



  )
