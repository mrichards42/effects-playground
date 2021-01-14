(ns effects-playground.condition
  "Conditions-based effects -- imperative, but perhaps easier to mock?"
  (:require [effects-playground.effects :as fx]
            [special.core :as special :refer [condition]]))

(def prod-handlers
  {:db/write! #(apply fx/db-write! %)
   :message/send! #(apply fx/message-send! %)
   :user/validate! #(apply fx/user-validate! %)})

(defn create-user [user]
  (if-let [user (condition :user/validate! [user])]
    (do
      (condition :db/write! [(:id user) user])
      (condition :message/send! ["user.created" {:user user}])
      (condition :message/send! ["response" :ok]))
    (condition :message/send! ["response" :invalid])))

(comment
  (let [create-user' (apply special/manage create-user (apply concat prod-handlers))]
    (create-user' {:id 1234 :name "George"}))
  )


;; what tests look like
(require '[clojure.test :refer [deftest testing is]])

(def test-handlers
  {:db/write! (fn [[_id user]] user)
   :message/send! (constantly nil)
   :user/validate! (fn [[user]] user)})

(defn trace-fx-middleware [effects-atom fn-name]
  (fn [handler]
    (fn [args]
      (swap! effects-atom conj (into [fn-name] args))
      (handler args))))

(defn capture-effects [f & {:as handlers}]
  (let [effects (atom [])
        restarts (merge test-handlers handlers)
        restarts (reduce (fn [m k]
                           (update m k (trace-fx-middleware effects k)))
                         restarts (keys restarts))
        managed (apply special/manage f (apply concat restarts))]
    (fn [& args]
      (apply managed args)
      @effects)))

(deftest create-user-test
  (testing "valid user is created"
    (let [user {:id 1234 :name "George"}
          test-create-user (capture-effects create-user)]
      (is (= [[:user/validate! user]
              [:db/write! 1234 user]
              [:message/send! "user.created" {:user user}]
              [:message/send! "response" :ok]]
             (test-create-user user)))))
  (testing "invalid user results in an error"
    (let [user {:name "George"}
          test-create-user (capture-effects create-user
                                            :user/validate! (constantly nil))]
        (is (= [[:user/validate! user]
                [:message/send! "response" :invalid]]
               (test-create-user user))))))
