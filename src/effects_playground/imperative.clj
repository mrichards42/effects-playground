(ns effects-playground.imperative
  "Straightforward imperative effects without real isolation"
  (:require [effects-playground.effects :as fx]))

(defn create-user [user]
  (if-let [user (fx/user-validate! user)]
    (do
      (fx/db-write! (:id user) user)
      (fx/message-send! "user.created" {:user user})
      (fx/message-send! "response" :ok))
    (fx/message-send! "response" :invalid)))

(comment
  ;; create the user
  (create-user {:id 1234 :name "George"})
  ;; observe changes
  (clojure.pprint/pprint (deref fx/db))
  (clojure.pprint/pprint (deref fx/broker))
  )


;; what tests look like
(require '[clojure.test :refer [deftest testing is]])

(deftest create-user-test
  (testing "valid user is created"
    (let [effects (atom [])
          user {:id 1234 :name "George"}]
      (with-redefs [fx/user-validate! identity
                    fx/db-write! #(swap! effects conj (into [:db-write] %&))
                    fx/message-send! #(swap! effects conj (into [:message-send] %&))]
        (create-user user)
        (is (= [[:db-write 1234 user]
                [:message-send "user.created" {:user user}]
                [:message-send "response" :ok]]
               @effects)))))
  (testing "invalid user results in an error"
    (let [effects (atom [])
          user {:name "George"}]
      (with-redefs [fx/user-validate! (constantly nil)
                    fx/db-write! #(swap! effects conj (into [:db-write] %&))
                    fx/message-send! #(swap! effects conj (into [:message-send] %&))]
        (create-user user)
        (is (= [[:message-send "response" :invalid]]
               @effects))))))
