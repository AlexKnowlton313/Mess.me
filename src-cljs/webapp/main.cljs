(ns webapp.main
  (:require-macros [cljs.core.async.macros :refer (go)])
  (:require
    [webapp.conversations :refer (conversations getThreads)]
    [webapp.state :as state]
    [webapp.websockets :as ws]
    [cljs-http.client :as http]
    [reagent.core :as reagent :refer [atom]]))

(enable-console-print!)

(defmacro handler-fn
  "Fixes a bug in reagent were handlers cannot return 'false'
  This function wraps them to alwasys return nil instead"
  ([& body]
   `(fn [~'event] ~@body nil)))

(defn getUsers []
  "gets all users"
  (go (let [users (:body (<! (http/get "/users")))
            removeMe (remove #(= (:id %) (:id @state/user-state)) users)
            genericKeys (flatten (map #(list {:name (:nickname %) :id (:id %) :username (:username %) :password "foo"}) removeMe))]
        (swap! state/main-state assoc :availableUsers genericKeys))))

(defn login! [creds]
  "creds must be an atom of the form:
  {:username email :password password}"
  (go (let [response (<! (http/post "/login" {:edn-params creds}))]
        (reset! state/user-state  {:loggedIn? true :username (:username creds) :name (:name creds) :id (:id creds)})
        (getUsers)
        (getThreads)
        (ws/start!))))

(defn logout! []
  (go (let [response (<! (http/get "/logout"))]
        (reset! state/user-state {:loggedIn? false :username ""})
        (reset! state/conversations-state {})
        (ws/stop-router!))))

(defn userModelUsers [user]
  [:p.settingsModalOption
   {:on-click #(do (logout!) (login! user))}
   (:name user)])

(defn changeUserModel []
  "the popup for changing users"
  [:div.changeUserModel.mdl-dialog
   [:div.mdl-dialog__content
    {:on-mouse-leave #(handler-fn (swap! state/main-state assoc :dialog-open false) nil)}
    [:h6.settingsModalTitle
     "Pick a new user:"]
    (map (fn [user] ^{:key (str "user-" (:name user))} [userModelUsers user]) (sort-by :name (:availableUsers @state/main-state)))]])

(defn screen []
  [:div
   [:main.mdl-layout__content.mdl-color--grey-100.width100
    [:div.mdl-card__title.alignItemsStart.mdl-card--border.mdl-card--expand..mdl-color--blue-400
     [:h3.mdl-card__title-text.text-white
      "A Simple Messenger Program"]
     [:div.mdl-layout-spacer]
     [:button.mdl-button.mdl-js-button.mdl-js-ripple-effect.text-white 
      {:on-click #(handler-fn (swap! state/main-state assoc :dialog-open true) nil)}
      (:name @state/user-state)]
     (if (:dialog-open @state/main-state)
       [changeUserModel])]
    [:div.mdl-card__supporting-text.mdl-color-text--grey-600.mdl-card--border.fullWidth
     [conversations]]]])

(defn mountit []
  (reagent/render-component [screen] (js/document.getElementById "app")))

;login as Harold by default
(login! {:username "example1@gmail.com" :password "foo" :id 1 :name "Alex"})
(mountit)
(ws/start!)

(defn fig-reload []
  (mountit))