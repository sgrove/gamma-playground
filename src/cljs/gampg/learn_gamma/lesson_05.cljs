(ns ^:figwheel-load gampg.learn-gamma.lesson-05
    (:require [gamma.api :as g]
              [gamma.program :as p]
              [gamma-driver.api :as gd]
              [gamma-driver.drivers.basic :as driver]
              [thi.ng.geom.core :as geom]
              [thi.ng.geom.core.matrix :as mat :refer [M44]]))

(def title
  "5. Introducing textures")

(def u-p-matrix
  (g/uniform "uPMatrix" :mat4))

(def u-mv-matrix
  (g/uniform "uMVMatrix" :mat4))

(def a-position
  (g/attribute "aVertexPosition" :vec3))

(def a-texture-coord
  (g/attribute "aTextureCoord" :vec2))

(def v-texture-coord
  (g/varying "vTextureCoord" :vec2 :mediump))

(def u-sampler
  (g/uniform "uSampler" :sampler2D))

(def program-source
  (p/program
   {:vertex-shader   {(g/gl-position) (-> u-p-matrix
                                          (g/* u-mv-matrix)
                                          (g/* (g/vec4 a-position 1)))
                      v-texture-coord a-texture-coord}
    :fragment-shader {(g/gl-frag-color)
                      (g/texture2D u-sampler (g/vec2 (g/swizzle v-texture-coord :st)))}}))

(defn get-perspective-matrix
  "Be sure to 
   1. pass the WIDTH and HEIGHT of the canvas *node*, not
      the GL context
   2. (set! (.-width/height canvas-node)
      width/height), respectively, or you may see no results, or strange
      results"
  [width height]
  (mat/perspective 45 (/ width height) 0.1 100))

(defn get-data [p mv vertices texture texture-coords]
  {u-p-matrix      p
   u-mv-matrix     mv
   u-sampler       texture
   a-position      vertices
   a-texture-coord texture-coords})

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
   :scene         {:cube-vertices       [ ;; Front face
                                         [-1.0 -1.0  1.0]
                                         [1.0 -1.0  1.0]
                                         [1.0  1.0  1.0]
                                         [-1.0  1.0  1.0]
                                         
                                         ;; Back face
                                         [-1.0 -1.0 -1.0]
                                         [-1.0  1.0 -1.0]
                                         [1.0  1.0 -1.0]
                                         [1.0 -1.0 -1.0]
                                         
                                         ;; Top face
                                         [-1.0  1.0 -1.0]
                                         [-1.0  1.0  1.0]
                                         [1.0  1.0  1.0]
                                         [1.0  1.0 -1.0]
                                         
                                         ;; Bottom face
                                         [-1.0 -1.0 -1.0]
                                         [1.0 -1.0 -1.0]
                                         [1.0 -1.0  1.0]
                                         [-1.0 -1.0  1.0]
                                         
                                         ;; Right face
                                         [1.0 -1.0 -1.0]
                                         [1.0  1.0 -1.0]
                                         [1.0  1.0  1.0]
                                         [1.0 -1.0  1.0]
                                         
                                         ;; Left face
                                         [-1.0 -1.0 -1.0]
                                         [-1.0 -1.0  1.0]
                                         [-1.0  1.0  1.0]
                                         [-1.0  1.0 -1.0]]
                   :cube-texture-coords [
                                         ;; Front face
                                         0.0, 0.0,
                                         1.0, 0.0,
                                         1.0, 1.0,
                                         0.0, 1.0,

                                         ;; Back face
                                         1.0, 0.0,
                                         1.0, 1.0,
                                         0.0, 1.0,
                                         0.0, 0.0,

                                         ;; Top face
                                         0.0, 1.0,
                                         0.0, 0.0,
                                         1.0, 0.0,
                                         1.0, 1.0,

                                         ;; Bottom face
                                         1.0, 1.0,
                                         0.0, 1.0,
                                         0.0, 0.0,
                                         1.0, 0.0,

                                         ;; Right face
                                         1.0, 0.0,
                                         1.0, 1.0,
                                         0.0, 1.0,
                                         0.0, 0.0,

                                         ;; Left face
                                         0.0, 0.0,
                                         1.0, 0.0,
                                         1.0, 1.0,
                                         0.0, 1.0,]
                   :cube-indices        [0  1  2     0  2  3  ;; Front face
                                         4  5  6     4  6  7  ;; Back face
                                         8  9  10    8 10 11  ;; Top face
                                         12 13 14   12 14 15  ;; Bottom face
                                         16 17 18   16 18 19  ;; Right face
                                         20 21 22   20 22 23;;
                                         ] ;; Left face
                   :cube-rotation       0
                   :mv                  (mat/matrix44)
                   :p                   (get-perspective-matrix width height)}})

(defn draw-fn [gl driver program]
  (fn [state]
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (let [{:keys [p mv
                  texture
                  cube-vertices cube-texture-coords
                  cube-rotation cube-indices]} (:scene state)]
      (let [mv (-> mv
                   (geom/translate [3 0 -7])
                   (geom/rotate-around-axis [1 0 0] cube-rotation)
                   (geom/rotate-around-axis [0 1 0] cube-rotation)
                   (geom/rotate-around-axis [0 0 1] cube-rotation))]
        (gd/draw-elements driver (gd/bind driver program
                                          (assoc (get-data p mv cube-vertices texture cube-texture-coords)
                                                 {:tag :element-index} cube-indices))
                          {:draw-mode :triangles
                           :first     0
                           ;; Hard-coded
                           :count     36})))))

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
  (let [time-now     (.getTime (js/Date.))
        elapsed      (- time-now (:last-rendered state))
        cube-diff    (/ (* 75 elapsed) 100000)]
    (-> state
        (update-in [:scene :cube-rotation] + cube-diff)
        (assoc-in [:last-rendered] time-now))))

(defn main [_ node]
  (let [gl      (.getContext node "webgl")
        width   (.-clientWidth node)
        height  (.-clientHeight node)
        driver  (make-driver gl)
        program program-source
        state   (app-state width height)]
    (reset-gl-canvas! node)
    (.enable gl (.-DEPTH_TEST gl))
    (.clearColor gl 0 0 0 1)
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (let [image (js/Image.)]
      (aset image "onload"
            (fn [] (let [texture {:data       image
                                 :filter     {:min :linear
                                              :mag :linear}
                                 :flip-y     true}]
                    (animate (draw-fn gl driver program) tick (assoc-in state [:scene :texture] texture)))))
      (aset image "src" "/images/nehe.gif"))))

(def explanation
  nil)

(def summary
  {:title       title
   :enter       main
   :explanation explanation})
