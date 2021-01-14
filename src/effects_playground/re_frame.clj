(ns effects-playground.re-frame
  "A re-frame-like approach to effects, using event dispatch for control flow."
  (:require [effects-playground.effects :as fx]))

;; machinery

(def handlers (atom {}))

(defn reg [kind k & args]
  (let [f (last args)
        #_#_interceptors (butlast args)]
    (swap! handlers assoc-in [kind k] f)))

(def reg-event (partial reg :event))
(def reg-fx (partial reg :effect))

(declare run-fx!)

(defn fx! [k & args]
  (prn "FX" (cons k args))
  (let [f (get-in @handlers [:effect k])]
    (apply f args)))

(defn run-event [[k & args]]
  (prn "RUN EVENT" (cons k args))
  (let [f (get-in @handlers [:event k])]
    (some-> (apply f args) :fx run-fx!)))

(defn run-fx! [fx-seq]
  (let [fx-seq (if (sequential? fx-seq) fx-seq [fx-seq])
        next-events (reduce (fn [next-events {:keys [command then]}]
                              (let [result (apply fx! command)]
                                (if then
                                  (conj next-events (conj then result))
                                  next-events)))
                            []
                            fx-seq)]
    (run! run-event next-events)))

;; fx

(reg-fx :db/read! fx/db-read!)
(reg-fx :db/write! fx/db-write!)
(reg-fx :message/send! fx/message-send!)
(reg-fx :user/validate! fx/user-validate!)

;; events

;; basically continuation passing
(reg-event
 ::create
 (fn [user]
   {:fx {:command [:user/validate! user]
         :then [::create-after-validate]}}))

(reg-event
 ::create-after-validate
 (fn [user]
   (if user
     {:fx {:command [:db/write! (:id user) user]
           :then [::create-after-write user]}}
     {:fx {:command [:message/send! "response" :invalid]}})))

(reg-event
 ::create-after-write
 (fn [before after]
   {:fx [{:command [:message/send! "user.created" {:before before, :after after}]}
         {:command [:message/send! "response" :ok]}]}))

;; or, in _actual_ CPS

(defn create [user]
  (letfn [(notify-created [before after cc]
            (fx! :message/send! "user.created" {:before before, :after after})
            (cc :ok))
          (after-validate [user cc]
            (if user
              (let [after (fx! :db/write! (:id user) user)]
                (notify-created user after cc))
              (cc :invalid)))
          (validate [user cc]
            (after-validate (fx! :user/validate! user) cc))
          (respond [response] (fx! :message/send! "response" response))]
    (validate user respond)))


(comment
  (def user {:name "George" :id "george@email.com" :address {}})

  (run-event [::create user])
  (create user)

  (run-event [::create (dissoc user :id)])
  (create (dissoc user :id))
  )
