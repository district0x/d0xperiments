(ns d0xperiments.preindexer
  (:require [datascript.core :as d]
            [bignumber.core :as bn]
            [cljs.nodejs :as nodejs]
            [d0xperiments.core :refer [install-facts-filter! get-past-events]]
            [clojure.core.async :as async]
            [posh.lib.pull-analyze :as posh-pull]
            [posh.lib.q-analyze :as posh-q]
            [clojure.tools.cli :refer [parse-opts]]
            [d0xperiments.utils :refer [compress-facts]]
            [clojure.pprint :as pprint]
            [d0xperiments.web3.impl.ethers-js :refer [make-ethers-js]]
            [d0xperiments.web3.core :as web3-core])
  (:require-macros [d0xperiments.utils :refer [<?]]))

(nodejs/enable-util-print!)

(defonce ethers (nodejs/require "ethers"))

(defonce http (nodejs/require "http"))
(defonce zlib (nodejs/require "zlib"))
(defonce Buffer (.-Buffer (nodejs/require "buffer")))
(defonce fs (nodejs/require "fs"))

(defonce conn (atom nil))

(defonce last-seen-block (atom 0))
(defonce attribute-stats (atom {}))

(defn load-schema [file-path]
  (or
   (-> (fs.readFileSync file-path)
       .toString
       cljs.reader/read-string)
   {}))

(defn transact-fact [conn {:keys [entity attribute value block-num] :as fact}]
  ;; (.log js/console (str "[" :db/add " " entity " " attribute " " value "]"))
  (swap! last-seen-block (partial (fnil max 0) block-num))
  (swap! attribute-stats (fn [s] (update s attribute inc)))
  (d/transact! conn [[:db/add
                      entity
                      attribute
                      value
                      block-num]]))


;; TODO add tools.cli


(def datoms-for
  (fn [db pulls-and-qs]
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
            pulls-and-qs)))


(defn process-req [conn req res]
  (let [headers {"Access-Control-Allow-Origin" "*"
                 "Access-Control-Request-Method" "*"
                 "Access-Control-Allow-Methods" "OPTIONS, GET, POST"
                 "Access-Control-Allow-Headers" "*"}]
    (cond
      (and (= (.-url req) "/db")
           (= (.-method req) "GET"))
      (let [res-map {:db-facts (->> (d/datoms @conn :eavt)
                                    (mapv (fn [[e a v _ x]] [e a v x])))
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

(def cli-options
  [["-a" "--address ADDRESS" "FactsDB contract address"]
   ["-p" "--port PORT" "HTTP server listening port"
    :default 1234
    :parse-fn #(js/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-s" "--schema FILE" "Datascript schema file"
    :default nil]
   ["-r" "--rpc HOST" "Ethereum RPC endpoint"
    :default "localhost:8545"]])

(defn -main [& args]
  (let [{:keys [options errors] :as result} (parse-opts args cli-options)]
    (when errors
      (doseq [e errors]
        (println e))
      (println (:summary result))
      (.exit js/process 1))

    (when (or (:help options)
              (not (:address options)))
      (println "Usage :  preindexer [options]")
      (println "Options: ")
      (println (:summary result))
      (.exit js/process 1))

    (println "Connecting to " (str "http://" (:rpc options)))
    (let [schema (load-schema (:schema options))
          conn-obj (d/create-conn schema)

          ethers-provider (new (-> ethers .-providers .-JsonRpcProvider)  (str "http://" (:rpc options)))
          web3 (make-ethers-js ethers-provider)]
      (set! js/ethers ethers)
      (async/go
        (println "Downloading past events, please wait...")
        (let [past-events (<? (get-past-events web3 (:address options) 0))
              new-facts-ch (install-facts-filter! web3 (:address options))]
          (println "Past events downloaded, replaying " (count past-events) " facts...")
          ;; transact past facts
          (doseq [f past-events]
            (transact-fact conn-obj f))

          (println "Old events replayed. Attribute stats : ")
          (pprint/pprint @attribute-stats)
          (println "Ready. Watching for more events...")
          ;; keep forever transacting new facts
          (loop [nf (<? new-facts-ch)]
            (transact-fact conn-obj nf)
            (recur (<? new-facts-ch)))))

      (doto (.createServer http (partial process-req conn-obj))
        (.listen (:port options))))))


(set! *main-cli-fn* -main)

(comment

  )
