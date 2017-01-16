(ns webapp.websockets
  (:require [clojure.string  :as str]
            [cljs.core.async :as async  :refer (<! >! put! chan)]
            [webapp.state :as state]
            [webapp.conversations :as conversations]
            [taoensso.encore :as encore :refer-macros (have have?)]
            [taoensso.timbre :as timbre :refer-macros (tracef debugf infof warnf errorf)]
            [taoensso.sente  :as sente  :refer (cb-success?)])
  (:require-macros
    [cljs.core.async.macros :as asyncm :refer (go go-loop)]))

(enable-console-print!)

;;Def channel socket

(defn init []
  (let [;; Serializtion format, must use same val for client + server:
        packer :edn ; Default packer, a good choice in most cases

        {:keys [chsk ch-recv send-fn state]}
        (sente/make-channel-socket-client!
          "/chsk" ; Must match server Ring routing URL
          {:type   :auto
           :packer packer})]

    (def chsk       chsk)
    (def ch-chsk    ch-recv) ; ChannelSocket's receive channel
    (def chsk-send! send-fn) ; ChannelSocket's send API fn
    (def chsk-state state)   ; Watchable, read-only atom
    ))

;;event handlers

(defmulti -event-msg-handler
  "Multimethod to handle Sente `event-msg`s"
  :id ; Dispatch on event-id
  )

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event]}]
  (println "Unhandled event: " event))

(defmethod -event-msg-handler :chsk/state
  [{:as ev-msg :keys [?data]}]
  (let [[old-state-map new-state-map] (have vector? ?data)]
    (if (:first-open? new-state-map)
      (println "Channel socket successfully established!: " new-state-map)
      (println "Channel socket state change: "              new-state-map))))

(defmethod -event-msg-handler :chsk/recv
  [{:as ev-msg :keys [?data]}]
  (let [id (first ?data)
        data (second ?data)]
    (cond ;FOR MORE EVENTS, ADD TO THIS COND
      (= id :conversations/messages) (conversations/handleMessagePushEvent data)
      (= id :conversations/threads) (conversations/handleThreadPushEvent data)
      :else (println "Push event from server socket: " data id))))

(defmethod -event-msg-handler :chsk/handshake
  [{:as ev-msg :keys [?data]}]
  (let [[?uid ?csrf-token ?handshake-data] ?data]
    (println "Handshake: " ?data)))

(defonce router_ (atom nil))
(defn  stop-router! [] (when-let [stop-f @router_] (stop-f)))
(defn start-router! []
  (stop-router!)
  (reset! router_
    (sente/start-client-chsk-router!
      ch-chsk event-msg-handler)))

;; UI EVENTS

(add-watch state/chsk :chsk
           (fn [key atom old-state new-state]
             (cond 
               (= (:type new-state) :messageThread) (chsk-send! [:conversations/threads new-state])
               (= (:type new-state) :message) (chsk-send! [:conversations/messages new-state]))))

;;Inits
(defn start! [] (init) (start-router!))