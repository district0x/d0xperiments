(ns d0xperiments.web3.core)

(defprotocol Events
  (-past-events [_ opts callback])
  (-on-new-events [_ opts callback]))

(defprotocol Blockchain
  (-last-block-number [_])
  (-block [_ number])
  (-tx [_ tx-hash])
  (-tx-receipt [_ tx-hash]))

(defprotocol Account
  (-balance [_ id]))

(defprotocol NameService
  (-resolve-name [_ name])
  (-lookup-address [_ address]))

(defprotocol ContractExecution
  (-call-constant [_ contract-key method args opts callbacks])
  (-call-tx [_ contract-key method args opts callbacks]))

(defprotocol ContractDeploy
  (-deploy [_ contract-key]))

;;;;;;;;;;;;
;; Events ;;
;;;;;;;;;;;;

(defn past-events [web3 opts callback]
  (-past-events web3 opts callback))

(defn on-new-events [web3 opts callback]
  (-on-new-events web3 opts callback))

;;;;;;;;;;;;;;;;
;; Blockchain ;;
;;;;;;;;;;;;;;;;

(defn last-block-number [web3]
  (-last-block-number web3))

(defn block [web3 number]
  (-block web3 number))

(defn tx [web3 tx-hash]
  (-tx web3 tx-hash))

(defn tx-receipt [web3 tx-hash]
  (-tx-receipt web3 tx-hash))

;;;;;;;;;;;;;
;; Account ;;
;;;;;;;;;;;;;

(defn balance [web3 id]
  (-balance web3 id))

;;;;;;;;;;;;;;;;;
;; NameService ;;
;;;;;;;;;;;;;;;;;

(defn resolve-name [web3 name]
  (-resolve-name web3 name))

(defn lookup-address [web3 address]
  (-lookup-address web3 address))

;;;;;;;;;;;;;;;;;;;;;;;
;; ContractExecution ;;
;;;;;;;;;;;;;;;;;;;;;;;

(defn call-constant [web3 contract-key method args opts callbacks]
  (-call-constant web3 contract-key method args opts callbacks))
(defn call-tx [web3 contract-key method args opts callbacks]
  (-call-tx web3 contract-key method args opts callbacks))

;;;;;;;;;;;;;;;;;;;;
;; ContractDeploy ;;
;;;;;;;;;;;;;;;;;;;;

(defn deploy [web3 contract-key]
  (-deploy web3 contract-key))
