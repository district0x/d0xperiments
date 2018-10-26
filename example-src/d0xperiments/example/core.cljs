(ns d0xperiments.example.core
  (:require [d0xperiments.core :refer [make-facts-syncer]]
            [datascript.core :as d]
            [bignumber.core :as bn]
            [cljs-web3.core :as web3]
            [re-posh.core :as re-posh]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]))

(def facts-db-address "0x24c51375f85def94f73c65701d4a2a14010ae0c7")

(defonce conn (d/create-conn))
(re-posh/connect! conn)

(re-posh/reg-sub
 ::all-people
 (fn [db _]
   {:type :query
   :query '[:find [?eid ...]
            :where [?eid :person/name]]}))

#_(re-frame/reg-sub
 ::all-people
 :<- [::all-person-ids]
 (fn [id _]
  {:type :pull
   :pattern '[*]
   :id id}))

(re-posh/reg-event-ds
 ::add-fact
 (fn [_ [_ e a v t x]]
   (.log js/console "Adding fact" [:db/add e a v t])
   [[:db/add e a v t]]))

(defn users-list []
  (let [users @(re-frame/subscribe [::all-people])]
    [:ul
     (for [u users]
       [:li u])]))

(defn mount-root []
  (re-frame/clear-subscription-cache!)
  (reagent/render [users-list]
                  (.getElementById js/document "app")))

(.addEventListener js/window "load"
                   (fn []
                     ;; as shown in https://github.com/MetaMask/faq/blob/master/DEVELOPERS.md#partly_sunny-web3---ethereum-browser-environment-check
                     (if js/web3
                       (set! js/web3js (js/Web3. (.-currentProvider js/web3)))
                       (set! js/web3js (web3/create-web3 js/Web3 "http://localhost:8549/")))

                     (.log js/console "Web3js is " js/web3js)

                     (let [filters (make-facts-syncer js/web3js facts-db-address
                                                      (fn [[e a v t x]]
                                                        (let [datom [(bn/number e) (keyword a) v t x]]
                                                          (.log js/console "D" datom)
                                                          (re-frame/dispatch (into [::add-fact] datom)))))]
                       (.log js/console "Added " filters))
                     (mount-root)))

(comment

  @(re-frame/subscribe [::all-person-ids])
  @(re-frame/subscribe [::all-people])

  )
