(ns effects-playground.effects
  "Some fake effects")

(def db (atom nil))
(def broker (atom {}))
(def validator (fn [user] (every? user [:id :name])))
(defn sleep [] (Thread/sleep 100))


(defn db-read!
  ([k] (db-read! db k))
  ([db k]
   (sleep)
   (get @db k)))

(defn db-write!
  ([k v] (db-write! db k v))
  ([db k v]
   (sleep)
   (swap! db assoc k v)
   v))

(defn message-send!
  ([queue message] (message-send! broker queue message))
  ([broker queue message]
   (sleep)
   (swap! broker update queue (fnil conj (clojure.lang.PersistentQueue/EMPTY)) message)
   nil))

(defn message-get!
  ([queue] (message-get! broker queue))
  ([broker queue]
   (sleep)
   (let [message (peek (get @broker queue))]
     (swap! broker update queue pop)
     message)))

(defn user-validate!
  ([user] (user-validate! validator user))
  ([validator user]
   ;; assume this requires some kind of external api call to make sure the user
   ;; has a valid zip code or something
   (sleep)
   (if (validator user)
     user
     nil)))
