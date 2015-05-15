(ns gampg.learn-gamma.lesson-09
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
  "9. Lots of moving objects")

(def u-p-matrix
  (g/uniform "uPMatrix" :mat4))

(def u-mv-matrix
  (g/uniform "uMVMatrix" :mat4))

(def a-position
  (g/attribute "aVertexPosition" :vec3))

(def a-vertex-normal
  (g/attribute "aVertexNormal" :vec3))

(def a-texture-coord
  (g/attribute "aTextureCoord" :vec2))

(def v-texture-coord
  (g/varying "vTextureCoord" :vec2 :mediump))

(def u-color
  (g/uniform "uColor" :vec3))

(def u-sampler
  (g/uniform "uSampler" :sampler2D))

(def program-source
  (p/program
   {:vertex-shader   {(g/gl-position)   (-> u-p-matrix
                                            (g/* u-mv-matrix)
                                            (g/* (g/vec4 a-position 1)))
                      v-texture-coord   a-texture-coord}
    :fragment-shader (let [texture-color (g/texture2D u-sampler (g/vec2 (g/swizzle v-texture-coord :st)))]
                       {(g/gl-frag-color) (g/* texture-color (g/vec4 u-color 1))})
    :precision {:float :mediump}}))

(defn get-perspective-matrix
  "Be sure to 
  1. pass the WIDTH and HEIGHT of the canvas *node*, not
  the GL context
  2. (set! (.-width/height canvas-node)
  width/height), respectively, or you may see no results, or strange
  results"
  [width height]
  (mat/perspective 45 (/ width height) 0.1 100))

(defn get-normal-matrix [mv]
  (-> mv
      (geom/invert)
      (geom/transpose)
      (mat/matrix44->matrix33)))

(defn get-data [p mv vertices texture texture-coords color]
  {u-p-matrix      p
   u-mv-matrix     mv
   u-color         color
   u-sampler       texture
   a-position      vertices
   a-texture-coord texture-coords})

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

(defn random-color []
  [(js/Math.random) (js/Math.random) (js/Math.random)])

(defn make-star [star-count idx]
  {:distance       (* (/ idx star-count) 5)
   :rotation-speed (/ idx star-count 10)
   :angle          0
   :color          (random-color)
   :twinkle        (random-color)})

;; js/window.requestAnimationFrame doesn't take arguments, so we have
;; to store the state elsewhere - in this atom, for example.
(defn app-state [width height]
  (let [star-count 50]
    {:last-rendered (.getTime (js/Date.))
     :scene         {:stars (map (partial make-star star-count) (range star-count))
                     :star-vertices [[-1.0, -1.0,  0.0,]
                                     [1.0, -1.0,  0.0,]
                                     [-1.0,  1.0,  0.0,]
                                     [1.0,  1.0,  0.0]]
                     :star-texture-coords [0.0, 0.0,
                                           1.0, 0.0,
                                           0.0, 1.0,
                                           1.0, 1.0]
                     :mv    (mat/matrix44)
                     :p     (get-perspective-matrix width height)}}))

(defn draw-fn [gl driver program]
  (fn [state]
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (let [{:keys [p mv texture
                  stars star-vertices
                  star-texture-coords]} (:scene state)
                  time-now     (.getTime (js/Date.))
                  twinkle? (pos? (js/Math.sin (/ time-now 1000)))]
      (doseq [star stars]
        (let [mv (-> mv
                     (geom/translate [0 0 -7])
                     (geom/rotate-around-axis [0 0 1] (:angle star))
                     (geom/translate [(:distance star) 0 -7])
                     (geom/rotate-around-axis [0 0 -1] (- (:angle star))))]
          (driver/draw-arrays driver program
                               (get-data p mv star-vertices texture star-texture-coords (if twinkle?
                                                                                          (:twinkle star)
                                                                                          (:color star)))
                               {:draw-mode :triangle-strip}))))))  

(defn animate [draw-fn step-fn current-value]
  (js/requestAnimationFrame
   (fn [time]
     (let [next-value (step-fn time current-value)]
       (draw-fn next-value)
       (animate draw-fn step-fn next-value)))))

(def effective-fpms
  (/ 60 1000))

(defn move-star [elapsed star]
  (let [distance  (- (:distance star)
                     (* effective-fpms elapsed 0.01))
        reset   (if (pos? distance) 0 5)]
    (-> star
        (assoc-in [:distance] (+ distance reset))
        (update-in [:angle] + (* (:rotation-speed star))))))

(defn tick
  "Takes the old world value and produces a new world value, suitable
  for rendering"
  [time state]
  ;; We get the elapsed time since the last render to compensate for
  ;; lag, etc.
  (let [time-now  (.getTime (js/Date.))
        elapsed   (- time-now (:last-rendered state))
        cube-diff (/ (* 75 elapsed) 100000)]
    (-> state
        ;; This is painful at 60FPS. Transducers?
        (update-in [:scene :stars] (fn [stars] (mapv (partial move-star elapsed) stars)))
        (assoc-in [:last-rendered] time-now))))

(defn main [gl node]  
  (let [width   (.-clientWidth node)
        height  (.-clientHeight node)
        driver  (driver/basic-driver gl)
        program (dp/program driver program-source)
        state   (app-state width height)]
    (reset-gl-canvas! node)
    ;; Set the blending function
    (.blendFunc gl (.-SRC_ALPHA gl) (.-ONE gl))
    (.enable gl (.-BLEND gl))
    (.disable gl (.-DEPTH_TEST gl))
    (.clearColor gl 0 0 0 1)
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (let [image (js/Image.)]
      (aset image "onload"
            (fn [] (let [texture {:data {:data       image
                                        :filter     {:min :linear
                                                     :mag :nearest}
                                        :flip-y     true
                                        :texture-id 0}}]
                    (animate (draw-fn gl driver program) tick (assoc-in state [:scene :texture] texture)))))
      (aset image "src" "/images/star.gif"))))
