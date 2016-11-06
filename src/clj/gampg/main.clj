(ns gampg.main
  (:require [com.stuartsierra.component :as component]
            [gampg.server :as server]
            [environ.core :refer [env]])
  (:gen-class))

(defn -main [& [port]]
  (component/start
    (server/system (Integer. (or port (env :port) 10555))
                   server/page)))