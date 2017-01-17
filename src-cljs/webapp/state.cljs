(ns webapp.state
(:require
 [reagent.core :as r]))

;different pages' states
(defonce user-state (r/atom {:loggedIn? false :username "" :justConfirmed? false}))
(defonce conversations-state (r/atom {}))
(defonce input-state (atom ""))
(defonce main-state (r/atom {}))

;for the handler in websocket.cljs
(defonce chsk (atom nil))

; init
(defn init [])