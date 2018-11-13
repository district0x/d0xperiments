(ns d0xperiments.utils)

(defn compress-facts [facts]
  (reduce (fn [cfs [e a v]]
            (update cfs a conj [e v]))
   {}
   facts))

(defn uncompress-facts [facts]
  (reduce (fn [fs [a evs]]
            (into fs
                  (reduce (fn [r [e v]]
                            (conj r [e a v]))
                   []
                   evs)))
   []
   facts))
