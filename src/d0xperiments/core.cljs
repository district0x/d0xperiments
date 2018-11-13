(ns ^:figwheel-hooks d0xperiments.core
  (:require [bignumber.core :as bn]
            [clojure.core.async :as async])
  (:require-macros [d0xperiments.utils :refer [slurpf]]))


(def facts-db-abi (-> (slurpf "./contracts/build/FactsDb.abi")
                      js/JSON.parse))

(defn get-block-number []
  (let [out-ch (async/chan)]
    (.getBlockNumber js/web3.eth (fn [err last-block-num]
                                   (async/put! out-ch last-block-num)))
    out-ch))

(defn- build-fact [ev]
  {:entity    (bn/number (-> ev .-returnValues .-entity))
   :attribute (keyword (-> ev .-returnValues .-attribute))
   :value     (if (bn/bignumber? (-> ev .-returnValues .-val))
                (bn/number (-> ev .-returnValues .-val))
                (-> ev .-returnValues .-val))
   :add       (boolean (-> ev .-returnValues .-add))
   :block-num (bn/number (-> ev .-blockNumber))})

(defn install-facts-filter!
  [web3 facts-db-address]
  (let [facts-db-contract (new js/web3.eth.Contract facts-db-abi facts-db-address)
        out-ch (async/chan)]
    (-> facts-db-contract
        .-events
        (.allEvents #js {}
                    (fn [err ev]
                      (if err
                        (async/put! out-ch (js/Error. "Error in event watcher" err))
                        (async/put! out-ch (-> ev
                                               build-fact))))))
    out-ch))

(defn get-past-events [web3 facts-db-address from-block]
  (let [facts-db-contract (new js/web3.eth.Contract facts-db-abi facts-db-address)
        out-ch (async/chan)]
    (-> facts-db-contract
        (.getPastEvents "allEvents"
                        #js {:fromBlock from-block}
                        (fn [err evs]
                          (if err
                            (async/put! out-ch (js/Error. "Error replaying past events " err))
                            (async/put! out-ch (->> evs (map build-fact)))))))
    out-ch))
