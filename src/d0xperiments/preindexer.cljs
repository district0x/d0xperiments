(ns d0xperiments.preindexer
  (:require [datascript.core :as d]
            [bignumber.core :as bn]
            [cljs.nodejs :as nodejs]
            [d0xperiments.core :refer [make-facts-syncer]]
            [cljs-web3.core :as web3]))

(nodejs/enable-util-print!)

(defonce w3 (nodejs/require "web3"))
(defonce http (nodejs/require "http"))
(defonce web3 (web3/create-web3 w3 "http://localhost:8549/"))
(defonce conn (d/create-conn {}))

(defn create-filters [facts-db-address]
  (make-facts-syncer web3 facts-db-address
                     (fn [[e a v t _ :as datom]]
                       (.log js/console (str "[" :db/add " "(bn/number e) " " (keyword a) " " v "]"))
                       (d/transact! conn [[:db/add
                                           (bn/number e)
                                           (keyword a)
                                           (if (bn/bignumber? v)
                                             (bn/number v)
                                             v)]]))))



(defn -main [& [facts-db-address port]]

  (create-filters facts-db-address)

  (doto (.createServer http
                       (fn [req res]
                         (.writeHead res 200 #js {"Content-Type" "application/edn"
                                                  "Access-Control-Allow-Origin" "*"
                                                  "Access-Control-Request-Method" "*"
                                                  "Access-Control-Allow-Methods" "OPTIONS, GET"
                                                  "Access-Control-Allow-Headers" "*"})
                         (.write res (prn-str @conn))
                         (.end res)))
    (.listen port)))


(set! *main-cli-fn* -main)

(comment

  (.listen http-server 1234)

  (create-filters "0xbb123fed696a108f1586c21e67b2ef75f210b329")

  (d/pull-many @conn '[*]
               (map first
                    (d/q '[:find ?e :where [?e :person/name]] @conn)))

  )
