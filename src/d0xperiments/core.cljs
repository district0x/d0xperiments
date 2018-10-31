(ns ^:figwheel-hooks d0xperiments.core
  (:require-macros [d0xperiments.core :refer [facts-abi-string]]))


(def facts-db-abi (-> (facts-abi-string "./contracts/build/FactsDb.abi")
                      js/JSON.parse))

;; TODO add storage stuff

(defn make-facts-syncer
  ([web3 facts-db-address fact-callback] (make-facts-syncer web3 facts-db-address fact-callback 0))
  ([web3 facts-db-address fact-callback from-block]
   (let [facts-db-contract (new js/web3.eth.Contract facts-db-abi facts-db-address)
         build-and-send-fact (fn [ev]
                               (let [fact (-> ev .-returnValues)
                                     block-num (-> ev .-blockNumber)
                                     tx-hash (-> ev .-transactionHash)]
                                 (fact-callback [(.-entity fact)
                                                 (.-attribute fact)
                                                 (.-val fact)
                                                 tx-hash
                                                 (.-add fact)]
                                                {:block-num block-num})))]
     (-> facts-db-contract
         (.getPastEvents "allEvents"
                         #js {:fromBlock from-block}
                         (fn [err evs]
                           (if err
                             (throw (js/Error. "Error replaying past events " err))
                             (doseq [e evs] (build-and-send-fact e))))))
     (-> facts-db-contract
         .-events
         (.allEvents #js {}
                     (fn [err ev]
                       (if err
                         (throw (js/Error. "Error in event watcher" err))
                         (build-and-send-fact ev))))))))
