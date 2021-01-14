(ns effects-playground.train.core
  "Train reservation system example")

;; Roughly translated from Haskell
;; https://github.com/QuentinDuval/HaskellTrainReservationKata/blob/7b265c918eb13cd4b48214d51c6274156fe1ce4e/src/ReservationApi.hs

(def train-max-occupancy 0.7)
(def coach-max-occupancy 0.8)

(def mock-trains
  {;; full
   :T1 {:coaches {:A {:seat-count 100, :available-seats (range 71 100)}
                  :B {:seat-count 100, :available-seats (range 71 100)}}}
   ;; first coach full
   :T2 {:coaches {:A {:seat-count 100, :available-seats (range 80 100)}
                  :B {:seat-count 100, :available-seats (range 50 100)}}}
   ;; lots of empty
   :T3 {:coaches {:A {:seat-count 100, :available-seats (range 20 100)}
                  :B {:seat-count 100, :available-seats (range 1 100)}}}})

(defn coach-occupancy [coach]
  (let [total-seats (:seat-count coach)
        free-seats (count (:available-seats coach))]
    {:occupied-seats (max 0 (- total-seats free-seats))
     :total-seats total-seats}))

(defn train-occupancy [train]
  (reduce (partial merge-with +)
          (map coach-occupancy (vals (:coaches train)))))

(defn train-typologies [[train-id train]]
  (for [[coach-id coach] (:coaches train)]
    [train-id coach-id coach]))

(defn coach->reservation [seats-requested [train-id, coach-id, coach]]
  {:train-id train-id
   :coach-id coach-id
   :seat-numbers (->> (:available-seats coach)
                      (sort)
                      (take seats-requested)
                      (apply sorted-set))})

(defn projected-occupancy [seats-requested occupancy]
  (/ (+ (:occupied-seats occupancy) seats-requested)
     (:total-seats occupancy)))

(defn reservations-by-decreasing-preference [seats-requested trains]
  (let [free-trains (filter (comp #(<= % train-max-occupancy)
                                  (partial projected-occupancy seats-requested)
                                  train-occupancy
                                  val)
                            trains)
        all-coaches (mapcat train-typologies free-trains)
        valid-coaches (filter (comp #(<= % 1)
                                    (partial projected-occupancy seats-requested)
                                    coach-occupancy
                                    last)
                              all-coaches)
        coach-priority (sort-by (comp (partial projected-occupancy seats-requested)
                                      coach-occupancy
                                      last)
                                valid-coaches)]
    (map (partial coach->reservation seats-requested) coach-priority)))
