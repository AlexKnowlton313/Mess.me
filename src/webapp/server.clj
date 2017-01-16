(ns webapp.server
  (:use [org.httpkit.server :only [run-server]])
  (:require [webapp.api :as api]
            [webapp.db :as db]))

(def server (atom nil))

(defn -main []
  (do
    (run-server api/handler {:port 3000})
    (println "Web server is running at http://localhost:3000/")
    (try
      (.browse (java.awt.Desktop/getDesktop) (java.net.URI. "http://localhost:3000/"))
      (catch java.awt.HeadlessException _)))
  (api/init))