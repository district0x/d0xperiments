(ns d0xperiments.utils
  (:require [d0xperiments.core :as core]
            [bignumber.core :as bn]))

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
