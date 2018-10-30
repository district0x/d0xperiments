(ns d0xperiments.example.core
  (:require [d0xperiments.core :refer [make-facts-syncer]]
            [datascript.core :as d]
            [bignumber.core :as bn]
            [cljs-web3.core :as web3]
            [re-posh.core :as re-posh]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [day8.re-frame.http-fx]
            [ajax.core :as ajax]))

(def facts-db-address "0x360b6d00457775267aa3e3ef695583c675318c05")

(defonce conn (d/create-conn))

;;;;;;;;;;;;
;; Events ;;
;;;;;;;;;;;;

(re-frame/reg-event-fx
 ::initialize
 (fn [_ _]
   {:db {}
    :dispatch [::reload-db]}))

(re-frame/reg-event-fx
 ::reload-db
 (fn [cofxs _]
   (.log js/console "Trying to fetch a pre indexed db")
   {:http-xhrio {:method          :get
                 :uri             "http://localhost:1234"
                 :timeout         30000
                 :response-format (ajax/raw-response-format)
                 :on-success      [::db-loaded]
                 :on-failure      [::bad-http-result]}}))

(re-frame/reg-event-fx
 ::db-loaded
 (fn [db [_ raw-res]]
   (.log js/console "HEY, we got a pre indexed db, using that")
   (let [res-map (cljs.reader/read-string raw-res)
         db-conn (d/conn-from-db (:db res-map))]
     ;; This things needs to be in order????
     {::connect-re-posh db-conn
      ::mount-root nil
      :db (-> db
              (assoc :facts-counter (count (d/datoms @db-conn :eavt)))
              (assoc :last-seen-block (:last-seen-block res-map)))
      ::install-filters {:from-block (:last-seen-block res-map)}})))

(re-frame/reg-event-fx
 ::bad-http-result
 (fn [_ _]
   (.log js/console "We couldn't find a pre indexed db, no worries, syncing from blockchain")
   {::connect-re-posh (d/create-conn)
    ::mount-root nil
    ::install-filters {:from-block 0}}))

(re-frame/reg-event-fx
 ::add-fact
 (fn [{:keys [db]} [_ block-num [e a v t x]]]
   {:transact [[:db/add e a v t]]
    :db (-> db
            (update :facts-counter (fnil inc 0))
            (update :last-seen-block (partial (fnil max 0) block-num)))}))

;;;;;;;;
;; FX ;;
;;;;;;;;

(re-frame/reg-fx
 ::install-filters
 (fn [{:keys [from-block]}]
   (let [filters (make-facts-syncer js/web3js facts-db-address
                                    (fn [block-num [e a v t x]]
                                      (let [datom [(bn/number e) (keyword a) v t x]]
                                        (re-frame/dispatch [::add-fact block-num datom])))
                                    from-block)]
     (.log js/console "Added " filters))))

(re-frame/reg-fx
 ::connect-re-posh
 (fn [conn]
   (re-posh/connect! conn)))

(declare mount-root)

(re-frame/reg-fx
 ::mount-root
 (fn [conn]
   (mount-root)))


;;;;;;;;;;;;;;;;;;;;
;; Subscriptions  ;;
;;;;;;;;;;;;;;;;;;;;


(re-frame/reg-sub
 ::tracked-stats
 (fn [db _]
   (select-keys db [:facts-counter :last-seen-block])))

(re-posh/reg-sub
 ::entities-count
 (fn [db _]
   {:type :query
    :query '[:find (count ?eid)
             :where [?eid]]}))

(re-posh/reg-query-sub
 ::attr-count
 '[:find (count ?eid)
   :in $ ?attr
   :where [?eid ?attr]])


;;;;;;;;
;; UI ;;
;;;;;;;;

(defn hud []
  (let [{:keys [facts-counter last-seen-block]} @(re-frame/subscribe [::tracked-stats])]
   [:ul {}
    [:li (str "Last seen block: " last-seen-block)]
    [:li (str "Facts count: " facts-counter)]
    [:li (str "Entities count: " (->> @(re-frame/subscribe [::entities-count]) first first))]
    [:li (str "Memes count: " (->> @(re-frame/subscribe [::attr-count :reg-entry/address]) first first))]
    [:li (str "Auctions count: " (->> @(re-frame/subscribe [::attr-count :auction/token-id]) first first))]]))

(defn main []
  [:div
   [hud]])

 ;;;;;;;;;;
 ;; Init ;;
 ;;;;;;;;;;

(defn mount-root []
  (re-frame/clear-subscription-cache!)
  (reagent/render [main]
                  (.getElementById js/document "app")))

(.addEventListener js/window "load"
                   (fn []
                     ;; as shown in https://github.com/MetaMask/faq/blob/master/DEVELOPERS.md#partly_sunny-web3---ethereum-browser-environment-check
                     (if js/web3
                       (set! js/web3js (js/Web3. (.-currentProvider js/web3)))
                       (set! js/web3js (web3/create-web3 js/Web3 "http://localhost:8549/")))

                     #_(.log js/console "Web3js is " js/web3js)

                     (re-frame/dispatch-sync [::initialize])))



(comment

  @(re-frame/subscribe [::all-person-ids])
  @(re-frame/subscribe [::all-people])

  )
