(ns effects-playground.inject
  "Injecting all dependencies"
  (:require [effects-playground.effects :as fx]))

(defn create-user*
  [validate-fn write-fn send-fn user]
  (if-let [user (validate-fn user)]
    (do
      (write-fn (:id user) user)
      (send-fn "user.created" {:user user})
      (send-fn "response" :ok))
    (send-fn "response" :invalid)))

(def create-user
  (partial create-user*
           fx/user-validate!
           fx/db-write!
           fx/message-send!))

(comment
  (create-user {:id 1234 :name "George"})
  )


;; what tests look like
(require '[clojure.test :refer [deftest testing is]])

(deftest create-user-test
  (testing "valid user is created"
    (let [effects (atom [])
          user {:id 1234 :name "George"}
          validate-fn identity
          write-fn #(swap! effects conj (into [:db-write] %&))
          send-fn #(swap! effects conj (into [:message-send] %&))]
      (create-user* validate-fn
                    write-fn
                    send-fn
                    user)
      (is (= [[:db-write 1234 user]
              [:message-send "user.created" {:user user}]
              [:message-send "response" :ok]]
             @effects))))
  (testing "invalid user results in an error"
    (let [effects (atom [])
          user {:name "George"}
          validate-fn (constantly nil)
          write-fn #(swap! effects conj (into [:db-write] %&))
          send-fn #(swap! effects conj (into [:message-send] %&))]
      (create-user* validate-fn
                    write-fn
                    send-fn
                    user)
      (is (= [[:message-send "response" :invalid]]
             @effects)))))
