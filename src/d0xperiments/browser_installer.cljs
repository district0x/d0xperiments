(ns d0xperiments.browser-installer
  (:require [d0xperiments.core :refer [install-facts-filter! get-block-number get-past-events]]
            [datascript.core :as d]
            [clojure.core.async :as async]
            [ajax.core :refer [ajax-request] :as ajax]
            [ajax.edn :as ajax-edn]
            [d0xperiments.indexeddb :as idb])
  (:require-macros [d0xperiments.utils :refer [<?]]))



(defn wait-for-load []
  (let [out-ch (async/chan)]
    (.addEventListener js/window "load" #(async/put! out-ch true))
    out-ch))

(defn fact->ds-fact [{:keys [entity attribute value]}]
  [:db/add entity attribute value])

(defn transact-facts-batch [finish-ch ds-conn transact-batch-size progress-cb facts-to-transact total-facts so-far]
  (if (empty? facts-to-transact)
    (do
      (progress-cb {:state :installing-facts :percentage 100})
      (async/put! finish-ch true))

    (do
      (d/transact! ds-conn (take transact-batch-size facts-to-transact))
      (progress-cb {:state :installing-facts :percentage (quot (* 100 so-far) total-facts)})
      (js/setTimeout #(transact-facts-batch finish-ch
                                            ds-conn
                                            transact-batch-size
                                            progress-cb
                                            (drop transact-batch-size facts-to-transact)
                                            total-facts
                                            (+ so-far transact-batch-size))
                     0))))

(defn pre-fetch [ds-conn url pulls-and-qs]
  (let [out-ch (async/chan)]
   (ajax-request {:method          :post
                  :uri             (str url "/datoms")
                  :timeout         30000
                  :params {:datoms-for pulls-and-qs}
                  :format (ajax-edn/edn-request-format)
                  :response-format (ajax/raw-response-format)
                  :handler (fn [[ok? res] result]
                             (if ok?
                               (let [datoms (->> (cljs.reader/read-string res)
                                                 :datoms
                                                 (mapv (fn [[e a v]]
                                                         [:db/add e a v])))]
                                 (d/transact! ds-conn datoms)
                                 (async/put! out-ch true))
                               (do
                                 (.error js/console "Error pre fetching datoms")
                                 (async/close! out-ch))))})
   out-ch))

(defn load-db-snapshot [url]
  (let [out-ch (async/chan)]
    (ajax-request {:method          :get
                   :uri             (str url "/db")
                   :timeout         30000
                   :response-format (ajax/raw-response-format)
                   :handler (fn [[ok? res] result]
                              (if ok?
                                (let [res-map (cljs.reader/read-string res)]
                                  (async/put! out-ch res-map))
                                (async/close! out-ch)))})
    out-ch))

(defn install [{:keys [progress-cb provider-url preindexer-url facts-db-address ds-conn pre-fetch-datoms transact-batch-size]}]

  (async/go
    (try

      (let [stop-watch-start (.getTime (js/Date.))
            last-block-so-far (atom 0)
            facts-to-transact (atom #{})
            facts-to-store (atom #{})]
        (<? (wait-for-load))
        (println "Page loaded")

        (when pre-fetch-datoms
          (println "Pre fetching datoms")
          (<? (pre-fetch ds-conn preindexer-url pre-fetch-datoms)))

        (set! js/web3js (js/Web3. (or (.-givenProvider js/web3) provider-url)))
        (<? (idb/init-indexed-db!))
        (println "IndexedDB initialized")

        ;; First try from IndexedDB
        (let [current-block-number (<? (get-block-number))
              idb-facts-count (<? (idb/get-store-facts-count))]
          (println "Current block number is " current-block-number)
          (println "IndexedDB contains " idb-facts-count "facts")

          (if (pos? idb-facts-count)
            (let [last-stored-bn (<? (idb/last-stored-block-number))
                  idb-facts (->> (<? (idb/every-store-fact-ch))
                                 (mapv fact->ds-fact))]
              (println "We have facts on IndexedDB. Last stored block number is " last-stored-bn)
              (reset! last-block-so-far last-stored-bn)
              (swap! facts-to-transact (fn [fs] (into fs idb-facts))))

            ;; NO IndexDB facts, try to load a snapshot
            (let [_ (println "We DON'T have IndexedDB facts, lets try to load a snapshot")
                  {:keys [db last-seen-block]} (<? (load-db-snapshot preindexer-url))]
              (if db
                (let []
                  (reset! last-block-so-far last-seen-block)
                  (println "we have a snapshot, installing it")
                  (swap! facts-to-transact (fn [fs] (->> (d/datoms db :eavt)
                                                         (mapv (fn [{:keys [e a v]}] [:db/add e a v]))
                                                         (into fs)) ))

                  (swap! facts-to-store (fn [fs]
                                          (->> (d/datoms db :eavt)
                                               (map (fn [{:keys [e a v block-num]}]
                                                      {:entity e :attribute a :value v :block-num block-num}))
                                               (into fs )))))

                (println "We couldn't download a snapshot"))))

          ;; we already or got facts from IndexedDB or downloaded a snapshot, or we don't have anything
          ;; in any case sync the remainning from blockchain
          (println "Let's sync the remainning facts directly from the blockchain. Last block seen " @last-block-so-far)

          (let [past-facts (<? (get-past-events js/web3js facts-db-address @last-block-so-far))]
            (swap! facts-to-transact (fn [fs] (->> (mapv fact->ds-fact past-facts)
                                                   (into fs))))
            (swap! facts-to-store (fn [fs] (into fs past-facts)))))

        (println "Transacting facts")
        (if transact-batch-size
          (do
            (when (< transact-batch-size 32) (throw (js/Error. "transact-batch-size should be nil or >= 32")))
            (let [finish-ch (async/chan)]
              (transact-facts-batch finish-ch ds-conn transact-batch-size progress-cb @facts-to-transact (count @facts-to-transact) 0)
              (<? finish-ch)))
          (d/transact! ds-conn (vec @facts-to-transact)))

        (println "Storing facts")
        (idb/store-facts @facts-to-store)

        (println "New facts listener installed")

        (progress-cb {:state :ready :startup-time-in-millis (- (.getTime (js/Date.)) stop-watch-start)})
        (println "Started in :" (- (.getTime (js/Date.)) stop-watch-start) " millis")

        ;; keep listening to new facts and transacting them to datascript db
        (let [new-facts-ch (install-facts-filter! js/web3js facts-db-address)]
          (loop [nf (<? new-facts-ch)]
            (d/transact! ds-conn [(fact->ds-fact nf)])
            (recur (<? new-facts-ch)))))
      (catch js/Error e (.log js/console e)))))
