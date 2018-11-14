(ns ^:figwheel-hooks d0xperiments.core
  (:require [bignumber.core :as bn]
            [clojure.core.async :as async]
            [d0xperiments.web3.core :as web3-core])
  (:require-macros [d0xperiments.utils :refer [slurpf]]))

(def facts-db-abi (-> (slurpf "./contracts/build/FactsDb.abi")
                      js/JSON.parse))

(defn build-fact [{:keys [:web3.block/number :web3.event/data] :as a}]
  {:entity (-> data :entity (.maskn 53) .toNumber)
   :attribute (keyword (:attribute data))
   :value (if (= (aget (-> data :val) "_ethersType") "BigNumber")
            (-> data :val (.maskn 53) .toNumber)
            (-> data :val))
   :block-num number
   :add (boolean (:add data))})

(defn get-block-number [web3]
  (let [out-ch (async/chan)]
    (web3-core/last-block-number web3
                                 {:on-result (fn [last-block-num]
                                               (async/put! out-ch last-block-num))})
    out-ch))

(defn install-facts-filter!
  [web3 facts-db-address]
  (let [out-ch (async/chan)]
    (web3-core/on-new-event web3
                            (web3-core/make-contract-instance facts-db-address facts-db-abi)
                            {}
                            {:on-result #(async/put! out-ch (build-fact %))
                             :on-error #(async/put! out-ch (js/Error. "Error in event watcher"))})

    out-ch))

(defn get-past-events [web3 facts-db-address from-block]
  (let [out-ch (async/chan)]
    (web3-core/past-events web3
                           (web3-core/make-contract-instance facts-db-address facts-db-abi)
                           {:from-block from-block}
                           {:on-result #(async/put! out-ch (map build-fact %))
                            :on-error #(async/put! out-ch (js/Error. "Error replaying past events "))})
    out-ch))
