(ns webapp.user
  (:refer-clojure :exclude [read])
  (:require [webapp.db :as db]
            [schema.core :as s]
            (cemerick.friend [credentials :as creds])
            [clj-time.core :as t]
            [clj-time.coerce :as coerce]))

(def User
  {:nickname s/Str
   :username s/Str
   :password s/Str
   :uniqueId s/Str
   :status s/Str   
   (s/optional-key :class) s/Keyword
   })

(defn create!
  ([] (db/create! {:type :user}))
  ([m] (s/validate User m)
   (db/create! (assoc m :type :user))))

(defn read
  ([] (db/read :type :user))
  ([id] (db/read id))
  ([k v] (db/read k v)))

(defn getUserByEmail [address]
  (let [first  (first (db/getUserByEmail address))
        userId (:id first)
        user   (db/expand (read userId))]
    user))

(defn auth [address]
  (let [user (getUserByEmail address)]
    (when (not= "CONFIRMED" (:status user)) (throw (Exception. "User must be confirmed before signing in."))) user))

(defn update! [id m] (db/update! id m))

(defn delete! [id] (db/delete! id))

(defn registerUser [nickname address password]
  (let [cryptPwd (creds/hash-bcrypt password)
        uuid     (str (java.util.UUID/randomUUID))
        response {::id (create! {:nickname nickname 
                                 :username address
                                 :password cryptPwd
                                 :uniqueId uuid
                                 :status "CONFIRMED"})}]
    response))

(defn init [])


