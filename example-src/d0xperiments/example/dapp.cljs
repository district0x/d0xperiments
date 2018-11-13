(ns ^:figwheel-hooks d0xperiments.example.dapp
  (:require [datascript.core :as d]
            [bignumber.core :as bn]
            [re-posh.core :as re-posh]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [posh.reagent :as posh]
            [clojure.string :as str]
            [goog.string :as gstring]
            [d0xperiments.browser-installer :as installer]
            [d0xperiments.utils :refer [make-web3js-0-facts-emitter
                                        make-web3js-1-facts-emitter]])
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


(defn log-stats [db]
  (println (str "Memes count: " (->> @(re-frame/subscribe [::attr-count :reg-entry/address]) first first)))
  (println (str "Challenges count: " (->> @(re-frame/subscribe [::attr-count :reg-entry/challenge]) first first)))
  (println (str "Votes count: " (->> @(re-frame/subscribe [::attr-count :challenge/vote]) first first)))
  (println (str "Votes reveals count: " (->> @(re-frame/subscribe [::attr-count :vote/revealed-on]) first first)))
  (println (str "Votes reclaims count: " (->> @(re-frame/subscribe [::attr-count :vote/reclaimed-reward-on]) first first)))
  (println (str "Tokens count: " (->> @(re-frame/subscribe [::attr-count :token/id]) first first)))
  (println (str "Auctions count: " (->> @(re-frame/subscribe [::attr-count :auction/token-id]) first first))))

(re-frame/reg-event-db
 :app-state-change
 (fn [db [_ {:keys [state] :as prog}]]
   ;;(when (= :ready state) (log-stats db))
   (assoc db :app-state prog)))

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
  [:div {:style {:color :red}}
   [:div "HUD"]
   [:ul
    [:li (str "Started in: " startup-time-in-millis " millis") ]
    [:li (str "Memes count: " (->> @(re-frame/subscribe [::attr-count :reg-entry/address]) first first))]]])


(defn meme [id]
  (let [m @(re-frame/subscribe [::meme id])]
    (when (:reg-entry/address m)
      [:ul {:style {:border "1px solid blue" :margin-bottom 2}}
       (for [[k v] m]
             ^{:key k}
             [:li (str (name k) " : " v)])])))

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
          [meme m])]])))

(def example-meme-id 1.0566523466793907e+48)

(defn main []
  (let [app-state @(re-frame/subscribe [::app-state])]
    [:div
     [hud (:startup-time-in-millis app-state)]
     [:div {:style {:color :green}}
      [:h3 "Example meme"]
      [meme example-meme-id]]

     (case (:state app-state)
       :downloading-facts   [:div "Downloading app data, please wait..."]
       :installing-facts    [:div (gstring/format "Installing app (%d%%)" (:percentage app-state))]
       [:div])

     [meme-factory-search]]))


 ;;;;;;;;;;
 ;; Init ;;
 ;;;;;;;;;;

(defn ^:after-load mount-root []
  (re-frame/clear-subscription-cache!)
  (reagent/render [main]
                  (.getElementById js/document "app")))

;; progress-states
;; {:state :downloading-facts}
;; {:state :installing-facts :percentage 50}
;; {:state :ready}


(defn install-datascript-db! [conn]
  (reset! db-conn conn)
  (re-posh/connect! conn))

(defn ^:export init []
  (let [dc (d/create-conn datascript-schema)
        ;; For web3js 1.0
        web3-obj (js/Web3. (or (.-givenProvider js/web3) "ws://localhost:8549/"))
        facts-emitter (make-web3js-1-facts-emitter web3-obj)

        ;; For web3js 0.20
        ;; web3-obj (if js/web3
        ;;            (js/Web3. (.-currentProvider js/web3))
        ;;            (js/Web3. (new js/Web3.providers.HttpProvider "http://localhost:8549/")))
        ;; facts-emitter (make-web3js-0-facts-emitter web3-obj)

        ]
    (install-datascript-db! dc)
    (mount-root)

    (installer/install {:facts-emitter facts-emitter
                        :preindexer-url "http://localhost:1234"
                        :facts-db-address #_"0x360b6d00457775267aa3e3ef695583c675318c05" "0x1994a5281cc200e7516e02cac1e707eb6cfa388e"
                        :progress-cb (fn [{:keys [state] :as progress}]
                                       (re-frame/dispatch [:app-state-change progress]))
                        :ds-conn dc
                        :transact-batch-size 2000

                        :pre-fetch-datoms [{:type    :pull
                                            :pattern '[*]
                                            :ids      [example-meme-id]}]

                        })))
