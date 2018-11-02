(ns d0xperiments.browser-installer
  (:require [d0xperiments.core :refer [install-facts-filter! get-block-number get-past-events]]
            [datascript.core :as d]
            [clojure.core.async :as async]
            [ajax.core :refer [ajax-request] :as ajax]

            [d0xperiments.indexeddb :as idb])
  (:require-macros [d0xperiments.utils :refer [<?]]))



(defn wait-for-load []
  (let [out-ch (async/chan)]
    (.addEventListener js/window "load" #(async/put! out-ch true))
    out-ch))

(defn fact->ds-fact [{:keys [entity attribute value]}]
  [:db/add entity attribute value])

(defn load-db-snapshot [url]
  (let [out-ch (async/chan)]
    (ajax-request {:method          :get
                   :uri             url
                   :timeout         30000
                   :response-format (ajax/raw-response-format)
                   :handler (fn [[ok? res] result]
                              (if ok?
                                (let [res-map (cljs.reader/read-string res)]
                                  (async/put! out-ch res-map))
                                (async/close! out-ch)))})
    out-ch))

(defn install [{:keys [progress-cb provider-url preindexer-url facts-db-address]}]
  (println "STARTING:" (.getTime (js/Date.)))
  (async/go
    (try
      (let [last-block-so-far (atom 0)
            db-conn (atom nil)]
        (<? (wait-for-load))
        (println "Page loaded")

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
                                 (mapv fact->ds-fact))
                  ds-db-conn (d/create-conn)]
              (println "We have facts on IndexedDB. Last stored block number is " last-stored-bn)
              (reset! last-block-so-far last-stored-bn)
              (d/transact! ds-db-conn idb-facts)
              (reset! db-conn ds-db-conn)
              (progress-cb {:state :datascript-db-ready :db-conn ds-db-conn}))

            ;; NO IndexDB facts, try to load a snapshot
            (let [_ (println "We DON'T have IndexedDB facts, lets try to load a snapshot")
                  {:keys [db last-seen-block]} (<? (load-db-snapshot preindexer-url))]
              (if db
                (let [dbc (d/conn-from-db db)]
                  (reset! last-block-so-far last-seen-block)
                  (println "we have a snapshot, installing it")
                  (progress-cb {:state :datascript-db-ready :db-conn dbc})
                  (reset! db-conn dbc)
                  (idb/store-facts (->> (d/datoms db :eavt)
                                        (map (fn [{:keys [e a v block-num]}]
                                               {:entity e :attribute a :value v :block-num block-num})))))

                (println "We couldn't download a snapshot"))))

          ;; we already or got facts from IndexedDB or downloaded a snapshot, or we don't have anything
          ;; in any case sync the remainning from blockchain
          (println "Let's sync the remainning facts directly from the blockchain. Last block seen " @last-block-so-far)
          (when-not @db-conn (reset! db-conn (d/create-conn)))

          (progress-cb {:state :downloading-facts})

          (let [past-facts (<? (get-past-events js/web3js facts-db-address @last-block-so-far))]
            (progress-cb {:state :installing-facts})
            (d/transact! @db-conn (mapv fact->ds-fact past-facts))
            (idb/store-facts past-facts)))

        (progress-cb {:state :datascript-db-ready :db-conn @db-conn})

        (println "New facts listener installed")
        (progress-cb {:state :ready})
        (println "DONE:" (.getTime (js/Date.)))

        ;; keep listening to new facts and transacting them to datascript db
        (let [new-facts-ch (install-facts-filter! js/web3js facts-db-address)]
          (loop [nf (<? new-facts-ch)]
            (d/transact! @db-conn [(fact->ds-fact nf)])
            (recur (<? new-facts-ch)))))
      (catch js/Error e (.log js/console e)))))
