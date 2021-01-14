(ns effects-playground.monad
  "Similar to free monads"
  (:require [effects-playground.effects :as fx]))

;;; Machinery

;; A delayed fx is either an Instruction or a Pure value
(defrecord Instruction [kind args])
(defrecord Pure [value])

;; Free fx instructions
(defrecord Free [instruction cc])

(defn bind [instruction cc] ;; might be better as a protocol
  (->Free instruction cc))

(defn fx! [kind & args]
  (->Instruction kind args))

;;; Interpreters

(def prod-fx
  {:db/read! fx/db-read!
   :db/write! fx/db-write!
   :message/send! fx/message-send!
   :user/validate! fx/user-validate!})

;; basic protocol for walking the tree
(defprotocol Walk
  (walk [this f]))

(extend-protocol Walk
  Free
  (walk [free f]
    (let [{:keys [instruction cc]} free
          result (walk instruction f)]
      (if cc
        (walk (cc result) f)
        result)))

  Instruction
  (walk [instruction f]
    (f instruction))

  Pure
  (walk [pure f]
    (:value pure))

  ;; maybe a special case so we don't need `Pure` at all?
  #_#_java.lang.Object
  (walk [this f]
    this))

;; basic walker that executes side-effects
(defn exec-walker [fx-map]
  (fn [instruction]
    (let [{:keys [kind args]} instruction]
      (if-let [fx! (get fx-map kind)]
        (apply fx! args)
        (throw (ex-info (str "Unknown fx! " kind) {:instruction instruction
                                                   :fx-map fx-map}))))))
;; essentially "middleware"
(defn print-walker [f]
  (fn [instruction]
    (print instruction "=> ")
    (let [result (f instruction)]
      (prn result)
      result)))

;; Could do a state monad, but probably simpler to use actual state (atoms)
; e.g. (defrecord State [state value])

(defn trace-walker [state f]
  (fn [instruction]
    (swap! state conj {:instruction instruction})
    (let [result (f instruction)]
      (swap! state #(conj (pop %)
                          (assoc (peek %) :result result)))
      result)))

(defn trace [ast f]
  (let [state (atom [])
        result (walk ast (trace-walker state f))]
    {:trace @state :result result}))


;;; Usage

(defn create-user [user]
  (bind (fx! :user/validate! user)
        (fn [user]
          (if user
            (let [id (:id user)]
              (bind (fx! :db/write! id user)
                    (fn [after]
                      (bind (fx! :message/send! "user.created" {:user after})
                            (fn [_]
                              (fx! :message/send! "response" :ok))))))
            (fx! :message/send! "response" :invalid)))))

;; `do` version

(defmacro let-fx
  "Like let, but for fx instructions (aka haskell's `do`)"
  [bindings & body]
  (if-let [[b cmd & bindings] (seq bindings)]
    `(bind ~cmd (fn [~b]
                  (let-fx ~(vec bindings) ~@body)))
    (let [[first-body & more-body] body]
      (if (seq more-body)
        `(let-fx [_# ~first-body] ~@more-body)
        first-body))))

;; should be equivalent to the explicit continuation version above, just nicer
;; syntax
(defn create-user-do [user]
  (let-fx [user (fx! :user/validate! user)]
    (if user
      (let-fx [;; except sticking this `->Pure` here is pretty ugly; could
               ;; add a special-case in `bind` or in `walk` so that we don't
               ;; need the explicit ->Pure
               id (->Pure (:id user))
               after (fx! :db/write! id user)]
        (fx! :message/send! "user.created" {:user after})
        (fx! :message/send! "response" :ok))
      (fx! :message/send! "response" :invalid))))



(comment
  (def user {:id 1234 :name "George"})

  ;; do the side effects
  (walk (create-user user) (exec-walker prod-fx))

  ;; trace the nil pathway
  (trace (create-user user) (constantly nil))
  ;; trace the non-nil pathway
  (clojure.pprint/pprint (trace (create-user user) (constantly true)))

  ;; print while executing
  (walk (create-user user) (print-walker (exec-walker prod-fx)))

  ;; the same with the `do` variant
  (trace (create-user-do user) (constantly true))
  (trace (create-user-do user) (print-walker (exec-walker prod-fx)))

  )


;; What tests look like
(require '[clojure.test :refer [deftest testing is]])

(def test-fx
  {:db/write! (fn [_id user] user)
   :message/send! (constantly nil)
   :user/validate! (fn [user] user)})

(defn trace-instructions [ast & [fx-map]]
  (->> (trace ast (exec-walker (merge test-fx fx-map)))
       :trace
       (map :instruction)))

(deftest create-user-test
  (testing "valid user is created"
    (let [user {:id 1234 :name "George"}]
      (is (= [(->Instruction :user/validate! [user])
              (->Instruction :db/write! [1234 user])
              (->Instruction :message/send! ["user.created" {:user user}])
              (->Instruction :message/send! ["response" :ok])]
             (trace-instructions (create-user user))))))
  (testing "invalid user results in an error"
    (let [user {:id 1234 :name "George"}]
      (is (= [(->Instruction :user/validate! [user])
              (->Instruction :message/send! ["response" :invalid])]
             (trace-instructions (create-user user)
                                 {:user/validate! (constantly nil)}))))))
