(ns ^:figwheel-hooks d0xperiments.example.dapp
  (:require [d0xperiments.core :refer [install-facts-filter! get-block-number get-past-events]]
            [datascript.core :as d]
            [bignumber.core :as bn]
            [re-posh.core :as re-posh]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [day8.re-frame.http-fx]
            [ajax.core :as ajax]
            [clojure.string :as str]
            [goog.string :as gstring]
            [clojure.core.async :as async]
            [ajax.core :refer [ajax-request]])
  (:require-macros [d0xperiments.utils :refer [<?]]))

;; TODO Add chema here!!!

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

(defn init-indexed-db! []
  (let [req (js/window.indexedDB.open "FactsDB")
        out-ch (async/chan)]
    (set! (.-onupgradeneeded req) (fn [e]
                                    (let [db (-> e .-target .-result)
                                          store (.createObjectStore db "facts" #js {:keyPath "entity"})]
                                      (set! (-> store .-transaction .-oncomplete)
                                            (fn [oce]
                                              (reset! facts-db db))))))
    (set! (.-onsuccess req) (fn [e]
                              (let [db (-> e .-target .-result)]
                                (reset! facts-db db)
                                (async/put! out-ch true))))
    out-ch))

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

(defn every-store-fact-ch []
  (let [out-ch (async/chan)
        transaction (.transaction @facts-db #js ["facts"] "readonly")
        facts-store (.objectStore transaction "facts")
        all-facts-cursor (.openCursor facts-store)]
    (set! (.-onsuccess all-facts-cursor)
          (fn [e]
            (let [c (-> e .-target .-result)]
              (when c
                (async/put! out-ch {:entity    (-> c .-value .-entity)
                                    :attribute (-> c .-value .-attribute)
                                    :value     (-> c .-value .-value)
                                    :add       (-> c .-value .-add)
                                    :block-num (-> c .-value .-blockNum)})
                (.continue c)))))
    out-ch))

(defn get-store-facts-count []
  (let [out-ch (async/chan)
        transaction (.transaction @facts-db #js ["facts"] "readonly")
        facts-store (.objectStore transaction "facts")
        count-req (.count facts-store)]
    (set! (.-onsuccess count-req)
          (fn [ev]
            (let [facts-count (-> ev .-target .-result)]
              (async/put! out-ch facts-count))))
    out-ch))

(defn last-stored-block-number []
  (let [out-ch (async/chan)]
    ;; TODO Implement this
    (async/put! out-ch 0)
    out-ch))

;;;;;;;;;;;;
;; Events ;;
;;;;;;;;;;;;

#_(def facts-stop-watch-mark 59640)

#_(re-frame/reg-event-fx
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

(re-frame/reg-cofx
 ::datascript-db
 (fn [cofxs]
   (assoc cofxs ::datascript-db @@db-conn)))



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

(defn wait-for-load []
  (let [out-ch (async/chan)]
    (.addEventListener js/window "load" #(async/put! out-ch true))
    out-ch))

(defn install-datascript-db! [conn]
  (reset! db-conn conn)
  (re-posh/connect! conn))

(defn fact->ds-fact [{:keys [entity attribute value]}]
  [:db/add entity attribute value])

(defn load-db-snapshot [url]
  (let [out-ch (async/chan)]
    (ajax-request {:method          :get
                   :uri             url
                   :timeout         30000
                   :response-format (ajax/raw-response-format)
                   :handler (fn [[ok? err] result]
                              (if ok?
                               (let [res-map (cljs.reader/read-string result)]
                                 (async/put! out-ch res-map))
                               (async/close! out-ch)))})
    out-ch))

(defn calc-progress [init end current]
  )

;; progress-states
;; {:satate :downloading-facts}
;; {:satate :installing-facts :progress 100}
;; {:satate :ready}

(defn start [{:keys [progress-callback provider-url preindexer-url facts-db-address]}]
  (set! js/web3js (js/Web3. (or (.-givenProvider js/web3) provider-url)))

  (async/go
    (let [last-block-so-far (atom 8000)]
      (<? (wait-for-load))
      (println "Page loaded")

      (<? (init-indexed-db!))
      (println "IndexedDB initialized")

      (let [current-block-number (<? (get-block-number))
            idb-facts-count (<? (get-store-facts-count))]

        (println "Current block number is " current-block-number)
        (println "IndexedDB contains " idb-facts-count "facts")

        (if (pos? idb-facts-count)
          (let [last-stored-bn (<? (last-stored-block-number))
                idb-facts (->> (<? (async/into [] (every-store-fact-ch)))
                               (mapv fact->ds-fact))
                ds-db-conn (d/create-conn)]

            (println "We have facts on IndexedDB")

            (install-datascript-db! ds-db-conn)
            (d/transact! ds-db-conn idb-facts))

          (let [{:keys [db last-seen-block]} (<? (load-db-snapshot preindexer-url))]
            (println "We DON'T have IndexedDB facts, lets try to load a snapshot")
            (if db
              (do
                (reset! last-block-so-far last-seen-block)
                (println "we have a snapshot, installing it")
                (install-datascript-db! (d/conn-from-db db))
                (store-facts (->> (d/datoms db :eavt)
                                  (map (fn [{:keys [e a v]}]
                                         ;; HACK since we don't have block numbers in snapshot and
                                         ;; don't want to add them to keep the snapshot as small as posible
                                         {:entity e :attribute a :value v :block-num last-seen-block})))))
              (println "We couldn't download a snapshot"))))

        ;; we already or got facts from IndexedDB, downloaded a snapshot, or we don't have anything
        (println "Let's sync the remainning facts directly from the browser")
        (when-not @db-conn (install-datascript-db! (d/create-conn)))

        (progress-callback {:state :downloading-facts})

        (let [past-facts (<? (get-past-events js/web3js facts-db-address @last-block-so-far))]
          (progress-callback {:state :installing-facts})
          (d/transact! @db-conn (mapv fact->ds-fact past-facts))
          (store-facts past-facts))

        ;; Reporting is 50% slower than transacting facts all together
        #_(let [past-facts (<? (get-past-events js/web3js facts-db-address @last-block-so-far))]
          (doseq [pf past-facts]
            (progress-callback {:state :installing-facts :progress (calc-progress @last-block-so-far
                                                                                  current-block-number
                                                                                  (:block-num pf))})
            (d/transact! @db-conn [(fact->ds-fact pf)])
            (store-fact pf))))


      ;; mount reagent component
      (mount-root)

      ;; keep listening to new facts and transacting them to datascript db
      (let [new-facts-ch (install-facts-filter! js/web3js facts-db-address)]
        (loop [nf (<? new-facts-ch)]
          (d/transact! @db-conn (fact->ds-fact nf))
          (recur (<? new-facts-ch))))
      (println "New facts listener installed")
      (progress-callback {:state :ready}))))

(defn ^:export init []

  (start {:provider-url "ws://localhost:8549/"
          :preindexer-url "http://localhost:1234"
          :facts-db-address "0x360b6d00457775267aa3e3ef695583c675318c05"
          :progress-callback (fn [{:keys [state] :as progress}]
                               (println progress))}))
