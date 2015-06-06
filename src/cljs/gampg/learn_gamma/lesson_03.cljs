(ns ^:figwheel-load gampg.learn-gamma.lesson-03
    (:require [gamma.api :as g]
              [gamma.program :as p]
              [gamma-driver.api :as gd]
              [gamma-driver.drivers.basic :as driver]
              [gampg.utils :as utils]
              [gampg.learn-gamma.lesson-02 :as lesson-02]
              [gampg.learn-gamma.programs :as progs]
              [thi.ng.geom.core :as geom]
              [thi.ng.geom.core.matrix :as mat :refer [M44]]))

(def title
  "3. A bit of movement")

(defn get-data [p mv vertices vertex-colors]
  {progs/u-p-matrix  p
   progs/u-mv-matrix mv
   progs/a-position  vertices
   progs/a-color     vertex-colors})

(defn app-state [width height]
  {:last-rendered 0
   :scene         {:triangle-rotation 0
                   :square-rotation   0
                   :triangle          lesson-02/triangle
                   :square            lesson-02/square
                   :mv                (mat/matrix44)
                   :p                 (utils/get-perspective-matrix 45 width height)}})

(defn draw-scene [gl driver program]
  (fn [state]
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (let [{:keys [p mv
                  triangle
                  triangle-rotation
                  square
                  square-rotation]} (:scene state)]
      (let [mv (-> mv
                   (geom/translate [-1.5 0 -7])
                   (geom/rotate-y triangle-rotation))]
        (gd/draw-arrays driver (gd/bind driver program (get-data p mv (:vertices triangle) (:colors triangle))) {:draw-mode :triangles}))
      (let [mv (-> mv
                   (geom/translate [3 0 -7])
                   (geom/rotate-x square-rotation))]
        (gd/draw-arrays driver (gd/bind driver program (get-data p mv (:vertices square) (:colors square))) {:draw-mode :triangle-strip})))))

(defn animate [draw-fn step-fn current-value]
  (js/requestAnimationFrame
   (fn [time]
     (let [next-value (step-fn time current-value)]
       (draw-fn next-value)
       (animate draw-fn step-fn next-value)))))

(defn tick
  "Takes the old world value and produces a new world value, suitable
  for rendering"
  [time state]
  ;; We get the elapsed time since the last render to compensate for
  ;; lag, etc.
  (let [time-now      (.getTime (js/Date.))
        elapsed       (- time-now (:last-rendered state))
        triangle-diff (/ (* 90 elapsed) 100000)
        square-diff   (/ (* 75 elapsed) 100000)]
    (-> state
        (update-in [:scene :triangle-rotation] + triangle-diff)
        (update-in [:scene :square-rotation] + square-diff)
        (assoc-in [:last-rendered] time-now))))

(defn main [global-app-state node]
  (let [gl      (.getContext node "webgl")
        width   (.-clientWidth node)
        height  (.-clientHeight node)
        driver  (driver/basic-driver gl)
        program (gd/program driver progs/simple-color)
        state   (app-state width height)]
    (utils/reset-gl-canvas! node)
    (.clearColor gl 0 0 0 1)
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (animate (draw-scene gl driver program) tick state)))

(def summary
  {:title title
   :enter main})
