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
                                          store (.createObjectStore db "facts" #js {:keyPath "factId" :autoIncrement true})]
                                      (.createIndex store "blockNum" "blockNum" #js {:unique false})
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
        facts-store (.objectStore transaction "facts")
        put-next (fn put-next [[x & r]]
                   (when x
                     (let [t (.add facts-store (build-indexed-db-fact x))]
                       (set! (.-onsuccess t) (partial put-next r))
                       (set! (.-onerror t) (fn [& args] (.log js/console args))))))]
    (put-next facts)))

(defn every-store-fact-ch []
  (let [out-ch (async/chan)
        transaction (.transaction @facts-db #js ["facts"] "readonly")
        facts-store (.objectStore transaction "facts")
        all-facts-req (.getAll facts-store)]
    (set! (.-onsuccess all-facts-req)
          (fn [e]
            (->> e
                .-target
                .-result
                (map (fn [c]
                       {:entity    (-> c .-entity)
                        :attribute (keyword (-> c .-attribute))
                        :value     (-> c .-value)
                        :add       (-> c .-add)
                        :block-num (-> c .-blockNum)}))
                (async/put! out-ch))))
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
  (let [out-ch (async/chan)
        transaction (.transaction @facts-db #js ["facts"] "readonly")
        facts-store (.objectStore transaction "facts")
        block-num-index (.index facts-store "blockNum")
        oc-req (.openCursor block-num-index nil "prev")]

    (set! (.-onsuccess oc-req)
          (fn [ev]
            (let [max-block-num (-> ev .-target .-result .-value .-blockNum)]
              (async/put! out-ch max-block-num))))
    out-ch))

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
  [:ul
   [:li (str "Entities count: " (->> @(re-frame/subscribe [::entities-count]) first first))]
   [:li (str "Memes count: " (->> @(re-frame/subscribe [::attr-count :reg-entry/address]) first first))]
   [:li (str "Challenges count: " (->> @(re-frame/subscribe [::attr-count :reg-entry/challenge]) first first))]
   [:li (str "Votes count: " (->> @(re-frame/subscribe [::attr-count :challenge/vote]) first first))]
   [:li (str "Votes reveals count: " (->> @(re-frame/subscribe [::attr-count :vote/revealed-on]) first first))]
   [:li (str "Votes reclaims count: " (->> @(re-frame/subscribe [::attr-count :vote/reclaimed-reward-on]) first first))]
   [:li (str "Tokens count: " (->> @(re-frame/subscribe [::attr-count :token/id]) first first))]
   [:li (str "Auctions count: " (->> @(re-frame/subscribe [::attr-count :auction/token-id]) first first))]])

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
  (let [app-state @(re-frame/subscribe [::app-state])]
    [:div
     (case app-state
       :downloading-facts [:div "Downloading app data, please wait..."]
       :installing-facts  [:div "Installing app"]
       :ready [:div
               [hud]
               [meme-factory-search]]
       [:div])]))


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
                   :handler (fn [[ok? res] result]
                              (if ok?
                                (let [res-map (cljs.reader/read-string res)]
                                  (async/put! out-ch res-map))
                                (async/close! out-ch)))})
    out-ch))

;; progress-states
;; {:satate :downloading-facts}
;; {:satate :installing-facts}
;; {:satate :ready}
;; for 9000 blocks / 61230 facts
;; 26 sec full install
;; 4,8 sec snapshot download + install (~800Kb)
;; 3,6 sec from IndexedDB


(defn start [{:keys [progress-callback provider-url preindexer-url facts-db-address]}]
  (set! js/web3js (js/Web3. (or (.-givenProvider js/web3) provider-url)))
  (println "STARTING:" (.getTime (js/Date.)))
  (async/go
    (try
      (let [last-block-so-far (atom 0)]
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
                  idb-facts (->> (<? (every-store-fact-ch))
                                 (mapv fact->ds-fact))
                  ds-db-conn (d/create-conn)]
              (println "We have facts on IndexedDB. Last stored block number is " last-stored-bn)
              (reset! last-block-so-far last-stored-bn)
              (install-datascript-db! ds-db-conn)
              (d/transact! ds-db-conn idb-facts))

            ;; NO IndexDB facts

            (let [_ (println "We DON'T have IndexedDB facts, lets try to load a snapshot")
                  {:keys [db last-seen-block]} (<? (load-db-snapshot preindexer-url))]
              (if db
                (do
                  (reset! last-block-so-far last-seen-block)
                  (println "we have a snapshot, installing it")
                  (install-datascript-db! (d/conn-from-db db))
                  (store-facts (->> (d/datoms db :eavt)
                                    (map (fn [{:keys [e a v block-num]}]
                                           {:entity e :attribute a :value v :block-num block-num})))))
                (println "We couldn't download a snapshot"))))

          ;; we already or got facts from IndexedDB, downloaded a snapshot, or we don't have anything
          (println "Let's sync the remainning facts directly from the browser. Last block seen " @last-block-so-far)
          (when-not @db-conn (install-datascript-db! (d/create-conn)))

          (progress-callback {:state :downloading-facts})

          (let [past-facts (<? (get-past-events js/web3js facts-db-address @last-block-so-far))]
            (progress-callback {:state :installing-facts})
            (d/transact! @db-conn (mapv fact->ds-fact past-facts))
            (store-facts past-facts)))

        (println "New facts listener installed")
        (progress-callback {:state :ready})
        (println "DONE:" (.getTime (js/Date.)))

        ;; keep listening to new facts and transacting them to datascript db
        (let [new-facts-ch (install-facts-filter! js/web3js facts-db-address)]
          (loop [nf (<? new-facts-ch)]
            (d/transact! @db-conn [(fact->ds-fact nf)])
            (recur (<? new-facts-ch)))))
      (catch js/Error e (.log js/console e)))))

(defn ^:export init []
  (mount-root)
  (start {:provider-url "ws://localhost:8549/"
          :preindexer-url "http://localhost:1234"
          :facts-db-address "0x360b6d00457775267aa3e3ef695583c675318c05"
          :progress-callback (fn [{:keys [state] :as progress}]
                               (re-frame/dispatch [:app-state-change state]))}))
