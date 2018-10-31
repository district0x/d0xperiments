(ns d0xperiments.preindexer
  (:require [datascript.core :as d]
            [bignumber.core :as bn]
            [cljs.nodejs :as nodejs]
            [d0xperiments.core :refer [make-facts-syncer]]
            [cljs-web3.core :as web3]))

(nodejs/enable-util-print!)

(defonce w3 (nodejs/require "web3"))
(defonce http (nodejs/require "http"))
(defonce zlib (nodejs/require "zlib"))
(defonce Buffer (.-Buffer (nodejs/require "buffer")))
(defonce fs (nodejs/require "fs"))

(defonce web3 (web3/create-web3 w3 "http://localhost:8549/"))
#_(defonce conn (d/create-conn (load-schema "/home/jmonetta/my-projects/district0x/d0xperiments/example-src/d0xperiments/example/db_schema.edn")))
(defonce last-seen-block (atom 0))

(defn load-schema [file-path]
  (or
   (-> (fs.readFileSync file-path)
       .toString
       cljs.reader/read-string)
   {}))

(defn create-filters [facts-db-address conn]
  (make-facts-syncer web3 facts-db-address
                     (fn [[e a v t _ :as datom] {:keys [block-num]}]
                       (.log js/console (str "[" :db/add " "(bn/number e) " " (keyword a) " " v "]"))
                       (swap! last-seen-block (partial (fnil max 0) block-num))
                       (d/transact! conn [[:db/add
                                           (bn/number e)
                                           (keyword a)
                                           (if (bn/bignumber? v)
                                             (bn/number v)
                                             v)]]))))



(defn -main [& [facts-db-address port schema-file]]
  (let [schema (load-schema schema-file)
        conn (d/create-conn schema)]

    (create-filters facts-db-address conn)

    (doto (.createServer http
                         (fn [req res]
                           (let [res-map {:db @conn
                                          :last-seen-block @last-seen-block}
                                 res-content (zlib.gzipSync (Buffer.from (prn-str res-map)))]
                             (.log js/console "Content got gziped to " (.-length res-content))
                             (.writeHead res 200 #js {"Content-Type" "application/edn"
                                                      "Content-Encoding" "gzip"
                                                      "Access-Control-Allow-Origin" "*"
                                                      "Access-Control-Request-Method" "*"
                                                      "Access-Control-Allow-Methods" "OPTIONS, GET"
                                                      "Access-Control-Allow-Headers" "*"})
                             (.write res res-content)
                             (.end res))))
      (.listen port))))


(set! *main-cli-fn* -main)

(comment

  (.listen http-server 1234)

  (create-filters "0xbb123fed696a108f1586c21e67b2ef75f210b329")

  (d/pull-many @conn '[*]
               (map first
                    (d/q '[:find ?e :where [?e :person/name]] @conn)))

  )
