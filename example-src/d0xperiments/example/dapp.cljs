(ns d0xperiments.example.dapp
  (:require [d0xperiments.core :refer [make-facts-syncer]]
            [datascript.core :as d]
            [bignumber.core :as bn]
            [cljs-web3.core :as web3]
            [cljs-web3.eth :as web3-eth]
            [re-posh.core :as re-posh]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [day8.re-frame.http-fx]
            [ajax.core :as ajax]
            [clojure.string :as str]))

(def facts-db-address "0x360b6d00457775267aa3e3ef695583c675318c05")

(defonce db-conn (atom nil))

;;;;;;;;;;;;
;; Events ;;
;;;;;;;;;;;;

(re-frame/reg-event-fx
 ::initialize
 (fn [_ [_ last-block-num]]
   {:db {:sync-progress {"uint256,string,string,bool" 0
                         "uint256,string,address,bool" 0
                         "uint256,string,uint256,bool" 0}
         :filters-start-time-millis (.getTime (js/Date.))}
    :dispatch [::reload-db last-block-num]}))

(re-frame/reg-event-fx
 ::reload-db
 (fn [cofxs [_ last-block-num]]
   (.log js/console "Trying to fetch a pre indexed db")
   {:http-xhrio {:method          :get
                 :uri             "http://localhost:1234"
                 :timeout         30000
                 :response-format (ajax/raw-response-format)
                 :on-success      [::db-loaded]
                 :on-failure      [::browser-full-sync last-block-num]}}))

(re-frame/reg-event-fx
 ::db-loaded
 (fn [{:keys [db]} [_ raw-res]]
   (.log js/console "HEY, we got a pre indexed db, using that")
   (let [res-map (cljs.reader/read-string raw-res)
         db-conn (d/conn-from-db (:db res-map))]
     ;; This things needs to be in order????
     {::install-db db-conn
      ::mount-root nil
      :db (-> db
              (assoc :facts-counter (count (d/datoms @db-conn :eavt)))
              (assoc :last-seen-block (:last-seen-block res-map)))
      ::install-filters {:from-block (:last-seen-block res-map)}})))

(re-frame/reg-event-fx
 ::browser-full-sync
 (fn [{:keys [db]} [_ last-block-num]]
   (.log js/console "We couldn't find a pre indexed db, no worries, syncing from blockchain from 0 to " last-block-num)
   {::install-db (d/create-conn)
    ::mount-root nil
    ::install-filters {:from-block 0}
    :db (-> db
            (assoc :init-sync-block 0
                   :dest-sync-block last-block-num))}))

(def facts-stop-watch-mark 59640)

(re-frame/reg-event-fx
 ::add-fact
 (fn [{:keys [db]} [_ [e a v t x :as fact] {:keys [block-num event-sig]}]]
   (when (= (:facts-counter db)  facts-stop-watch-mark)
     (.log js/console "Synchronized " facts-stop-watch-mark " facts in " (- (.getTime (js/Date.))
                                                                            (:filters-start-time-millis db))
           "millis"))
   (when (and (:dest-sync-block db) (mod (:facts-counter db) 2000))
     (.log js/console block-num " from " event-sig (quot (* 100 (- block-num (:init-sync-block db)))
                                                         (- (:dest-sync-block db) (:init-sync-block db)))
           "%"))

   {:transact [[:db/add e a v t]]
    :db (-> db
            (assoc-in [:sync-progress event-sig] (quot (* 100 (- block-num (:init-sync-block db)))
                                                       (- (:dest-sync-block db) (:init-sync-block db))))
            (update :facts-counter (fnil inc 0))
            (update :last-seen-block (partial (fnil max 0) block-num)))}))

;; Search by title matching a regular expression
;; sort-by title
;; return only first page
(re-frame/reg-event-fx
 ::search-memes
 [(re-frame/inject-cofx ::datascript-db)]
 (fn [{:keys [db ::datascript-db] :as cfx} [_ text]]
   (let [q-result (d/q '[:find ?eid ?title
                         :in $ ?text ?re
                         :where
                         [?eid :reg-entry/address]
                         [?eid :meme/title ?title]
                         [(re-matches ?re ?title)]]
                       datascript-db
                       text (re-pattern (str ".*" text ".*")))]
     {:db (assoc db :meme-search-results (->> q-result
                                              (sort-by second)
                                              (map first)
                                              (take 20)))})))

