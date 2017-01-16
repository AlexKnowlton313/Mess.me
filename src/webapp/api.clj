(ns webapp.api
  (:require [webapp.html :as html]
            [webapp.init :as init]
            [webapp.conversations :as conversations]
            [webapp.user :as user]
            [webapp.db :as db]
            [webapp.auth :as auth]
            [webapp.sockets :as sockets]
            [liberator.core :refer (resource)]
            [compojure.core :refer (defroutes ANY GET POST)]
            [compojure.route :refer (resources not-found)]
            [ring.middleware.keyword-params :refer (wrap-keyword-params)]
            [ring.middleware.params :refer (wrap-params)]
            [ring.middleware.session :refer (wrap-session)]
            [ring.middleware.edn :refer (wrap-edn-params)]
            [ring.util.response :refer (redirect)]
            [clj-json.core :as json]
            [clj-time.coerce :as coerce]
            [cemerick.friend :as friend]
            [webapp.resources :as r :refer [defresource]]
            [clj-time.core :as t]))

(defn handle-exception
  [ctx]
  (let [e (:exception ctx)]
    (.printStackTrace e)
    {:status 500 :message (.getMessage e)}))

(defn getUser [ctx]
  (let [authentications (get-in ctx [:request :session :cemerick.friend/identity :authentications])
        current (get-in ctx [:request :session :cemerick.friend/identity :current])]
    (get-in authentications [current])))

(defroutes routes
  (GET  "/chsk"  ring-req (sockets/ring-ajax-get-or-ws-handshake ring-req))
  (ANY "/login" [] (redirect "/login.html"))
  (ANY "/logout" req (friend/logout* (redirect (str (:context req) "/"))))
  (POST "/chsk"  ring-req (sockets/ring-ajax-post                ring-req))
  
  (ANY "/users" []
       (defresource users
         :available-media-types ["application/edn" "application/json" "text/html"]
         :allowed-methods [:get]
         :base r/authenticated-base
         :handle-ok (fn [ctx]
                      (let [users (mapv db/expand (db/getAllUsers))]
                        (condp = (-> ctx :representation :media-type)
                          "application/edn" users
                          "application/json" (json/generate-string users)
                          "text/html" (html/generate-string users))))
         :post-redirect? (fn [ctx] {:location (str "/threads/" (::id ctx))})
         :handle-exception handle-exception))
  
  (ANY "/threads" [title targetId]
       (defresource thread
         :available-media-types ["application/edn" "application/json" "text/html"]
         :allowed-methods [:get :post]
         :base r/authenticated-base
         :handle-ok (fn [ctx]
                      (let [userId  (:id (getUser ctx))
                            threads (mapv db/expand (conversations/readThreadForUser userId))
                            found (map #(let [targetUserId    (map long (remove (fn [id] (= userId id)) (:originIds %)))
                                              targetUser      (map (fn [id] (db/expand (db/read id))) targetUserId)
                                              targetUserEmail (map :username targetUser)
                                              targetUserName  (map :nickname targetUser)]
                                          (assoc %
                                            :targetUserId targetUserId
                                            :targetUserName targetUserName
                                            :targetUserEmail targetUserEmail))
                                       threads)]
                        (condp = (-> ctx :representation :media-type)
                          "application/edn" found
                          "application/json" (json/generate-string found)
                          "text/html" (html/generate-string found))))
         :post! (fn [ctx]  {::id (conversations/createThread!
                                   {:title (if title title "")
                                    :timeStamp (coerce/to-date (t/now))
                                    :userIds   (reduce conj (vec (read-string (str targetId))) [(:id (getUser ctx))])
                                    :originIds (reduce conj (vec (read-string (str targetId))) [(:id (getUser ctx))])})})
         :post-redirect? (fn [ctx] {:location (str "/threads/" (::id ctx))})
         :handle-exception handle-exception))
  
  (ANY "/threads/:id" [id title targetId]
       (let [id (Integer/parseInt id)]
         (defresource thread-existing
           :available-media-types ["application/edn"]
           :allowed-methods [:get :put :delete]
           :base r/data-multi-userId-auth
           :handle-ok (fn [ctx]
                        (db/expand (conversations/readThread id)))
           :put! (fn [ctx]
                   (conversations/updateThread!
                     id
                     {:title title
                      :timeStamp (coerce/to-date (t/now))}))
           :new? false
           :respond-with-entity? true
           :delete! (fn [ctx]
                      (db/retract! id {:userIds (:id (getUser ctx))})
                      (conversations/updateThread! id {:timeStamp (coerce/to-date (t/now))})
                      (let [thread (db/expand (conversations/readThread id))]
                        (if (empty? (:userIds thread)) (conversations/deleteThread! id))
                        nil))
           :handle-exception handle-exception)))
  
  
  (ANY "/messages"
       [content threadId idx]
       (defresource messages
         :available-media-types ["application/edn" "application/json" "text/html"]
         :allowed-methods [:get :post]
         :base r/data-multi-userId-auth
         :handle-ok (fn [ctx]
                      (let [mostRecentResultFrom (if (not (nil? idx)) (Long/parseLong (str idx)) 0)
                            found (map db/expand (conversations/readMessagesForThread mostRecentResultFrom (Long/parseLong (str threadId))))]
                        (condp = (-> ctx :representation :media-type)
                          "application/edn" found
                          "application/json" (json/generate-string found)
                          "text/html" (html/generate-string found))))
         :post! (fn [ctx]
                  {::id (conversations/createMessage! {:content   content
                                                       :threadId  (Long/parseLong (str threadId))
                                                       :timeStamp (coerce/to-date (t/now))
                                                       :edited    false
                                                       :userId    (:id (getUser ctx))})})
         :post-redirect? (fn [ctx] {:location (str "/messages/" (::id ctx))})
         :handle-exception handle-exception))
  
  
  (ANY "/messages/:id" [id content threadId]
       (let [id (Integer/parseInt id)]
         (defresource messages-existing
           :available-media-types ["application/edn"]
           :allowed-methods [:get :put :delete]
           :base r/authenticated-base
           :handle-ok (fn [ctx]
                        (db/expand (conversations/readMessage id)))
           :put! (fn [ctx]
                   (conversations/updateMessage!
                     id
                     {:content   content
                      :timeStamp (coerce/to-date (t/now))
                      :edited    true}))
           :new? false
           :respond-with-entity? true
           :delete! (fn [ctx] (conversations/deleteMessage! id))
           :handle-exception handle-exception)))
  
  (ANY "/" [] (redirect "/index.html"))
  
  (resources "/" {:root "public"})
  (resources "/" {:root "/META-INF/resources"})
  (not-found "404"))

(def handler
  (-> routes
      (auth/friend-middleware user/auth)
      wrap-keyword-params
      wrap-params
      wrap-session
      wrap-edn-params))

(defn init []
  (db/init)
  (init/init)
  (sockets/start!))