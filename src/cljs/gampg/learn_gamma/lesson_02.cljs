(ns gampg.learn-gamma.lesson-02
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
  "2. Adding colour")

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

(defn main [gl node]
  (let [w                 (.-clientWidth node)
        h                 (.-clientHeight node)
        driver            (make-driver gl)
        program           (dp/program driver program-source)
        p                 (get-perspective-matrix w h)
        mv                (mat/matrix44)
        triangle-vertices [[ 0  1  0]
                           [-1 -1  0]
                           [ 1 -1  0]]
        triangle-colors   [[1 0 0 1]
                           [0 1 0 1]
                           [0 0 1 1]]
        square-vertices   [[ 1  1  0]
                           [-1  1  0]
                           [ 1 -1  0]
                           [-1 -1  0]]
        square-colors     [[1 0 0 1]
                           [0 1 0 1]
                           [0 0 1 1]
                           [1 1 1 1]]]
    (reset-gl-canvas! node)
    (.clearColor gl 0 0 0 1)
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (let [mv (geom/translate mv [-1.5 0 -7])]
      (driver/draw-arrays driver program (get-data p mv triangle-vertices triangle-colors) {:draw-mode :triangles}))
    (let [mv (geom/translate mv [3 0 -7])]
      (driver/draw-arrays driver program (get-data p mv square-vertices square-colors) {:draw-mode :triangle-strip}))))
