(ns gampg.main
  (:require [gampg.core :as core]
            [figwheel.client :as figwheel]))

(enable-console-print!)

(figwheel/watch-and-reload
  :websocket-url "ws://localhost:3449/figwheel-ws"
  :jsload-callback (fn []
                     (core/main)))

(core/main)
