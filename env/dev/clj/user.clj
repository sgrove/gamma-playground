(ns user
  (:require [clojure.tools.namespace.repl :refer (refresh)]
            [gampg.server :as server]
            [net.cgrand.enlive-html :refer [set-attr prepend append html deftemplate]]
            [com.stuartsierra.component :as component]
            [figwheel-sidecar.repl :as repl]
            [figwheel-sidecar.repl-api :as repl-api])
  (:import (java.io StringReader)))

(def figwheel-config
  {:figwheel-options { :css-dirs ["resources/public/css"] }
   :all-builds      [{:id           "dev"
                      :source-paths ["src/cljs" "env/dev/cljs"]
                      :compiler     {:output-to            "resources/public/js/app.js"
                                     :output-dir           "resources/public/js/out"
                                     :source-map           "resources/public/js/out.js.map"
                                     :source-map-timestamp true}}]})

(def inject-devmode-html
  (comp
    (set-attr :class "is-dev")
    (prepend (html [:script {:type "text/javascript" :src "/js/out/goog/base.js"}]))
    (prepend (html [:script {:type "text/javascript" :src "/react/react.inc.js"}]))
    (append  (html [:script {:type "text/javascript"} "goog.require('gampg.main')"]))))

(deftemplate page (StringReader. server/page) []
             [:body] inject-devmode-html)

(defrecord FigwheelServer []
  ;; Since we use alter-var-root only one of these can be running at a time,
  ;; Though thats ok because the underlieing server will System/exit if
  ;; a second was started anyways
  component/Lifecycle
  (start [t]
    (when-not repl/*autobuild-env*
      (alter-var-root #'repl/*autobuild-env* (constantly (repl/create-autobuild-env figwheel-config)))
      (repl/clean-builds ["dev"])
      (repl/start-autobuild ["dev"]))
    t)
  (stop [t]
    (when repl/*autobuild-env*
      ((get-in repl/*autobuild-env* [:figwheel-server :http-server]) :timeout 100)
      (repl/stop-autobuild)
      (alter-var-root #'repl/*autobuild-env* (constantly false)))
    t))

(defonce system nil)

(defn init []
  (alter-var-root #'system
    (constantly (assoc (server/system 10555 (page))
                  :figwheel (map->FigwheelServer {})))))

(defn start []
  (alter-var-root #'system component/start))

(defn stop []
  (alter-var-root #'system
    (fn [s] (when s (component/stop s)))))

(defn go []
  (init)
  (start))

(defn reset []
  (stop)
  (refresh :after 'user/go))

(defn browser-repl []
  (if repl/*autobuild-env*
    (repl/cljs-repl)
    (prn "Figwheel is not running.  Try (go) first")))

(defn fig-status []
  (if repl/*autobuild-env*
    (repl-api/fig-status)
    (prn "Figwheel is not running.  Try (go) first")))
