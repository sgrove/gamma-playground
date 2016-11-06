(ns gampg.server
  (:require [clojure.java.io :as io]
            [compojure.core :refer [GET defroutes]]
            [compojure.route :refer [resources]]
            [net.cgrand.enlive-html :refer [deftemplate]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [ring.adapter.jetty :refer [run-jetty]]
            [com.stuartsierra.component :as component]))

(defn routes [index]
  (compojure.core/routes
    (resources "/")
    (resources "/react" {:root "cljsjs/development"})
    (GET "/*" req index)))

(defn http-handler [index]
  (wrap-defaults (routes index) api-defaults))

(def page (slurp (io/resource "index.html")))

(defn run-web-server [port index]
  (print "Starting web server on port" port ".\n")
  (run-jetty (http-handler index) {:port port :join? false}))

(defrecord JettyServer [port index jetty]
  component/Lifecycle
  (start [t]
    (if-not jetty
      (assoc t :jetty (run-web-server port index))
      t))
  (stop [t]
    (when jetty
      (.stop jetty))
    (assoc t :jetty nil)))

(defn system [port index]
  (component/system-map
    :jetty (map->JettyServer {:port port :index index})))
