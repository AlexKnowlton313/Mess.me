(ns webapp.messages
  (:require
    [reagent.core :as reagent :refer [atom]]
    [reagent.ratom     :refer-macros [reaction]]))

(defonce footer-message-state (atom {:message "" :level "info"}))

(defn setFooter[s lvl]
  (reset! footer-message-state {:message s :level lvl}))
