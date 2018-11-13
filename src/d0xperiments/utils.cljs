(ns d0xperiments.utils
  (:require [d0xperiments.core :as core]))

(defn compress-facts [facts]
  (reduce (fn [cfs [e a v]]
            (update cfs a conj [e v]))
   {}
   facts))

(defn uncompress-facts [facts]
  (reduce (fn [fs [a evs]]
            (into fs
                  (reduce (fn [r [e v]]
                            (conj r [e a v]))
                   []
                   evs)))
   []
   facts))

(defrecord Web3js1FactsEmitter [web3-obj]

  core/Web3FactsEmitter

  (last-block-number [emitter callback]
    (.getBlockNumber (-> web3-obj .-eth) callback))

  (all-past-facts [emitter contract-address from-block callback]
    (let [facts-db-contract (new (-> web3-obj .-eth .-Contract) core/facts-db-abi contract-address)]
     (-> facts-db-contract
         (.getPastEvents "allEvents"
                         #js {:fromBlock from-block}
                         callback))))

  (listen-new-facts [emitter contract-address callback]
    (let [facts-db-contract (new (-> web3-obj .-eth .-Contract) core/facts-db-abi contract-address)]
     (-> facts-db-contract
         .-events
         (.allEvents #js {} callback)))))

(defn make-web3js-1-facts-emitter [web3-obj]
  (->Web3js1FactsEmitter web3-obj))
