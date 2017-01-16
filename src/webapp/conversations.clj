(ns webapp.conversations
  (:refer-clojure :exclude [read])
  (:require [webapp.db :as db]
            [clj-time.core :as t]
            [clj-time.coerce :as coerce]
            [schema.core :as s]))

(def messageThread
  {:title s/Str
   :timeStamp s/Inst
   :userIds   [s/Int]
   :originIds [s/Int]
   (s/optional-key :class) s/Keyword})

(def Message
  {:content s/Str
   :threadId s/Int
   :timeStamp s/Inst
   :edited s/Bool
   :userId s/Int})

(defn readThread
  ([]
   (db/read :type :messageThread))
  ([id]
   (db/read id))
  ([k v]
   (db/read k v )))

(defn readMessage
  ([]
   (db/read :type :message))
  ([id]
   (db/read id))
  ([k v]
   (db/read k v)))

(defn readThreadForUser [userId]
  (db/readThreadForUser userId))

(defn readMessagesForThread [idx threadId]
  (db/readMessagesSubsetForThreadId :message :timeStamp idx 30 threadId))

(defn updateThread! [id m]
  (db/update! id m))

(defn updateMessage! [id m]
  (updateThread! (:threadId m) {:timeStamp (coerce/to-date (t/now))})
  (db/update! id m))

(defn createThread!
  ([]
   (db/create! {:type :messageThread}))
  ([m]
   (s/validate messageThread m)
   (db/create! (assoc m :type :messageThread))))

(defn createMessage!
  ([]
   (db/create! {:type :message}))
  ([m]
   (s/validate Message m)
   (updateThread! (:threadId m) {:timeStamp (coerce/to-date (t/now))})
   (db/create! (assoc m :type :message))))

(defn deleteThread! [id]
  (db/delete! id))

(defn deleteMessage! [id]
  (db/delete! id))

(defn init [])