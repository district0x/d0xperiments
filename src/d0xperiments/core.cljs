(ns ^:figwheel-hooks d0xperiments.core
  (:require [cljs-web3.core :as web3]
            [cljs-web3.eth :as web3-eth])
  (:require-macros [d0xperiments.core :refer [facts-abi-string]]))


(def facts-db-abi (-> (facts-abi-string "./contracts/build/FactsDb.abi")
                      js/JSON.parse))

(defn make-facts-syncer
  ([web3 facts-db-address fact-callback] (make-facts-syncer web3 facts-db-address fact-callback 0))
  ([web3 facts-db-address fact-callback from-block]
   (let [facts-db-contract (web3-eth/contract-at web3 facts-db-abi facts-db-address)
         fact-fn (fn [ev event-sig]
                   (let [fact (-> ev .-args)
                         block-num (-> ev .-blockNumber)
                         tx-hash (-> ev .-transactionHash)]
                     (fact-callback [(.-entity fact)
                                     (.-attribute fact)
                                     (.-val fact)
                                     tx-hash
                                     (.-add fact)]
                                    {:block-num block-num
                                     :event-sig event-sig})))
         get-filter (fn [event-sig]
                      (-> ((aget (.-Fact facts-db-contract) event-sig)
                           (clj->js {:fromBlock from-block :toBlock "latest"}))
                          (.get (fn [err events]
                                  (if err
                                    (.error js/console "Got error in facts get " err)
                                    (doseq [ev events]
                                      (fact-fn ev event-sig)))))))
         watch-filter (fn [event-sig]
                        (-> ((aget (.-Fact facts-db-contract) event-sig)
                             (clj->js {}))
                            (.watch (fn [err ev]
                                      (if err
                                        (.error js/console "Got error in facts watch " err)
                                        (fact-fn ev event-sig))))))]
     [(get-filter "uint256,string,string,bool")
      (watch-filter "uint256,string,string,bool")

      (get-filter "uint256,string,address,bool")
      (watch-filter "uint256,string,address,bool")

      (get-filter "uint256,string,uint256,bool")
      (watch-filter "uint256,string,uint256,bool")

      (get-filter "uint256,string,bytes,bool")
      (watch-filter "uint256,string,bytes,bool")

      (get-filter "uint256,string,bytes32,bool")
      (watch-filter "uint256,string,bytes32,bool")])))
