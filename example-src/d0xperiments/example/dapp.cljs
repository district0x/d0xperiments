(ns ^:figwheel-hooks d0xperiments.example.dapp
  (:require [d0xperiments.core :refer [install-facts-filter]]
            [datascript.core :as d]
            [bignumber.core :as bn]
            [re-posh.core :as re-posh]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [day8.re-frame.http-fx]
            [ajax.core :as ajax]
            [clojure.string :as str]
            [goog.string :as gstring]))

;; TODO Add chema here!!!
(def facts-db-address "0x360b6d00457775267aa3e3ef695583c675318c05")
(def preindexer-url "http://localhost:1234")
(def default-provider-url "ws://localhost:8549/")

(defonce db-conn (atom nil))
(defonce facts-db (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Initialization process goes like this :                                                                    ;;
;;                                                                                                            ;;
;; - Check indexedDB for facts                                                                                ;;
;;      - HAVE facts, create a datascript-db and transact them in one transaction -> [::install-facts-filter] ;;
;;      - NO facts, try to get a snapshot from preindexer-url                                                 ;;
;;            - HAVE snapshot, create a datascript-db from serialized one -> [::install-facts-filter]         ;;
;;            - NO snapshot, build empty db -> [::replay-past-events] -> [::install-facts-filter]             ;;
;;                                                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;
;; IndexedDB ;;
;;;;;;;;;;;;;;;

(defn init-db [ready-callback]
  (let [req (js/window.indexedDB.open "FactsDB")]
    (set! (.-onupgradeneeded req) (fn [e]
                                    (let [db (-> e .-target .-result)
                                          store (.createObjectStore db "facts" #js {:keyPath "entity"})]
                                      (set! (-> store .-transaction .-oncomplete)
                                            (fn [oce]
                                              (reset! facts-db db))))))
    (set! (.-onsuccess req) (fn [e]
                              (let [db (-> e .-target .-result)]
                                (reset! facts-db db)
                                (ready-callback))))))

(defn build-indexed-db-fact [{:keys [entity attribute value add block-num]}]
  (let [attr-ns (namespace attribute)
        attr-name (name attribute)]
    #js {:entity    entity
         :attribute (str attr-ns "/" attr-name)
         :value     value
         :add       add
         :blockNum  block-num}))

(defn store-fact [fact]
  (let [transaction (.transaction @facts-db #js ["facts"] "readwrite")
        facts-store (.objectStore transaction "facts")]
    (.add facts-store (build-indexed-db-fact fact))))

(defn store-facts [facts]
  (let [transaction (.transaction @facts-db #js ["facts"] "readwrite")
        facts-store (.objectStore transaction "facts")]
    (doseq [fact facts]
      (.add facts-store (build-indexed-db-fact fact)))))

(defn on-every-store-fact [callback-fn]
  (let [transaction (.transaction @facts-db #js ["facts"] "readonly")
        facts-store (.objectStore transaction "facts")
        all-facts-cursor (.openCursor facts-store)]
    (set! (.-onsuccess all-facts-cursor)
          (fn [e]
            (let [c (-> e .-target .-result)]
              (when c
                (callback-fn {:entity    (-> c .-value .-entity)
                              :attribute (-> c .-value .-attribute)
                              :value     (-> c .-value .-value)
                              :add       (-> c .-value .-add)
                              :block-num (-> c .-value .-blockNum)})
                (.continue c)))))))

;;;;;;;;;;;;
;; Events ;;
;;;;;;;;;;;;

(re-frame/reg-event-fx
 ::initialize
 (fn [{:keys [db ::index-db-facts?]} [_ last-block-num index-db-facts-count last-indexed-db-block]]

   (if (pos? index-db-facts-count)
     {::install-datascript-db (d/create-conn)
      ::load-and-transact-idexed-db-facts {:last-block-num last-block-num}
      :db (assoc db :sync-progress 0)
      ::mount-root true
      ::install-facts-filter nil #_{:from-block last-indexed-db-block}} ;; TODO extract the number
     {:dispatch [::load-snapshot {:last-block-num last-block-num}]})))

(re-frame/reg-event-fx
 ::load-snapshot
 (fn [cofxs [_ {:keys [last-block-num]}]]
   (.log js/console "Trying to fetch a pre indexed db")
   {:http-xhrio {:method          :get
                 :uri             preindexer-url
                 :timeout         30000
                 :response-format (ajax/raw-response-format)
                 :on-success      [::snapshot-loaded]
                 :on-failure      [::browser-full-sync {:last-block-num last-block-num}]}}))

(re-frame/reg-event-fx
 ::snapshot-loaded
 (fn [{:keys [db]} [_ raw-res]]
   (.log js/console "HEY, we got a pre indexed db, using that")
   (let [res-map (cljs.reader/read-string raw-res)
         db-conn (d/conn-from-db (:db res-map))
         last-seen-block (:last-seen-block res-map)]
     ;; This things needs to be in order????
     {::install-datascript-db db-conn
      ::store-all-facts (->> (d/datoms @db-conn :eavt)
                             (map (fn [{:keys [e a v]}]
                                    ;; HACK since we don't have block numbers in snapshot and
                                    ;; don't want to add them to keep the snapshot as small as posible
                                    {:entity e :attribute a :value v :block-num last-seen-block})))
      ::mount-root true
      :db (-> db
              (assoc :facts-counter (count (d/datoms @db-conn :eavt)))
              (assoc :last-seen-block last-seen-block)
              (assoc :sync-progress 100))
      ::install-facts-filter {:from-block (:last-seen-block res-map)}})))

(re-frame/reg-event-fx
 ::browser-full-sync
 (fn [{:keys [db]} [_ {:keys [last-block-num]}]]
   (.log js/console "We couldn't find a pre indexed db, no worries, syncing from blockchain from 0 to " last-block-num)
   {::install-datascript-db (d/create-conn)
    ::mount-root true
    ::install-facts-filter {:from-block 8000}
    :db (-> db
            (assoc :init-sync-block 8000
                   :dest-sync-block last-block-num
                   :sync-progress 0))}))

(def facts-stop-watch-mark 59640)

(re-frame/reg-event-fx
 ::add-fact
 (fn [{:keys [db]} [_ {:keys [entity attribute value block-num] :as fact} store?]]

   #_(when (= (:facts-counter db)  facts-stop-watch-mark)
     (.log js/console "Synchronized " facts-stop-watch-mark " facts in " (- (.getTime (js/Date.))
                                                                            (:filters-start-time-millis db))
           "millis"))
   (.log js/console "." store?)
   (cond-> {:transact [[:db/add entity attribute value]]
            :db (-> db
                    (assoc :sync-progress (quot (* 100 (- block-num (:init-sync-block db)))
                                                (- (:dest-sync-block db) (:init-sync-block db))))
                    (update :facts-counter (fnil inc 0))
                    (update :last-seen-block (partial (fnil max 0) block-num)))}
     store? (assoc ::indexed-db-store-fact fact))))

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
 ::install-facts-filter
 (fn [{:keys [from-block]}]
   (.log js/console "Installing facts filter from block " from-block)
   (let [filters (install-facts-filter js/web3js facts-db-address from-block
                                       (fn [fact]
                                         ;; so we give some space for the render to run and we can show progress
                                         (js/setTimeout (fn [] (re-frame/dispatch [::add-fact fact true])) 0)))]
     nil)))

(re-frame/reg-fx
 ::load-and-transact-idexed-db-facts
 (fn []
   (.log js/console "Adding facts from indexedDB")
   (on-every-store-fact #(re-frame/dispatch [::add-fact % false]))))

(re-frame/reg-fx
 ::install-datascript-db
 (fn [conn]
   (.log js/console "Installing datascript db")
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

(re-frame/reg-fx
 ::indexed-db-store-fact
 (fn [{:keys [entity attribute value block-num] :as f}]
   (store-fact f)))


;;;;;;;;;;;;;;;;;;;;
;; Subscriptions  ;;
;;;;;;;;;;;;;;;;;;;;

(re-frame/reg-sub
 ::sync-progress
 (fn [db _]
   (:sync-progress db)))


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
    #_[:li [:ul
          [:li (str "Entities count: " (->> @(re-frame/subscribe [::entities-count]) first first))]
          [:li (str "Memes count: " (->> @(re-frame/subscribe [::attr-count :reg-entry/address]) first first))]
          [:li (str "Challenges count: " (->> @(re-frame/subscribe [::attr-count :reg-entry/challenge]) first first))]
          [:li (str "Votes count: " (->> @(re-frame/subscribe [::attr-count :challenge/vote]) first first))]
          [:li (str "Votes reveals count: " (->> @(re-frame/subscribe [::attr-count :vote/revealed-on]) first first))]
          [:li (str "Votes reclaims count: " (->> @(re-frame/subscribe [::attr-count :vote/reclaimed-reward-on]) first first))]
          [:li (str "Tokens count: " (->> @(re-frame/subscribe [::attr-count :token/id]) first first))]
          [:li (str "Auctions count: " (->> @(re-frame/subscribe [::attr-count :auction/token-id]) first first))]]]]))

(defn search-item [id]
  (let [[title address] @(re-frame/subscribe [::meme id])]
    [:li (str "Title: " title " @ " address)]))

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

(defn main []
  (let [sp @(re-frame/subscribe [::sync-progress])]
    [:div
     (cond
       (zero? sp)   [:div "Downloading app data, please wait..."]
       (< 0 sp 100) [:div (gstring/format "Installing app (%d%%)" sp)]
       :else        [:div
                     [hud]
                     [meme-factory-search]])]))


 ;;;;;;;;;;
 ;; Init ;;
 ;;;;;;;;;;

(defn ^:after-load mount-root []
  (re-frame/clear-subscription-cache!)
  (reagent/render [main]
                  (.getElementById js/document "app")))

(defn ^:export init []
  (.addEventListener js/window "load"
                     (fn []

                       (.log js/console "Provider " (.-givenProvider js/web3))
                       (set! js/web3js (js/Web3. (or (.-givenProvider js/web3)
                                                     default-provider-url)))

                       (.log js/console "Web3js is " js/web3js)
                       (.log js/console "FactsContract is " )

                       (.getBlockNumber js/web3.eth
                                        (fn [err last-block-num]
                                          (.log js/console "Last block number is " last-block-num)
                                          (.log js/console "Creating db")
                                          (init-db (fn []
                                                     (let [transaction (.transaction @facts-db #js ["facts"] "readonly")
                                                           facts-store (.objectStore transaction "facts")
                                                           count-req (.count facts-store)]
                                                       (set! (.-onsuccess count-req)
                                                             (fn [ev]
                                                               (let [facts-count (-> ev .-target .-result)]
                                                                (.log js/console "Indexed db initial facts count " facts-count)
                                                                (re-frame/dispatch-sync [::initialize last-block-num facts-count]))))))))))))

(comment







  )
