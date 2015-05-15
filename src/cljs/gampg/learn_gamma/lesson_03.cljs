(ns ^:figwheel-load gampg.learn-gamma.lesson-03
    (:require [clojure.string :as s]
            [gamma.api :as g]
            [gamma.program :as p]
            [gamma.tools :as gt]
            [gamma-driver.drivers.basic :as driver]
            [gamma-driver.protocols :as dp]
            [goog.webgl :as ggl]
            [thi.ng.geom.core :as geom]
            [thi.ng.geom.core.matrix :as mat :refer [M44]]
            [thi.ng.geom.webgl.arrays :as arrays]))

(def title
  "3. A bit of movement")

(def u-p-matrix
  (g/uniform "uPMatrix" :mat4))

(def u-mv-matrix
  (g/uniform "uMVMatrix" :mat4))

(def a-position
  (g/attribute "aVertexPosition" :vec3))

(def a-color
  (g/attribute "aVertexColor" :vec4))

(def v-color
  (g/varying "vColor" :vec4 :mediump))

(def program-source
  (p/program
   {:vertex-shader   {(g/gl-position) (-> u-p-matrix
                                          (g/* u-mv-matrix)
                                          (g/* (g/vec4 a-position 1)))
                      v-color         a-color}
    :fragment-shader {(g/gl-frag-color) v-color}}))

(defn get-perspective-matrix
  "Be sure to 
   1. pass the WIDTH and HEIGHT of the canvas *node*, not
      the GL context
   2. (set! (.-width/height canvas-node)
      width/height), respectively, or you may see no results, or strange
      results"
  [width height]
  (mat/perspective 45 (/ width height) 0.1 100))

(defn get-data [p mv vertices vertex-colors]
  {u-p-matrix  p
   u-mv-matrix mv
   a-position  vertices
   a-color     vertex-colors})

(defn make-driver [gl]
  (driver/basic-driver gl))

(defn reset-gl-canvas! [canvas-node]
  (let [gl     (.getContext canvas-node "webgl")
        width  (.-clientWidth canvas-node)
        height (.-clientHeight canvas-node)]
    ;; Set the width/height (in terms of GL-resolution) to actual
    ;; canvas-element width/height (or else you'll see blurry results)
    (set! (.-width canvas-node) width)
    (set! (.-height canvas-node) height)
    ;; Setup GL Canvas
    (.viewport gl 0 0 width height)))

;; js/window.requestAnimationFrame doesn't take arguments, so we have
;; to store the state elsewhere - in this atom, for example.
(defn app-state [width height]
  {:last-rendered 0
   :scene         {:triangle-rotation 0
                   :square-rotation   0
                   :triangle-vertices [[ 0  1  0]
                                       [-1 -1  0]
                                       [ 1 -1  0]]
                   :triangle-colors   [[1 0 0 1]
                                       [0 1 0 1]
                                       [0 0 1 1]]
                   :square-vertices   [[ 1  1  0]
                                       [-1  1  0]
                                       [ 1 -1  0]
                                       [-1 -1  0]]
                   :square-colors     [[1 0 0 1]
                                       [0 1 0 1]
                                       [0 0 1 1]
                                       [1 1 1 1]]
                   :mv                (mat/matrix44)
                   :p                 (get-perspective-matrix width height)}})

(defn draw-fn [gl driver program]
  (fn [state]
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (let [{:keys [p mv
                  triangle-vertices triangle-colors
                  triangle-rotation
                  square-vertices square-colors
                  square-rotation]} (:scene state)]
      (let [mv (-> mv
                   (geom/translate [-1.5 0 -7])
                   (geom/rotate-y triangle-rotation))]
        (driver/draw-arrays driver program (get-data p mv triangle-vertices triangle-colors) {:draw-mode :triangles}))
      (let [mv (-> mv
                   (geom/translate [3 0 -7])
                   (geom/rotate-x square-rotation))]
        (driver/draw-arrays driver program (get-data p mv square-vertices square-colors) {:draw-mode :triangle-strip})))))

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

(defn main [gl node]
  (let [width     (.-clientWidth node)
        height    (.-clientHeight node)
        driver    (make-driver gl)
        program   (dp/program driver program-source)
        state (app-state width height)]
    (reset-gl-canvas! node)
    (.clearColor gl 0 0 0 1)
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (animate (draw-fn gl driver program) tick state)))
