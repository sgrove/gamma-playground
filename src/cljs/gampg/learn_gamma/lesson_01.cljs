(ns gampg.learn-gamma.lesson-01
  (:require [gamma.api :as g]
            [gamma.program :as p]
            [gamma-driver.api :as gd]
            [gamma-driver.drivers.basic :as driver]
            [thi.ng.geom.core :as geom]
            [thi.ng.geom.core.matrix :as mat :refer [M44]]))

(def title
  "1. A triangle and a square")

(def u-p-matrix
  (g/uniform "uPMatrix" :mat4))

(def u-mv-matrix
  (g/uniform "uMVMatrix" :mat4))

(def a-position
  (g/attribute "aVertexPosition" :vec3))

(def program-source
  (p/program
   {:vertex-shader   {(g/gl-position)   (-> u-p-matrix
                                            (g/* u-mv-matrix)
                                            (g/* (g/vec4 a-position 1)))}
    :fragment-shader {(g/gl-frag-color) (g/vec4 1 1 1 1)}}))

(defn get-perspective-matrix
  "Be sure to 
   1. pass the WIDTH and HEIGHT of the canvas *node*, not
      the GL context
   2. (set! (.-width/height canvas-node)
      width/height), respectively, or you may see no results, or strange
      results"
  [width height]
  (mat/perspective 45 (/ width height) 0.1 100))

(defn get-data [p mv vertices]
  {u-p-matrix  p
   u-mv-matrix mv
   a-position  vertices})

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

(defn main [gl node]
  (let [w                 (.-clientWidth node)
        h                 (.-clientHeight node)
        driver            (make-driver gl)
        program           program-source
        p                 (get-perspective-matrix w h)
        mv                (mat/matrix44)
        triangle-vertices [[ 0  1  0]
                           [-1 -1  0]
                           [ 1 -1  0]]
        square-vertices   [[ 1  1  0]
                           [-1  1  0]
                           [ 1 -1  0]
                           [-1 -1  0]]]
    (reset-gl-canvas! node)
    (.clearColor gl 0 0 0 1)
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (let [mv (geom/translate mv [-1.5 0 -7])]
      (gd/draw-arrays driver (gd/bind driver program (get-data p mv triangle-vertices)) {:draw-mode :triangles}))
    (let [mv (geom/translate mv [3 0 -7])]
      (gd/draw-arrays driver (gd/bind driver program (get-data p mv square-vertices)) {:draw-mode :triangle-strip}))))
