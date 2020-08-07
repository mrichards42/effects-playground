(ns effects-playground.train.monad
  "Not exactly free monads"
  (:require [effects-playground.train.core :as train]))

;; Instructions

(defprotocol Monad
  (bind [this fy]))

(defrecord SearchTrain [date])     ; => ReservationExpr [TrainId ...]
(defrecord GetTypology [train-id]) ; => ReservationExpr (Maybe TrainTypology)
(defrecord Reserve [reservation])  ; => ReservationExpr (Maybe Reservation)
(defrecord Log [message])          ; => ReservationExpr ()
(defrecord Pure [value])           ; => ReservationExpr x
(defrecord List [exprs])

(defrecord Free [expr continuation])

;; special-case just for reservation expressions
(extend-protocol Monad
  ;; single expression
  clojure.lang.IRecord
  (bind [reservation-expr f]
    (->Free reservation-expr f))
  ;; list of expressions -> expression returning list (i.e. list monad?)
  clojure.lang.ISeq
  (bind [reservation-exprs f]
    (bind (->List reservation-exprs) f)))

;; DSL in use

; queryTrainTypologies :: [TrainId] -> ReservationExpr [(TrainId, TrainTypology)]
(defn queryTrainTypologies [trainIds]
  (bind (for [train-id trainIds]
          (->GetTypology train-id))
        (fn [typologies]
          (->Pure (filter identity typologies)))))

;; https://github.com/QuentinDuval/HaskellTrainReservationKata/blob/7b265c918eb13cd4b48214d51c6274156fe1ce4e/src/ReservationApi.hs#L54-L68
; reserve :: ReservationRequest -> ReservationExpr ReservationResult
(defn reserve [request]
  (let [confirmByPref (fn confirmByPref [[r & reservations]]
                        (if-not r
                          (->Pure nil)
                          (bind (->Reserve r)
                                (fn [validated]
                                  (if validated
                                    (->Pure validated)
                                    (confirmByPref reservations))))))]
    (bind (->SearchTrain (:date request))
          (fn [trainIds]
            (bind (queryTrainTypologies trainIds)
                  (fn [typologies]
                    (let [reservations (train/reservations-by-decreasing-preference
                                        (:seat-count request)
                                        typologies)]
                      (bind (->Log (str "Valid reservations (by preference): "
                                        (pr-str reservations)))
                            (fn [_]
                              (confirmByPref reservations))))))))))

;; mdo version -- not so bad really, aside from needing a macro

(defmacro mdo [bindings & body]
  (if-let [[b cmd & bindings] (seq bindings)]
    `(bind ~cmd (fn [~b]
                  (mdo ~(vec bindings) ~@body)))
    (let [[first-body & more-body] body]
      (if (seq more-body)
        `(mdo [_# ~first-body] ~@more-body)
        first-body))))

(defn reserve' [request]
  (let [confirmByPref (fn confirmByPref [[r & reservations]]
                        (if-not r
                          (->Pure nil)
                          (mdo [validated (->Reserve r)]
                            (if validated
                              (->Pure validated)
                              (confirmByPref reservations)))))]
    (mdo [trainIds (->SearchTrain (:date request))
          typologies (queryTrainTypologies trainIds)
          ;; just need to make this pure
          reservations (->Pure (train/reservations-by-decreasing-preference
                                (:seat-count request)
                                typologies))]
      (->Log (str "Valid reservations (by preference): "
                  (pr-str reservations)))
      (confirmByPref reservations))))


; reserve request = do
;     trainIds <- SearchTrain (_dateTime request)
;     typologies <- queryTrainTypologies trainIds
;     let reservations = reservationsByDecreasingPreference (_seatCount request) typologies
;     Log ("Valid reservations (by preference): " ++ show reservations)
;     confirmByPref reservations
;   where
;     -- TODO: refactor (in any case, we should look in reserve... but before)
;     confirmByPref [] = Pure NoTrainAvailable
;     confirmByPref (r:rs) = do
;       validated <- Reserve r
;       case validated of
;         Nothing -> confirmByPref rs
;         Just ok -> Pure (Confirmed ok)


;;; Interpreters

(defprotocol Printer
  (->str [this]))

(extend-protocol Printer
  Free
  (->str [this]
    (flatten [(->str (:expr this))
              (when-let [c (:continuation this)]
                (->str (c nil)))]))

  List
  (->str [this]
    (map ->str (:exprs this)))

  clojure.lang.IRecord
  (->str [this]
    [(pr-str this)]))

(defprotocol MockExec
  (exec [this]))

(extend-protocol MockExec
  ;; machinery
  Free
  (exec [this]
    (let [result (exec (:expr this))]
      (if-let [c (:continuation this)]
        (exec (c result))
        (exec result))))

  List
  (exec [this]
    (mapv exec (:exprs this)))

  ;; Commands
  SearchTrain
  (exec [this] (keys train/mock-trains))

  GetTypology
  (exec [this]
    (find train/mock-trains (:train-id this)))

  Reserve
  (exec [this] (->Pure (:reservation this)))

  Log
  (exec [this]
    (println "LOG:" (:message this)))

  Pure
  (exec [this]
    (:value this)))

(comment
  (def expr (reserve {:seat-count 10, :date #inst "2020-01-01"}))
  (run! println (->str expr))
  (exec expr)

  (def expr' (reserve' {:seat-count 10, :date #inst "2020-01-01"}))
  (run! println (->str expr'))
  (exec expr')

  (= (exec expr') (exec expr))
  )
