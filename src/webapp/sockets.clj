(ns webapp.sockets
  (:require
    [clojure.string     :as str]
    [webapp.resources :as res]
    [webapp.conversations :as conversations]
    [webapp.db :as db]
    [compojure.core     :as comp :refer (defroutes GET POST)]
    [compojure.route    :as route]
    [hiccup.core        :as hiccup]
    [clojure.core.async :as async  :refer (<! <!! >! >!! put! chan go go-loop)]
    [taoensso.encore    :as encore :refer (have have?)]
    [taoensso.timbre    :as timbre :refer (tracef debugf infof warnf errorf)]
    [taoensso.sente     :as sente]
    [org.httpkit.server :as http-kit]
    [taoensso.sente.server-adapters.http-kit :refer (get-sch-adapter)]
    [taoensso.sente.packers.transit :as sente-transit]))

(let [packer :edn
      chsk-server
      (sente/make-channel-socket-server!
        (get-sch-adapter)
        {:packer packer
         :user-id-fn (fn [ring-req] (:id (res/getUser ring-req)))})
      {:keys [ch-recv send-fn connected-uids
              ajax-post-fn ajax-get-or-ws-handshake-fn]}
      chsk-server]
  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
  (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids) ; Watchable, read-only atom
  )

;; We can watch this atom for changes if we like
(add-watch connected-uids :connected-uids
           (fn [_ _ old new]
             (when (not= old new)
               (infof "Connected uids change: %s" new))))

;; send functions
(defn push-to-thread-owners [content chskId]
  (let
    [threadId (if (= chskId :conversations/threads) (:id content) (:threadId content))
     thread   (conversations/readThread threadId)
     userIds  (:userIds thread)]
    (doseq [uid userIds]
      (chsk-send! uid [chskId content]))))

;;event handlers
(defmulti -event-msg-handler
  "Multimethod to handle Sente `event-msg`s"
  :id ; Dispatch on event-id
  )

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg) ; Handle event-msgs on a single thread
  ;; (future (-event-msg-handler ev-msg)) ; Handle event-msgs on a thread pool
  )

(defmethod -event-msg-handler :chsk/uidport-open [{:keys [uid client-id]}]
  (println "New connection:" uid client-id))

(defmethod -event-msg-handler :chsk/uidport-close [{:keys [uid]}]
  (println "Disconnected:" uid))

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:id (res/getUser ring-req))]
    ;(debugf "Unhandled event: %s" event)
    (when ?reply-fn
      (?reply-fn {:umatched-event-as-echoed-from-from-server event}))))

(defmethod -event-msg-handler :conversations/messages
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (push-to-thread-owners ?data id))


(defmethod -event-msg-handler :conversations/threads
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (println "sending thread!")
  (push-to-thread-owners ?data id))

;;router and start/stop

(defonce router_ (atom nil))

(defn  stop-router! [] (when-let [stop-fn @router_] (stop-fn)))

(defn start-router! []
  (stop-router!)
  (reset! router_
          (sente/start-server-chsk-router!
            ch-chsk event-msg-handler)))

(defn start! [] (start-router!))
