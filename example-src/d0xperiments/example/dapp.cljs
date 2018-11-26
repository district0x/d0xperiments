(ns ^:figwheel-hooks d0xperiments.example.dapp
  (:require [datascript.core :as d]
            [bignumber.core :as bn]
            [re-posh.core :as re-posh]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [posh.reagent :as posh]
            [clojure.string :as str]
            [goog.string :as gstring]
            [district0x-facts-db.browser-installer :as installer]
            [web3.impl.ethers-js :refer [make-ethers-js]]
            [web3.core :as web3-core]
            [cljs.spec.alpha :as s]
            [cljs.spec.test.alpha]
            [expound.alpha :as expound])
  (:require-macros [district0x-facts-db.utils :refer [slurpf]]))


(enable-console-print!)

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
 (fn [db [_ {:keys [state] :as prog}]]
   (assoc db :app-state prog)))

;; (re-frame/reg-event-fx
;;  ::approve-and-create-meme
;;  (fn [{:keys [db]} [_ data deposit {:keys [Name Hash Size] :as meme-meta}]]
;;    (let [tx-id (str (random-uuid))
;;          tx-name "Approve and create meme"
;;          active-account (account-queries/active-account db)
;;          extra-data (web3-eth/contract-get-data (contract-queries/instance db :meme-factory)
;;                                                 :create-meme
;;                                                 active-account
;;                                                 Hash
;;                                                 (bn/number (:issuance data)))]
;;      {:dispatch [::tx-events/send-tx {:instance (contract-queries/instance db :DANK)
;;                                       :fn :approve-and-call
;;                                       :args [(contract-queries/contract-address db :meme-factory)
;;                                              deposit
;;                                              extra-data]
;;                                       :tx-opts {:from active-account
;;                                                 :gas 6000000}
;;                                       :tx-id {:meme/create-meme tx-id}
;;                                       :tx-log {:name tx-name}
;;                                       :on-tx-success-n [[::logging/info (str tx-name " tx success") ::create-meme]
;;                                                         [::notification-events/show (gstring/format "Meme created with meta hash %s" Hash)]]
;;                                       :on-tx-error [::logging/error (str tx-name " tx error") {:user {:id active-account}
;;                                                                                                :deposit deposit
;;                                                                                                :data data
;;                                                                                                :meme-meta meme-meta} ::create-meme]}]})))



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

(defonce w3 (atom nil))

(defn ^:export init []
  (cljs.spec.test.alpha/instrument)
  (set! cljs.spec.alpha/*fspec-iterations* 0)
  (set! (.-onerror js/window) (fn [& [_ _ _ _ err :as all]]
                                (let [ed (ex-data err)]
                                  (if (and (map? ed) (:cljs.spec.alpha/problems ed))
                                    (do (.error js/console (ex-message err))
                                        (s/explain-out ed)
                                        #_(expound/printer ed))
                                    (.error js/console (.-message err) err)))))

  (let [dc (d/create-conn datascript-schema)
        provider (if js/web3
                   (new js/ethers.providers.Web3Provider js/web3.currentProvider)
                   (new js/ethers.providers.JsonRpcProvider "http://localhost:8549"))
        web3 (make-ethers-js (.getSigner provider) provider)]
    (reset! w3 web3)
    (install-datascript-db! dc)
    (mount-root)

    (installer/install {:web3 web3
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
