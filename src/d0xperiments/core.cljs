(ns ^:figwheel-hooks d0xperiments.core
  (:require [cljs-web3.core :as web3]
            [cljs-web3.eth :as web3-eth]))

(defn ^:after-load on-reload [])

(def facts-db-abi (-> "[{\"constant\":false,\"inputs\":[{\"name\":\"entity\",\"type\":\"uint256\"},{\"name\":\"attribute\",\"type\":\"string\"},{\"name\":\"val\",\"type\":\"string\"}],\"name\":\"removeString\",\"outputs\":[],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"},{\"constant\":false,\"inputs\":[{\"name\":\"entity\",\"type\":\"uint256\"},{\"name\":\"attribute\",\"type\":\"string\"},{\"name\":\"val\",\"type\":\"string\"}],\"name\":\"transactString\",\"outputs\":[],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"},{\"anonymous\":false,\"inputs\":[{\"indexed\":false,\"name\":\"entity\",\"type\":\"uint256\"},{\"indexed\":false,\"name\":\"attribute\",\"type\":\"string\"},{\"indexed\":false,\"name\":\"val\",\"type\":\"string\"},{\"indexed\":false,\"name\":\"add\",\"type\":\"bool\"}],\"name\":\"Fact\",\"type\":\"event\"},{\"anonymous\":false,\"inputs\":[{\"indexed\":false,\"name\":\"entity\",\"type\":\"uint256\"},{\"indexed\":false,\"name\":\"attribute\",\"type\":\"string\"},{\"indexed\":false,\"name\":\"val\",\"type\":\"uint256\"},{\"indexed\":false,\"name\":\"add\",\"type\":\"bool\"}],\"name\":\"Fact\",\"type\":\"event\"},{\"anonymous\":false,\"inputs\":[{\"indexed\":false,\"name\":\"entity\",\"type\":\"uint256\"},{\"indexed\":false,\"name\":\"attribute\",\"type\":\"string\"},{\"indexed\":false,\"name\":\"val\",\"type\":\"address\"},{\"indexed\":false,\"name\":\"add\",\"type\":\"bool\"}],\"name\":\"Fact\",\"type\":\"event\"}]"
                      js/JSON.parse))

(defn make-facts-syncer [web3 facts-db-address fact-callback]
  (let [facts-db-contract (web3-eth/contract-at web3 facts-db-abi facts-db-address)
        fact-fn (fn [ev]
                  (let [fact (-> ev .-args)
                        tx-hash (-> ev .-transactionHash)]
                    (fact-callback [(.-entity fact)
                                    (.-attribute fact)
                                    (.-val fact)
                                    tx-hash
                                    (.-add fact)])))
        get-filter (fn [event-sig]
                     (-> ((aget (.-Fact facts-db-contract) event-sig)
                          (clj->js {:fromBlock 0 :toBlock "latest"}))
                         (.get (fn [err events]
                                 (if err
                                   (.error js/console "Got error in facts get " err)
                                   (doseq [ev events]
                                     (fact-fn ev)))))))
        watch-filter (fn [event-sig]
                       (-> ((aget (.-Fact facts-db-contract) event-sig)
                            (clj->js {}))
                           (.watch (fn [err ev]
                                     (if err
                                       (.error js/console "Got error in facts watch " err)
                                       (fact-fn ev))))))]
    [(get-filter "uint256,string,string,bool")
     (watch-filter "uint256,string,string,bool")]))
