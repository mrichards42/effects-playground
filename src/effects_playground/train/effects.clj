(ns effects-playground.train.effects
  (:require [effects-playground.train.core :as train]))

(def train-db (atom train/mock-trains))

(defn try-reserve! [train-db reservation]
  (Thread/sleep 100)
  (let [{:keys [train-id coach-id seat-numbers]} reservation
        seat-path [train-id :coaches coach-id :available-seats]]
    (loop [db @train-db]
      (if (every? (set (get-in db seat-path)) seat-numbers)
        (let [db' (update-in db seat-path
                             #(->> %
                                   (remove (set seat-numbers))
                                   (apply sorted-set)))]
          (if (compare-and-set! train-db db db')
            true
            (recur @train-db)))
        false))))

(defn search-trains! [_date]
  (Thread/sleep 100)
  (keys @train-db))

(defn find-train! [id]
  (Thread/sleep 100)
  (find @train-db id))

(defn place-reservation!
  [reservation]
  (when (try-reserve! train-db reservation)
    reservation))

(defn log! [msg] (println msg))
