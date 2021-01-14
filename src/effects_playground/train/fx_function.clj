(ns effects-playground.train.fx-function
  (:require [clojure.pprint]
            [effects-playground.train.core :as train]
            [effects-playground.train.effects :as fx]))

(def prod-fx
  {:train/search fx/search-trains!
   :train/find fx/find-train!
   :reservation/place fx/place-reservation!
   :log/info fx/log!})

(defn fx! [fx-map k & args]
  (apply (get fx-map k) args))

(defn reserve* [fx! request]
  (let [train-ids (fx! :train/search (:date request))
        trains (keep (partial fx! :train/find) train-ids)
        reservations (train/reservations-by-decreasing-preference
                      (:seat-count request)
                      trains)]
    (if (seq reservations)
      (fx! :log/info (str "Valid reservations (by preference):\n"
                          (with-out-str (clojure.pprint/pprint reservations))))
      (fx! :log/info "No reservations available!"))
    (some (partial fx! :reservation/place) reservations)))

(def reserve (partial reserve* (partial fx! prod-fx)))

(comment
  (reserve {:seat-count 10})
  )

;; testing

(require '[clojure.test :refer [deftest testing is]])
(require '[effects-playground.fx-function
           :refer [fx! traced-fn fx-steps replay-fx]])

(def test-fx
  {:train/search (fn [_date] (keys train/mock-trains))
   :train/find (fn [id] (find train/mock-trains id))
   :reservation/place identity
   :log/info (constantly nil)})

(deftest reserve-test
  (testing "valid reservation works"
    (let [reservation {:seat-count 10, :date #inst "2020-01-01"}
          trace ((traced-fn reserve* (partial fx! test-fx))
                 reservation)]
      (is (= [[:train/search #inst "2020-01-01"]
              [:train/find :T1]
              [:train/find :T2]
              [:train/find :T3]
              [:reservation/place {:train-id :T3
                                   :coach-id :B
                                   :seat-numbers #{1 2 3 4 5 6 7 8 9 10}}]]
             (->> (fx-steps trace)
                  (remove (comp #{:log/info} first)))))))
  (testing "reservation with no trains does nothing else"
    (let [reservation {:seat-count 10, :date #inst "2020-01-01"}
          fx-map (assoc test-fx :train/search (constantly []))
          trace ((traced-fn reserve* (partial fx! fx-map))
                 reservation)]
      (is (= [[:train/search #inst "2020-01-01"]]
             (->> (fx-steps trace)
                  (remove (comp #{:log/info} first)))))))
  (testing "reservations are attempted until one is available"
    (let [reservation {:seat-count 10, :date #inst "2020-01-01"}
          ;; don't allow reserving T3
          fx-map (assoc test-fx :reservation/place
                        #(if (= :T3 (:train-id %))
                           false
                           %))
          trace ((traced-fn reserve* (partial fx! fx-map))
                 reservation)]
      (is (= [[:train/search #inst "2020-01-01"]
              [:train/find :T1]
              [:train/find :T2]
              [:train/find :T3]
              ;; tries both T3 reservations first, since it's the emptiest
              [:reservation/place {:train-id :T3
                                   :coach-id :B
                                   :seat-numbers (set (range 1 11))}]
              [:reservation/place {:train-id :T3
                                   :coach-id :A
                                   :seat-numbers (set (range 20 30))}]
              ;; then moves to T2 (the next emptiest)
              [:reservation/place {:train-id :T2
                                   :coach-id :B
                                   :seat-numbers (set (range 50 60))}]]
             (->> (fx-steps trace)
                  (remove (comp #{:log/info} first))))))))

(comment
  ;; or recording-based
  (def success-recording
    ((traced-fn reserve* (partial fx! prod-fx))
     {:seat-count 50 :date #inst "2020-01-01"}))

  (def failure-recording
    ((traced-fn reserve* (partial fx! prod-fx))
     {:seat-count 101 :date #inst "2020-01-01"}))

  (deftest recorded-test
    (testing "successful reservation"
      (replay-fx reserve* success-recording))
    (testing "trying to reserve too many seats"
      (replay-fx reserve* failure-recording)))
  )
