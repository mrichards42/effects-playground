(ns effects-playground.mount
  "Using mount to start effects"
  (:require [effects-playground.effects :as fx]
            [mount.core :as mount :refer [defstate]]))

;; global state
(defstate user-db :start (atom {}))
(defstate rmq :start (atom {}))
(defstate validator :start fx/validator)

;; effects using global state
(defstate db-write! :start (partial fx/db-write! user-db))
(defstate message-send! :start (partial fx/message-send! rmq))
(defstate user-validate! :start (partial fx/user-validate! validator))


(defn create-user [user]
  (if-let [user (user-validate! user)]
    (do
      (db-write! (:id user) user)
      (message-send! "user.created" {:user user})
      (message-send! "response" :ok))
    (message-send! "response" :invalid)))

(comment
  (mount/start)
  (create-user {:id 1234 :name "George"})

  (deref user-db)
  (clojure.pprint/pprint (deref rmq))
  )

;; what tests might like
(require '[clojure.test :refer [deftest testing is]])

(deftest create-user-test
  (testing "valid user is created"
    (let [effects (atom [])
          user {:id 1234 :name "George"}]
      (mount/start-with {#'db-write! #(swap! effects conj (into [:db-write] %&))
                         #'message-send! #(swap! effects conj (into [:message-send] %&))
                         #'user-validate! identity})
      (create-user user)
      (is (= [[:db-write 1234 user]
              [:message-send "user.created" {:user user}]
              [:message-send "response" :ok]]
             @effects)))
    (mount/stop))
  (testing "invalid user results in an error"
    (let [effects (atom [])
          user {:name "George"}]
      (mount/start-with {#'db-write! #(swap! effects conj (into [:db-write] %&))
                         #'message-send! #(swap! effects conj (into [:message-send] %&))
                         #'user-validate! (constantly nil)})
      (create-user user)
      (is (= [[:message-send "response" :invalid]]
             @effects))
      (mount/stop))))