;;;;;;;;;;;;;;;;;;;
;; FXs and COFXs ;;
;;;;;;;;;;;;;;;;;;;

(re-frame/reg-fx
 ::install-filters
 (fn [{:keys [from-block]}]
   (let [filters (make-facts-syncer js/web3js facts-db-address
                                    (fn [[e a v t x] fact-info]
                                      (let [datom [(bn/number e)
                                                   (keyword a)
                                                   (if (bn/bignumber? v)
                                                     (bn/number v)
                                                     v)
                                                   t
                                                   x]]
                                        (re-frame/dispatch [::add-fact datom fact-info])))
                                    from-block)]
     (.log js/console "Added " filters))))

(re-frame/reg-fx
 ::install-db
 (fn [conn]
   (reset! db-conn conn)
   (re-posh/connect! conn)))

(declare mount-root)

(re-frame/reg-fx
 ::mount-root
 (fn [conn]
   (mount-root)))

(re-frame/reg-cofx
 ::datascript-db
 (fn [cofxs]
   (assoc cofxs ::datascript-db @@db-conn)))


;;;;;;;;;;;;;;;;;;;;
;; Subscriptions  ;;
;;;;;;;;;;;;;;;;;;;;


(re-frame/reg-sub
 ::installing?
 (fn [db _]
   (->> (vals (:sync-progress db))
        (apply min)
        (> 99))))

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

#_(re-posh/reg-query-sub
 ::meme-search
 '[:find ?eid
   :in $ ?text ?re
   :where
   [?eid :reg-entry/address]
   [?eid :meme/title ?title]
   [(re-matches ?re ?title)]
   #_[(or [?eid :reg-etry/tag ?text])]])

(re-frame/reg-sub
 ::meme-search-results
 (fn [db [_]]
   (:meme-search-results db)))

;; Don't know why pull isn't working
#_(re-posh/reg-sub
 ::meme-b
 (fn [_ [_ id]]
   {:type    :pull
    :pattern '[:meme/title]
    :id      id}))

(re-posh/reg-query-sub
 ::meme
 '[:find [?t ?rea]
   :in $ ?eid
   :where
   [?eid :reg-entry/address ?rea]
   [?eid :meme/title ?t]])


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
    [:li (str "Challenges count: " (->> @(re-frame/subscribe [::attr-count :reg-entry/challenge]) first first))]
    [:li (str "Votes count: " (->> @(re-frame/subscribe [::attr-count :challenge/vote]) first first))]
    [:li (str "Votes reveals count: " (->> @(re-frame/subscribe [::attr-count :vote/revealed-on]) first first))]
    [:li (str "Votes reclaims count: " (->> @(re-frame/subscribe [::attr-count :vote/reclaimed-reward-on]) first first))]
    [:li (str "Tokens count: " (->> @(re-frame/subscribe [::attr-count :token/id]) first first))]
    [:li (str "Auctions count: " (->> @(re-frame/subscribe [::attr-count :auction/token-id]) first first))]]))

(defn search-item [id]
  (let [[title address] @(re-frame/subscribe [::meme id])]
    [:li (str "Title: " title " @ " address)]))

(defn meme-factory-search []
  (let [search-val (reagent/atom "")
        result (re-frame/subscribe [::meme-search-results])]
    (fn []
      [:div
       [:div
        [:label "Search:"]
        [:input {:on-change #(reset! search-val (-> % .-target .-value))}]
        [:button {:on-click (fn [e] (re-frame/dispatch [::search-memes @search-val]))}
         "Search"]]
       [:ul
        (for [m @result]
          ^{:key m}
          [search-item m])]])))

(defn main []
  (let [installing? @(re-frame/subscribe [::installing?])]
    (if installing?
      [:div "Installing dApp, please wait, check the console for progress ... "]
      [:div
       [hud]
       [meme-factory-search]])))

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

                     #_ (.log js/console "Web3js is " js/web3js)
                     (web3-eth/block-number js/web3js (fn [err last-block-num]
                                                        (.log js/console "Last block number is " last-block-num)
                                                        (re-frame/dispatch-sync [::initialize last-block-num])))))



(comment

  @(re-frame/subscribe [::all-person-ids])
  @(re-frame/subscribe [::all-people])

  )
