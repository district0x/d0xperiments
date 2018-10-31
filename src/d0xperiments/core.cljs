(ns ^:figwheel-hooks d0xperiments.core
  (:require [cljs-web3.core :as web3]
            [cljs-web3.eth :as web3-eth]))

(defn ^:after-load on-reload [])

(def facts-db-abi (-> "[{\"constant\":false,\"inputs\":[{\"name\":\"entity\",\"type\":\"uint256\"},{\"name\":\"attribute\",\"type\":\"string\"},{\"name\":\"val\",\"type\":\"uint256\"}],\"name\":\"transactUInt\",\"outputs\":[],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"},{\"constant\":false,\"inputs\":[{\"name\":\"entity\",\"type\":\"uint256\"},{\"name\":\"attribute\",\"type\":\"string\"},{\"name\":\"val\",\"type\":\"string\"}],\"name\":\"removeString\",\"outputs\":[],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"},{\"constant\":false,\"inputs\":[{\"name\":\"entity\",\"type\":\"uint256\"},{\"name\":\"attribute\",\"type\":\"string\"},{\"name\":\"val\",\"type\":\"bytes\"}],\"name\":\"transactBytes\",\"outputs\":[],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"},{\"constant\":false,\"inputs\":[{\"name\":\"entity\",\"type\":\"uint256\"},{\"name\":\"attribute\",\"type\":\"string\"},{\"name\":\"val\",\"type\":\"string\"}],\"name\":\"transactString\",\"outputs\":[],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"},{\"constant\":false,\"inputs\":[{\"name\":\"entity\",\"type\":\"uint256\"},{\"name\":\"attribute\",\"type\":\"string\"},{\"name\":\"val\",\"type\":\"address\"}],\"name\":\"transactAddress\",\"outputs\":[],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"},{\"anonymous\":false,\"inputs\":[{\"indexed\":false,\"name\":\"entity\",\"type\":\"uint256\"},{\"indexed\":false,\"name\":\"attribute\",\"type\":\"string\"},{\"indexed\":false,\"name\":\"val\",\"type\":\"string\"},{\"indexed\":false,\"name\":\"add\",\"type\":\"bool\"}],\"name\":\"Fact\",\"type\":\"event\"},{\"anonymous\":false,\"inputs\":[{\"indexed\":false,\"name\":\"entity\",\"type\":\"uint256\"},{\"indexed\":false,\"name\":\"attribute\",\"type\":\"string\"},{\"indexed\":false,\"name\":\"val\",\"type\":\"uint256\"},{\"indexed\":false,\"name\":\"add\",\"type\":\"bool\"}],\"name\":\"Fact\",\"type\":\"event\"},{\"anonymous\":false,\"inputs\":[{\"indexed\":false,\"name\":\"entity\",\"type\":\"uint256\"},{\"indexed\":false,\"name\":\"attribute\",\"type\":\"string\"},{\"indexed\":false,\"name\":\"val\",\"type\":\"address\"},{\"indexed\":false,\"name\":\"add\",\"type\":\"bool\"}],\"name\":\"Fact\",\"type\":\"event\"},{\"anonymous\":false,\"inputs\":[{\"indexed\":false,\"name\":\"entity\",\"type\":\"uint256\"},{\"indexed\":false,\"name\":\"attribute\",\"type\":\"string\"},{\"indexed\":false,\"name\":\"val\",\"type\":\"bytes\"},{\"indexed\":false,\"name\":\"add\",\"type\":\"bool\"}],\"name\":\"Fact\",\"type\":\"event\"}] "
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
      (watch-filter "uint256,string,uint256,bool")])))
