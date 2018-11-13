(ns ^:figwheel-hooks d0xperiments.core
  (:require [bignumber.core :as bn]
            [clojure.core.async :as async])
  (:require-macros [d0xperiments.utils :refer [slurpf]]))

(def facts-db-abi (-> (slurpf "./contracts/build/FactsDb.abi")
                      js/JSON.parse))

(defprotocol Web3FactsEmitter
  (last-block-number [_ callback] "Returns last block number. Callback receives err, lbn")
  (all-past-facts [_ contract-address from-block callback] "Calls callback with err, all-past-events, events like {:entity :attribute :value :add :block-num}")
  (listen-new-facts [_ contract-address callback] "Calls callback with err, event for each new event, events like {:entity :attribute :value :add :block-num}"))


(defn get-block-number [facts-emitter]
  (let [out-ch (async/chan)]
    (last-block-number facts-emitter (fn [err last-block-num]
                                       (async/put! out-ch last-block-num)))
    out-ch))

(defn install-facts-filter!
  [facts-emitter facts-db-address]
  (let [out-ch (async/chan)]
    (listen-new-facts facts-emitter
                      facts-db-address
                      (fn [err ev]
                        (if err
                          (async/put! out-ch (js/Error. "Error in event watcher" err))
                          (async/put! out-ch ev))))

    out-ch))

(defn get-past-events [facts-emitter facts-db-address from-block]
  (let [out-ch (async/chan)]
    (all-past-facts facts-emitter
                    facts-db-address
                    from-block
                    (fn [err evs]
                      (if err
                        (async/put! out-ch (js/Error. "Error replaying past events " err))
                        (do (.log js/console "Returning " evs)
                          (async/put! out-ch evs)))))
    out-ch))
