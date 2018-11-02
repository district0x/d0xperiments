(ns d0xperiments.preindexer
  (:require [datascript.core :as d]
            [bignumber.core :as bn]
            [cljs.nodejs :as nodejs]
            [d0xperiments.core :refer [install-facts-filter! get-past-events]]
            [clojure.core.async :as async])
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

(defn -main [facts-db-address port schema-file default-provider-url]
  (let [schema (load-schema schema-file)
        conn-obj (d/create-conn schema)
        ws-provider (new (-> w3 .-providers .-WebsocketProvider) (str "ws://" default-provider-url))
        http-provider (new (-> w3 .-providers .-HttpProvider) (str "http://" default-provider-url))
        web3-http  (new w3 http-provider)
        web3-ws (new w3 ws-provider)]

    ;; (reset! web3 web3-obj) ;; for repl
    ;; (reset! conn conn-obj)

    (async/go
      (let [past-events (<? (get-past-events web3-http facts-db-address 0))
            new-facts-ch (install-facts-filter! web3-ws facts-db-address)]
        ;; transact past facts
        (doseq [f past-events]
          (transact-fact conn-obj f))

        ;; keep forever transacting new facts
        (loop [nf (<? new-facts-ch)]
          (d/transact! conn-obj nf)
          (recur (<? new-facts-ch)))))

    (doto (.createServer http
                         (fn [req res]
                           (let [res-map {:db @conn-obj
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

  (-main "0x360b6d00457775267aa3e3ef695583c675318c05"
         1234
         "/home/jmonetta/my-projects/district0x/d0xperiments/example-src/d0xperiments/example/db_schema.edn"
         "ws://localhost:8549/")

  (.listen http-server 1234)

  (create-filters "0xbb123fed696a108f1586c21e67b2ef75f210b329")

  (d/pull-many @conn '[*]
               (map first
                    (d/q '[:find ?e :where [?e :person/name]] @conn)))

  )
