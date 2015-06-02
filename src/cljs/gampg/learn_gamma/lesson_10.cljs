(ns gampg.learn-gamma.lesson-10
  (:require [gamma.api :as g]
            [gamma.program :as p]
            [gamma-driver.api :as gd]
            [gamma-driver.drivers.basic :as driver]
            [thi.ng.geom.core :as geom]
            [thi.ng.geom.core.matrix :as mat :refer [M44]]))

;; With some better architecture,
;; this could easily go away
(declare app-state)

(def title
  "10. Loading a world, and the most basic kind of camera")

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
   {:vertex-shader   {(g/gl-position)   (-> u-p-matrix
                                            (g/* u-mv-matrix)
                                            (g/* (g/vec4 a-position 1)))
                      v-texture-coord   a-texture-coord}
    :fragment-shader (let [texture-color (g/texture2D u-sampler (g/vec2 (g/swizzle v-texture-coord :st)))]
                       {(g/gl-frag-color) texture-color})
    :precision       {:float :mediump}}))

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

(defn get-data [p mv vertices texture texture-coords]
  {u-p-matrix      p
   u-mv-matrix     mv
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

;; js/window.requestAnimationFrame doesn't take arguments, so we have
;; to store the state elsewhere - in this atom, for example.
(defn make-app-state [width height]
  (let [star-count 50]
    {:last-rendered (.getTime (js/Date.))
     :scene         {:poly-count 36
                     :texture-coords {:id   :texture-coords
                                      :data [[0.0 6.0]
                                             [0.0 0.0]
                                             [6.0 0.0]
                                             [0.0 6.0]
                                             [6.0 6.0]
                                             [6.0 0.0]
                                             [0.0 6.0]
                                             [0.0 0.0]
                                             [6.0 0.0]
                                             [0.0 6.0]
                                             [6.0 6.0]
                                             [6.0 0.0]
                                             [0.0 1.0]
                                             [0.0 0.0]
                                             [1.5 0.0]
                                             [0.0 1.0]
                                             [1.5 1.0]
                                             [1.5 0.0]
                                             [2.0 1.0]
                                             [2.0 0.0]
                                             [0.5 0.0]
                                             [2.0 1.0]
                                             [0.5 1.0]
                                             [0.5 0.0]
                                             [2.0  1.0]
                                             [2.0 0.0]
                                             [0.5 0.0]
                                             [2.0  1.0]
                                             [0.5  1.0]
                                             [0.5 0.0]
                                             [2.0  1.0]
                                             [2.0 0.0]
                                             [0.5 0.0]
                                             [2.0  1.0]
                                             [0.5  1.0]
                                             [0.5 0.0]
                                             [0.0  1.0]
                                             [0.0 0.0]
                                             [1.5 0.0]
                                             [0.0  1.0]
                                             [1.5  1.0]
                                             [1.5 0.0]
                                             [2.0 1.0]
                                             [2.0 0.0]
                                             [0.5 0.0]
                                             [2.0 1.0]
                                             [0.5 1.0]
                                             [0.5 0.0]
                                             [0.0 1.0]
                                             [0.0 0.0]
                                             [1.5 0.0]
                                             [0.0 1.0]
                                             [1.5 1.0]
                                             [1.5 0.0]
                                             [2.0 1.0]
                                             [2.0 0.0]
                                             [0.5 0.0]
                                             [2.0 1.0]
                                             [0.5 1.0]
                                             [0.5 0.0]
                                             [0.0 1.0]
                                             [0.0 0.0]
                                             [1.0 0.0]
                                             [0.0 1.0]
                                             [1.0 1.0]
                                             [1.0 0.0]
                                             [0.0 1.0]
                                             [0.0 0.0]
                                             [1.0 0.0]
                                             [0.0 1.0]
                                             [1.0 1.0]
                                             [1.0 0.0]
                                             [0.0 1.0]
                                             [0.0 0.0]
                                             [1.0 0.0]
                                             [0.0 1.0]
                                             [1.0 1.0]
                                             [1.0 0.0]
                                             [0.0 1.0]
                                             [0.0 0.0]
                                             [1.0 0.0]
                                             [0.0 1.0]
                                             [1.0 1.0]
                                             [1.0 0.0]
                                             [1.0 1.0]
                                             [1.0 0.0]
                                             [0.0 0.0]
                                             [1.0 1.0]
                                             [0.0 1.0]
                                             [0.0 0.0]
                                             [1.0 1.0]
                                             [1.0 0.0]
                                             [0.0 0.0]
                                             [1.0 1.0]
                                             [0.0 1.0]
                                             [0.0 0.0]
                                             [1.0 1.0]
                                             [1.0 0.0]
                                             [0.0 0.0]
                                             [1.0 1.0]
                                             [0.0 1.0]
                                             [0.0 0.0]
                                             [1.0 1.0]
                                             [1.0 0.0]
                                             [0.0 0.0]
                                             [1.0 1.0]
                                             [0.0 1.0]
                                             [0.0 0.0]]
                                      :immutable? true}
                     ;; TODO: Move this to load from a file?
                     :vertices   {:id         :vertices
                                  :data       [ ;; Floor 1
                                               [-3.0  0.0 -3.0]
                                               [-3.0  0.0  3.0]
                                               [3.0  0.0  3.0]
                                               
                                               [-3.0  0.0 -3.0]
                                               [3.0  0.0 -3.0]
                                               [3.0  0.0  3.0]
                                               
                                               ;; Ceiling 1
                                               [-3.0  1.0 -3.0]
                                               [-3.0  1.0  3.0]
                                               [3.0  1.0  3.0]
                                               [-3.0  1.0 -3.0]
                                               [3.0  1.0 -3.0]
                                               [3.0  1.0  3.0]
                                               
                                               ;; A1

                                               [-2.0  1.0  -2.0]
                                               [-2.0  0.0  -2.0]
                                               [-0.5  0.0  -2.0]
                                               [-2.0  1.0  -2.0]
                                               [-0.5  1.0  -2.0]
                                               [-0.5  0.0  -2.0]
                                               
                                               ;; A2

                                               [2.0  1.0  -2.0]
                                               [2.0  0.0  -2.0]
                                               [0.5  0.0  -2.0]
                                               [2.0  1.0  -2.0]
                                               [0.5  1.0  -2.0]
                                               [0.5  0.0  -2.0]
                                               
                                               ;; B1

                                               [-2.0  1.0  2.0]
                                               [-2.0  0.0   2.0]
                                               [-0.5  0.0   2.0]
                                               [-2.0  1.0  2.0]
                                               [-0.5  1.0  2.0]
                                               [-0.5  0.0   2.0]
                                               
                                               ;; B2

                                               [2.0  1.0  2.0]
                                               [2.0  0.0   2.0]
                                               [0.5  0.0   2.0]
                                               [2.0  1.0  2.0]
                                               [0.5  1.0  2.0]
                                               [0.5  0.0   2.0]
                                               
                                               ;; C1

                                               [-2.0  1.0  -2.0]
                                               [-2.0  0.0   -2.0]
                                               [-2.0  0.0   -0.5]
                                               [-2.0  1.0  -2.0]
                                               [-2.0  1.0  -0.5]
                                               [-2.0  0.0   -0.5]
                                               
                                               ;; C2

                                               [-2.0  1.0   2.0]
                                               [-2.0  0.0   2.0]
                                               [-2.0  0.0   0.5]
                                               [-2.0  1.0  2.0]
                                               [-2.0  1.0  0.5]
                                               [-2.0  0.0   0.5]
                                               
                                               ;; D1

                                               [2.0  1.0  -2.0]
                                               [2.0  0.0   -2.0]
                                               [2.0  0.0   -0.5]
                                               [2.0  1.0  -2.0]
                                               [2.0  1.0  -0.5]
                                               [2.0  0.0   -0.5]
                                               
                                               ;; D2

                                               [2.0  1.0  2.0]
                                               [2.0  0.0   2.0]
                                               [2.0  0.0   0.5]
                                               [2.0  1.0  2.0]
                                               [2.0  1.0  0.5]
                                               [2.0  0.0   0.5]
                                               
                                               ;; Upper hallway - L
                                               [-0.5  1.0  -3.0]
                                               [-0.5  0.0   -3.0]
                                               [-0.5  0.0   -2.0]
                                               [-0.5  1.0  -3.0]
                                               [-0.5  1.0  -2.0]
                                               [-0.5  0.0   -2.0]
                                               
                                               ;; Upper hallway - R
                                               [0.5  1.0  -3.0]
                                               [0.5  0.0   -3.0]
                                               [0.5  0.0   -2.0]
                                               [0.5  1.0  -3.0]
                                               [0.5  1.0  -2.0]
                                               [0.5  0.0   -2.0]
                                               
                                               ;; Lower hallway - L
                                               [-0.5  1.0  3.0]
                                               [-0.5  0.0   3.0]
                                               [-0.5  0.0   2.0]
                                               [-0.5  1.0  3.0]
                                               [-0.5  1.0  2.0]
                                               [-0.5  0.0   2.0]
                                               
                                               ;; Lower hallway - R
                                               [0.5  1.0  3.0]
                                               [0.5  0.0   3.0]
                                               [0.5  0.0   2.0]
                                               [0.5  1.0  3.0]
                                               [0.5  1.0  2.0]
                                               [0.5  0.0   2.0]
                                               

                                               ;; Left hallway - Lw

                                               [-3.0  1.0  0.5]
                                               [-3.0  0.0   0.5]
                                               [-2.0  0.0   0.5]
                                               [-3.0  1.0  0.5]
                                               [-2.0  1.0  0.5]
                                               [-2.0  0.0   0.5]
                                               
                                               ;; Left hallway - Hi

                                               [-3.0  1.0  -0.5]
                                               [-3.0  0.0   -0.5]
                                               [-2.0  0.0   -0.5]
                                               [-3.0  1.0  -0.5]
                                               [-2.0  1.0  -0.5]
                                               [-2.0  0.0   -0.5]
                                               
                                               ;; Right hallway - Lw

                                               [3.0  1.0  0.5]
                                               [3.0  0.0   0.5]
                                               [2.0  0.0   0.5]
                                               [3.0  1.0  0.5]
                                               [2.0  1.0  0.5]
                                               [2.0  0.0   0.5]
                                               
                                               ;; Right hallway - Hi

                                               [3.0  1.0  -0.5]
                                               [3.0  0.0   -0.5]
                                               [2.0  0.0   -0.5]
                                               [3.0  1.0  -0.5]
                                               [2.0  1.0 -0.5]
                                               [2.0  0.0   -0.5]]
                                  :immutable? true}
                     :mv         (mat/matrix44)
                     :p          (get-perspective-matrix width height)
                     :camera     {:pitch 0
                                  :yaw   0
                                  :x     0
                                  :y     0.5
                                  :z     0}}}))

(defn draw-fn [gl driver program]
  (fn [state]
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (let [{:keys [p mv texture
                  camera
                  poly-count texture-coords
                  vertices]} (:scene state)
                  time-now     (.getTime (js/Date.))]
      (let [mv (-> mv
                   ;;(geom/rotate-around-axis [1 0 0] (:pitch camera))
                   (geom/rotate-around-axis [0 1 0] (- (:yaw camera)))
                   (geom/translate [(- (:x camera))
                                    (- (:y camera))
                                    (- (:z camera))]))]
        (gd/draw-arrays driver (gd/bind driver program
                                        (get-data p mv vertices texture texture-coords))
                        {:draw-mode :triangles})))))  

(defn animate [draw-fn step-fn current-value]
  (js/requestAnimationFrame
   (fn [time]
     (let [next-value (swap! app-state step-fn time)]
       (draw-fn next-value)
       (animate draw-fn step-fn next-value)))))

(def effective-fpms
  (/ 60 1000))

(defn tick
  "Takes the old world value and produces a new world value, suitable
  for rendering"
  [state time]
  ;; We get the elapsed time since the last render to compensate for
  ;; lag, etc.
  (let [time-now   (.getTime (js/Date.))
        elapsed    (- time-now (:last-rendered state))
        movement   (:movement state)
        walk-speed (:forward movement 0)
        turn-speed (:yaw movement 0)
        camera     (get-in state [:scene :camera])
        ns         (-> state
                       (update-in [:scene :camera :x] (fn [x] (- x (* (js/Math.sin (:yaw camera)) walk-speed elapsed))))
                       (update-in [:scene :camera :z] (fn [z] (- z (* (js/Math.cos (:yaw camera)) walk-speed elapsed))))
                       (update-in [:scene :camera :yaw] + (* turn-speed elapsed))
                       (assoc-in [:last-rendered] time-now))]
    ns))

(defn main [node]
  (let [gl      (.getContext node "webgl")
        width   (.-clientWidth node)
        height  (.-clientHeight node)
        driver  (driver/basic-driver gl)
        program program-source
        state   (atom (make-app-state width height))]
    (def app-state state)
    (reset-gl-canvas! node)
    (.enable gl (.-DEPTH_TEST gl))
    (.clearColor gl 0 0 0 1)
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (let [image (js/Image.)]
      (aset image "onload"
            (fn [] (let [texture {:data       image
                                 :filter     {:min :linear
                                              :mag :nearest}
                                 :flip-y     true
                                 :texture-id 0}]
                    (swap! state assoc-in [:scene :texture] texture)
                    (set! (.-debugRedrawScene js/window)
                          (fn []
                            (animate (draw-fn gl driver program) tick state)))
                    (animate (draw-fn gl driver program) tick state))))
      (aset image "src" "/images/hardwood.jpg"))
    (let [keycodes {37 :left
                    38 :up
                    39 :right
                    40 :down
                    65 :a
                    68 :d
                    87 :w
                    83 :s}]
      (js/document.addEventListener "keydown"
                                    (fn [event]
                                      (let [keycode (.-which event)
                                            key (get keycodes keycode)
                                            directions {:w     :forward
                                                        :up    :forward
                                                        :s     :backward
                                                        :down  :backward
                                                        :a     :left
                                                        :left  :left
                                                        :d     :right
                                                        :right :right}
                                            [direction speed] (condp = key
                                                                :w     [:forward 0.001]
                                                                :up    [:forward 0.001]
                                                                :a     [:yaw 0.001]
                                                                :left  [:yaw 0.001]
                                                                :d     [:yaw -0.001]
                                                                :right [:yaw -0.001]
                                                                :s     [:forward -0.001]
                                                                :down  [:forward -0.001]
                                                                nil)]
                                        (when direction
                                          (.preventDefault event)
                                          (swap! state assoc-in [:movement direction] speed)))))
      (js/document.addEventListener "keyup"
                                    (fn [event]
                                      (let [keycode (.-which event)
                                            key (get keycodes keycode)
                                            directions {:w     :forward
                                                        :up    :forward
                                                        :s     :backward
                                                        :down  :backward
                                                        :a     :left
                                                        :left  :left
                                                        :d     :right
                                                        :right :right}
                                            [direction speed] (condp = key
                                                                :w     [:forward 0.0]
                                                                :up    [:forward 0.0]
                                                                :a     [:yaw 0.0]
                                                                :left  [:yaw 0.0]
                                                                :d     [:yaw 0.0]
                                                                :right [:yaw 0.0]
                                                                :s     [:forward 0.0]
                                                                :down  [:forward 0.0]
                                                                nil)]
                                        (when direction
                                          (.preventDefault event)
                                          (swap! state assoc-in [:movement direction] speed))))))))

