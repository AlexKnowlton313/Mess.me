(ns webapp.init
  (:require 
    [webapp.user :as user]
    [clj-time.core :as t]
    [clj-time.coerce :as coerce]
    [clojure.instant :as i]
    [webapp.db :as db]))

(defonce cache (atom {}))

(defn init[]
  ;create user 1
  (swap! cache assoc :user1 (:webapp.user/id (user/registerUser "Alex" "example1@gmail.com" "foo")))
  
  ;create user 2
  (swap! cache assoc :user2 (:webapp.user/id (user/registerUser "David" "example2@gmail.com" "foo")))
  
  ;create user 3
  (swap! cache assoc :user3 (:webapp.user/id (user/registerUser "Jamal" "example3@gmail.com" "foo" )))
  
  ;create user 4
  (swap! cache assoc :user4 (:webapp.user/id (user/registerUser "Brian" "example4@gmail.com" "foo")))
  
  ;create user 5
  (swap! cache assoc :user5 (:webapp.user/id (user/registerUser "CJ" "example5@gmail.com" "foo")))
  
  ;create user 6
  (swap! cache assoc :user5 (:webapp.user/id (user/registerUser "Tommy" "example6@gmail.com" "foo")))
  
  ;create user 7
  (swap! cache assoc :user5 (:webapp.user/id (user/registerUser "Adam" "example7@gmail.com" "foo")))
  
  ;create user 8
  (swap! cache assoc :user5 (:webapp.user/id (user/registerUser "Scott" "example8@gmail.com" "foo"))))

