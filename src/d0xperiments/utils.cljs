(ns d0xperiments.utils
  (:require [d0xperiments.core :as core]
            [bignumber.core :as bn]))

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

(defn- build-fact-web3-js-0 [ev]
  {:entity    (bn/number (-> ev .-args .-entity))
   :attribute (keyword (-> ev .-args .-attribute))
   :value     (if (bn/bignumber? (-> ev .-args .-val))
                (bn/number (-> ev .-args .-val))
                (-> ev .-args .-val))
   :add       (boolean (-> ev .-args .-add))
   :block-num (bn/number (-> ev .-blockNumber))})

(def facts-topics ["Fact(uint256,string,string,bool)"
                   "Fact(uint256,string,uint256,bool)"
                   "Fact(uint256,string,address,bool)"
                   "Fact(uint256,string,bytes,bool)"
                   "Fact(uint256,string,bytes32,bool)"])

(defrecord Web3js0FactsEmitter [web3-obj]

  core/Web3FactsEmitter

  (last-block-number [emitter callback]
    (.getBlockNumber (-> web3-obj .-eth) callback))

  (all-past-facts [emitter contract-address from-block callback]
    (let [facts-db-contract (.contract (.-eth web3-obj) core/facts-db-abi)
          facts-db-instance (.at facts-db-contract contract-address)]
      (.log js/console "GOING WITH " (clj->js {:fromBlock from-block
                                               :toBlock "latest"
                                               :topics (map #((.-sha3 web3-obj) %) facts-topics)}))
      (-> facts-db-instance
          (.Fact (clj->js {})
                 (clj->js {:fromBlock from-block
                           :toBlock "latest"
                           :topics (map #((.-sha3 web3-obj) %) facts-topics)}))
          (.get (fn [err evs]
                  (if err
                    (callback err nil)
                    (callback nil (->> evs (map build-fact-web3-js-0)))))))))

  (listen-new-facts [emitter contract-address callback]
    (let [facts-db-contract (.contract (.-eth web3-obj) core/facts-db-abi)
          facts-db-instance (.at facts-db-contract contract-address)]
      (-> facts-db-instance
          (.Fact (clj->js {})
                 (clj->js {:topics (map #((.-sha3 web3-obj) %) facts-topics)}))
          (.watch (fn [err ev]
                    (if err
                      (callback err nil)
                      (callback nil (build-fact-web3-js-0 ev)))))))))

(defn make-web3js-0-facts-emitter [web3-obj]
  (->Web3js0FactsEmitter web3-obj))

(defn- build-fact-web3-js-1 [ev]
  {:entity    (bn/number (-> ev .-returnValues .-entity))
   :attribute (keyword (-> ev .-returnValues .-attribute))
   :value     (if (bn/bignumber? (-> ev .-returnValues .-val))
                (bn/number (-> ev .-returnValues .-val))
                (-> ev .-returnValues .-val))
   :add       (boolean (-> ev .-returnValues .-add))
   :block-num (bn/number (-> ev .-blockNumber))})

(defrecord Web3js1FactsEmitter [web3-obj]

  core/Web3FactsEmitter

  (last-block-number [emitter callback]
    (.getBlockNumber (-> web3-obj .-eth) callback))

  (all-past-facts [emitter contract-address from-block callback]
    (let [facts-db-contract (new (-> web3-obj .-eth .-Contract) core/facts-db-abi contract-address)]
     (-> facts-db-contract
         (.getPastEvents "allEvents"
                         #js {:fromBlock from-block}
                         (fn [err evs]
                           (if err
                             (callback err nil)
                             (callback nil (->> evs (map build-fact-web3-js-1)))))))))

  (listen-new-facts [emitter contract-address callback]
    (let [facts-db-contract (new (-> web3-obj .-eth .-Contract) core/facts-db-abi contract-address)]
     (-> facts-db-contract
         .-events
         (.allEvents #js {} (fn [err ev]
                              (if err
                                (callback err nil)
                                (callback nil (build-fact-web3-js-1 ev)))))))))

(defn make-web3js-1-facts-emitter [web3-obj]
  (->Web3js1FactsEmitter web3-obj))
