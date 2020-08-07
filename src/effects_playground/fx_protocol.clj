(ns effects-playground.fx-protocol
  "Protocol-based version of the fx! function"
  (:require [effects-playground.effects :as fx]))

;; protocols
(defprotocol UserValidator
  :extend-via-metadata true
  (validate-user! [this user]))

(defprotocol UserDatabase
  :extend-via-metadata true
  (write-user! [this id user]))

(defprotocol MessageQueue
  :extend-via-metadata true
  (publish! [this queue message]))

;; prod record
(defrecord ProdContext []
  UserValidator
  (validate-user! [this user] (fx/user-validate! user))
  UserDatabase
  (write-user! [this id user] (fx/db-write! id user))
  MessageQueue
  (publish! [this queue message] (fx/message-send! queue message)))


(defn create-user* [ctx user]
  (if-let [user (validate-user! ctx user)]
    (let [id (:id user)
          after (write-user! ctx id user)]
      (publish! ctx "user.created" {:user after})
      (publish! ctx "response" :ok))
    (publish! ctx "response" :invalid)))

(defn create-user [user]
  (create-user* (->ProdContext) user))

(comment
  (create-user {:id 1234 :name "George"})
  )


;; Overridable context

(defrecord TestContext [handler])

(defn test-context [fx-map]
  (let [handler (fn [_ k & args]
                  (let [f (get fx-map k)]
                    (apply f args)))
        fx-map' (reduce (fn [m k]
                          (assoc m k (fn [this & args]
                                       (apply (:handler this) this k args))))
                        fx-map
                        (keys fx-map))]
    (with-meta (->TestContext handler) fx-map')))

;; tracing middleware

(defn wrap-trace* [state handler] ;; same as fx-function
  (fn [ctx & args]
    (swap! state conj {:fx args})
    (let [result (apply handler ctx args)]
      (swap! state #(conj (pop %) (assoc (peek %) :result result)))
      result)))

(defn wrap-trace [ctx]
  (let [state (atom [])]
    (-> ctx
        (assoc :trace state)
        (update :handler #(wrap-trace* state %)))))


;; what tests might look like
(require '[clojure.test :refer [deftest testing is]])

(def test-fx
  {`write-user! (fn [_id user] user)
   `publish! (constantly nil)
   `validate-user! (fn [user] user)})

(defn traced-fn [f fx-map]
  (let [ctx (wrap-trace (test-context fx-map))]
    (fn [& args]
      (let [result (apply f ctx args)]
        {:input args, :result result, :trace @(:trace ctx)}))))

(defn fx-steps [trace]
  (->> trace
       :trace
       (map :fx)))

(deftest create-user-test
  (testing "valid user is created"
    (let [user {:id 1234 :name "George"}
          traced-create (traced-fn create-user* test-fx)]
      (is (= [[`validate-user! user]
              [`write-user! 1234 user]
              [`publish! "user.created" {:user user}]
              [`publish! "response" :ok]]
             (fx-steps (traced-create user))))))
  (testing "invalid user results in an error"
    (let [user {:id 1234 :name "George"}
          fx-map (merge test-fx {`validate-user! (constantly nil)})
          traced-create (traced-fn create-user* fx-map)]
      (is (= [[`validate-user! user]
              [`publish! "response" :invalid]]
             (fx-steps (traced-create user)))))))


;; Plus neat stuff like snapshot testing with record + replay
;; https://github.com/graninas/automatic-whitebox-testing-showcase

(defn replay-fx [f recording]
  (let [recorded-fx (atom (:trace recording))
        handler (fn [& args]
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
        fx-map (reduce (fn [m k]
                         (assoc m k (partial handler k)))
                       {}
                       (set (map (comp first :fx) (:trace recording))))
        ctx (test-context fx-map)
        result (apply f ctx (:input recording))]
    (is (= result (:result recording)))))

(comment
  (def prod-fx
    {`write-user! fx/db-write!
     `publish! fx/message-send!
     `validate-user! fx/user-validate!})

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
                  :trace [{:fx [`validate-user! {:name "George"}] :result nil}
                          {:fx [`publish! "response" :invalid] :result nil}]})))
  )
