(ns gampg.learn-gamma.lesson-02
  (:require [cljs.repl :as repl]
            [fipp.edn :as fipp]
            [gamma.api :as g]
            [gamma.program :as p]
            [gamma-driver.api :as gd]
            [gamma-driver.drivers.basic :as driver]
            [gampg.learn-gamma.programs :as progs]
            [gampg.utils :as utils]
            [thi.ng.geom.core :as geom]
            [thi.ng.geom.core.matrix :as mat :refer [M44]]))

(def title
  "2. Adding colour")

(def v-color
  (g/varying "vColor" :vec4 :mediump))

(def program-source
  (p/program
   {:id              :lesson-02-simple
    :vertex-shader   {(g/gl-position) (-> progs/u-p-matrix
                                          (g/* progs/u-mv-matrix)
                                          (g/* (g/vec4 progs/a-position 1)))
                      v-color         progs/a-color}
    :fragment-shader {(g/gl-frag-color) progs/v-color}}))

(defn get-data [p mv vertices vertex-colors]
  {progs/u-p-matrix  p
   progs/u-mv-matrix mv
   progs/a-position  vertices
   progs/a-color     vertex-colors})

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

(def triangle
  {:vertices {:data       [[ 0  1  0]
                           [-1 -1  0]
                           [ 1 -1  0]]
              :immutable? true
              :id         :triangle-vertices}
   :colors   {:data       [[1 0 0 1]
                           [0 1 0 1]
                           [0 0 1 1]]
              :immutable? true
              :id         :triangle-colors}})

(def square
  {:vertices {:data       [[ 1  1  0]
                           [-1  1  0]
                           [ 1 -1  0]
                           [-1 -1  0]]
              :immutable? true
              :id         :square-vertices}
   :colors   {:data       [[1 0 0 1]
                           [0 1 0 1]
                           [0 0 1 1]
                           [1 1 1 1]]
              :immutable? true
              :id         :square-colors}})

(defn main [app-state node]
  (let [gl      (.getContext node "webgl")
        w       (.-clientWidth node)
        h       (.-clientHeight node)
        driver  (make-driver gl)
        program program-source
        p       (utils/get-perspective-matrix w h)
        mv      (mat/matrix44)]
    (reset-gl-canvas! node)
    (.clearColor gl 0 0 0 1)
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (let [mv (geom/translate mv [-1.5 0 -7])]
      (gd/draw-arrays driver (gd/bind driver program (get-data p mv (:vertices triangle) (:colors triangle))) {:draw-mode :triangles}))
    (let [mv (geom/translate mv [3 0 -7])]
      (gd/draw-arrays driver (gd/bind driver program (get-data p mv (:vertices square) (:colors square))) {:draw-mode :triangle-strip}))))

(def explanation
  (str
   "```clojure " (with-out-str (repl/source main)) "
 ```
"))

(def summary
  {:title       title
   :enter       main
   :explanation explanation})
