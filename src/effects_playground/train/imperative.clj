(ns effects-playground.train.imperative
  (:require [clojure.pprint :refer [pprint]]
            [effects-playground.train.core :as train]
            [effects-playground.train.effects :as fx]))

(defn reserve [request]
  (let [train-ids (fx/search-trains! (:date-time request))
        trains (keep fx/find-train! train-ids)
        reservations (train/reservations-by-decreasing-preference
                      (:seat-count request)
                      trains)]
    (fx/log! (str "Valid reservations (by preference):\n"
                  (with-out-str (pprint reservations))))
    (some fx/place-reservation! reservations)))

(comment
  (reserve {:seat-count 10})
  )
