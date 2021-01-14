(ns effects-playground.fx-function
  "Injecting just the `fx!` function"
  (:require [effects-playground.effects :as fx]))

(def prod-fx
  {:db/write! fx/db-write!
   :message/send! fx/message-send!
   :user/validate! fx/user-validate!})

(defn fx! [fx-map k & args]
  (apply (get fx-map k) args))

(defn create-user* [fx! user]
  (if-let [user (fx! :user/validate! user)]
    (let [id (:id user)
          after (fx! :db/write! id user)]
      (fx! :message/send! "user.created" {:user after})
      (fx! :message/send! "response" :ok))
    (fx! :message/send! "response" :invalid)))

(def create-user (partial create-user* (partial fx! prod-fx)))

(comment
  (create-user {:id 1234 :name "George"})
  )


;; tracing machinery

(defn wrap-trace [state fx!]
  (fn [& args]
    (swap! state conj {:fx args})
    (let [result (apply fx! args)]
      (swap! state #(conj (pop %) (assoc (peek %) :result result)))
      result)))

(defn traced-fn [f fx!]
  (let [effects (atom [])
        fx! (wrap-trace effects fx!)]
    (fn [& args]
      (let [result (apply f fx! args)]
        {:input args, :result result, :trace @effects}))))

(defn fx-steps [trace]
  (->> trace
       :trace
       (map :fx)))


;; what tests might look like
(require '[clojure.test :refer [deftest testing is]])

(def test-fx
  {:db/write! (fn [_id user] user)
   :message/send! (constantly nil)
   :user/validate! (fn [user] user)})

(deftest create-user-test
  (testing "valid user is created"
    (let [user {:id 1234 :name "George"}
          traced-create (traced-fn create-user* (partial fx! test-fx))]
      (is (= [[:user/validate! user]
              [:db/write! 1234 user]
              [:message/send! "user.created" {:user user}]
              [:message/send! "response" :ok]]
             (fx-steps (traced-create user))))))
  (testing "invalid user results in an error"
    (let [user {:id 1234 :name "George"}
          fx-map (merge test-fx {:user/validate! (constantly nil)})
          traced-create (traced-fn create-user* (partial fx! fx-map))]
      (is (= [[:user/validate! user]
              [:message/send! "response" :invalid]]
             (fx-steps (traced-create user)))))))


;; Plus neat stuff like snapshot testing with record + replay
;; https://github.com/graninas/automatic-whitebox-testing-showcase

(defn replay-fx [f recording]
  (let [recorded-fx (atom (:trace recording))
        fx! (fn [& args]
              (let [{:keys [fx result]} (first @recorded-fx)
                    step (- (count (:trace recording))
                            (dec (count @recorded-fx)))]
                (or (is (= fx args))
                    (throw (ex-info "Broken recording!"
                                    {:recording recording
                                     :step step
                                     :expected args
                                     :actual fx})))
                (swap! recorded-fx rest)
                result))
        result (apply f fx! (:input recording))]
    (is (= result (:result recording)))))

(comment
  (def success-recording
    ((traced-fn create-user* prod-fx)
     {:id 1234 :name "George"}))

  (deftest create-user-test
    ;; works with a real recording
    (testing "actual recording"
      (replay-fx create-user* success-recording))
    ;; works with a made-up recording
    (testing "made up recording"
      (replay-fx create-user*
                 {:input [{:name "George"}] ;; no id, so this fails validation
                  :result nil
                  :trace [{:fx [:user/validate! {:name "George"}] :result nil}
                          {:fx [:message/send! "response" :invalid] :result nil}]})))
  )
