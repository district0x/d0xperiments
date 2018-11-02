(ns d0xperiments.indexeddb
  (:require [clojure.core.async :as async]))

(defonce facts-db (atom nil))

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
