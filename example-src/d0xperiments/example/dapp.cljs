(ns ^:figwheel-hooks d0xperiments.example.dapp
  (:require [datascript.core :as d]
            [bignumber.core :as bn]
            [re-posh.core :as re-posh]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [posh.reagent :as posh]
            [clojure.string :as str]
            [goog.string :as gstring]
            [d0xperiments.browser-installer :as installer])
  (:require-macros [d0xperiments.utils :refer [slurpf]]))

;; TODO Add chema here!!!

(defonce db-conn (atom nil))
(def datascript-schema (-> (slurpf "./example-src/d0xperiments/example/db_schema.edn")
                           cljs.reader/read-string ))

;;;;;;;;;;;;
;; Events ;;
;;;;;;;;;;;;

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

(re-frame/reg-event-db
 :app-state-change
 (fn [db [_ state]]
   (assoc db :app-state state)))

;;;;;;;;;;;;;;;;;;;
;; FXs and COFXs ;;
;;;;;;;;;;;;;;;;;;;

(re-frame/reg-cofx
 ::datascript-db
 (fn [cofxs]
   (assoc cofxs ::datascript-db @@db-conn)))

;;;;;;;;;;;;;;;;;;;;
;; Subscriptions  ;;
;;;;;;;;;;;;;;;;;;;;

(re-frame/reg-sub
 ::app-state
 (fn [db _]
   (:app-state db)))

(def attr-count-query '[:find (count ?eid)
                        :in $ ?attr
                        :where [?eid ?attr]])

(re-posh/reg-query-sub
 ::attr-count
 attr-count-query)

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
(re-posh/reg-sub
 ::meme
 (fn [_ [_ id]]
   {:type    :pull
    :pattern '[*]
    :id      id}))

;;;;;;;;
;; UI ;;
;;;;;;;;

(defn hud [startup-time-in-millis]
  [:ul
   [:li (str "Started in: " startup-time-in-millis " millis") ]
   [:li (str "Memes count: " (->> @(re-frame/subscribe [::attr-count :reg-entry/address]) first first))]
   [:li (str "Challenges count: " (->> @(re-frame/subscribe [::attr-count :reg-entry/challenge]) first first))]
   [:li (str "Votes count: " (->> @(re-frame/subscribe [::attr-count :challenge/vote]) first first))]
   [:li (str "Votes reveals count: " (->> @(re-frame/subscribe [::attr-count :vote/revealed-on]) first first))]
   [:li (str "Votes reclaims count: " (->> @(re-frame/subscribe [::attr-count :vote/reclaimed-reward-on]) first first))]
   [:li (str "Tokens count: " (->> @(re-frame/subscribe [::attr-count :token/id]) first first))]
   [:li (str "Auctions count: " (->> @(re-frame/subscribe [::attr-count :auction/token-id]) first first))]])


(defn search-item [id]
  (let [m @(re-frame/subscribe [::meme id])]
    [:li (str m)]))

(defn meme-factory-search []
  (let [search-val (reagent/atom "")
        result (re-frame/subscribe [::meme-search-results])
        re-search (fn [& _] (re-frame/dispatch [::search-memes @search-val]))]
    (fn []
      [:div
       [:div
        [:label "Search:"]
        [:input {:on-change #(reset! search-val (-> % .-target .-value))
                 :on-key-press #(when (= (-> % .-key) "Enter") (re-search))}]
        [:button {:on-click re-search} "Search"]]
       [:ul
        (for [m @result]
          ^{:key m}
          [search-item m])]])))

(def example-meme-id 1.3254459306746723e+48)

(defn main []
  (let [app-state @(re-frame/subscribe [::app-state])]
    [:div
     [:div {:style {:color :red}}
      [hud (:startup-time-in-millis app-state)]
      [:h3 "Example meme"]
      [:ul (for [[k v] @(re-frame/subscribe [::meme example-meme-id])]
             ^{:key k}
             [:li (str (name k) " : " v)])]]
     (case (:state app-state)
       :downloading-facts   [:div "Downloading app data, please wait..."]
       :installing-facts    [:div "Installing app"]
       :datascript-db-ready [:div "Db ready"]
       :ready [meme-factory-search]
       [:div])]))


 ;;;;;;;;;;
 ;; Init ;;
 ;;;;;;;;;;

(defn ^:after-load mount-root []
  (re-frame/clear-subscription-cache!)
  (reagent/render [main]
                  (.getElementById js/document "app")))



;; progress-states
;; {:satate :downloading-facts}
;; {:satate :installing-facts}
;; {:satate :datascript-db-ready :db-conn conn}
;; {:satate :ready}

;; for 9000 blocks / 61230 facts

;; DESKTOP
;; 26 sec full install
;; 4,8 sec snapshot download + install (~800Kb)
;; 3,6 sec from IndexedDB

;; MOBILE
;;  sec full install
;; 14,7 sec snapshot download + install (~800Kb)
;;  sec from IndexedDB


(defn install-datascript-db! [conn]
  (reset! db-conn conn)
  (re-posh/connect! conn))

(defn ^:export init []
  (let [dc (d/create-conn datascript-schema)]
    (install-datascript-db! dc)
    (mount-root)

    (installer/install {:provider-url "ws://localhost:8549/"
                        :preindexer-url "http://localhost:1234"
                        :facts-db-address "0x360b6d00457775267aa3e3ef695583c675318c05"
                        :progress-cb (fn [{:keys [state] :as progress}]
                                       (re-frame/dispatch [:app-state-change progress]))
                        :ds-conn dc

                        :pre-fetch-datoms [{:type    :pull
                                            :pattern '[*]
                                            :ids      [example-meme-id]}]})))
