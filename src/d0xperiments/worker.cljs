(ns d0xperiments.worker
  (:require [ajax.core :refer [ajax-request] :as ajax]
            [ajax.edn :as ajax-edn]
            [d0xperiments.utils :refer [uncompress-facts]]))

(.log js/console "Worker created")

(defmulti process-message :fn)

(defmethod process-message :download-snapshot
  [{:keys [args]} callback]
  (let [url (first args)]
    (.log js/console "Downloading " url)
    (ajax-request {:method          :get
                   :uri             (str url "/db")
                   :timeout         30000
                   :response-format (ajax/raw-response-format)
                   :handler (fn [[ok? res] result]
                              (if ok?

                                (callback true (cljs.reader/read-string res))

                                (callback false res)))})))

(defmethod process-message :default
  [x]
  (.error js/console "No process message registered in worker for " x))

(set! (.-onmessage js/self)
      (fn [e]
        (.log js/console "Worker got message " (.-data e))
        (let [ev-data (js->clj (.-data e) :keywordize-keys true)]
          (process-message (-> ev-data
                               (update :fn keyword))
                           (fn [ok? result]
                             (if ok?
                              (.postMessage js/self (clj->js {:result result
                                                              :id (:id ev-data)}
                                                             :keyword-fn #(if-let [ns (namespace %)]
                                                                            (str ns "/" (name %))
                                                                            (name %))))
                              (do (.error js/console "Worker process-message error " (clj->js [ev-data result]))
                                  (.postMessage js/self #js {:error true
                                                             :id (:id ev-data)}))))))))
