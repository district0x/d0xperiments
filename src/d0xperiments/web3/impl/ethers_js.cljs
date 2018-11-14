(ns d0xperiments.web3.impl.ethers-js
  (:require [d0xperiments.web3.core :as web3-core]))

(defrecord EthersJS [provider contracts-map])

(defn make-ethers-js [provider contracts-map]
  (->EthersJS provider contracts-map))

(extend-type EtherJS

  web3-core/Events

  (-past-events [{:keys [provider contracts-map]} opts callback]
    )
  (-on-new-events [{:keys [provider contracts-map]} opts callback]
    )


  web3-core/Blockchain

  (-last-block-number [{:keys [provider contracts-map]}]
    )
  (-block [{:keys [provider contracts-map]} number]
    )
  (-tx [{:keys [provider contracts-map]} tx-hash]
    )
  (-tx-receipt [{:keys [provider contracts-map]} tx-hash]
    )


  web3-core/Account

  (-balance [{:keys [provider contracts-map]} id]
    )


  web3-core/NameService

  (-resolve-name [{:keys [provider contracts-map]} name]
    )
  (-lookup-address [{:keys [provider contracts-map]} address]
    )


  web3-core/ContractExecution

  (-call-constant [{:keys [provider contracts-map]} contract-key method args opts callbacks]
    )
  (-call-tx [{:keys [provider contracts-map]} contract-key method args opts callbacks]
    ))
