(ns ^:figwheel-hooks d0xperiments.core
  (:require [bignumber.core :as bn])
  (:require-macros [d0xperiments.core :refer [facts-abi-string]]))


(def facts-db-abi (-> (facts-abi-string "./contracts/build/FactsDb.abi")
                      js/JSON.parse))


(defn- build-fact [ev]
  {:entity    (bn/number (-> ev .-returnValues .-entity))
   :attribute (keyword (-> ev .-returnValues .-attribute))
   :value     (if (bn/bignumber? (-> ev .-returnValues .-val))
                (bn/number (-> ev .-returnValues .-val))
                (-> ev .-returnValues .-val))
   :block-num (bn/number (-> ev .-blockNumber))})

(defn install-facts-filter
  [web3 facts-db-address from-block fact-callback]
  (let [facts-db-contract (new js/web3.eth.Contract facts-db-abi facts-db-address)]
    (-> facts-db-contract
        .-events
        (.allEvents #js {:fromBlock from-block}
                    (fn [err ev]
                      (if err
                        (throw (js/Error. "Error in event watcher" err))
                        (-> ev
                            build-fact
                            fact-callback)))))))

(defn get-past-events [web3 facts-db-address from-block fact-callback]
  (let [facts-db-contract (new js/web3.eth.Contract facts-db-abi facts-db-address)]
    (-> facts-db-contract
         (.getPastEvents "allEvents"
                         #js {:fromBlock from-block}
                         (fn [err evs]
                           (if err
                             (throw (js/Error. "Error replaying past events " err))
                             (doseq [ev evs]
                               (-> ev
                                   build-fact
                                   fact-callback))))))))
