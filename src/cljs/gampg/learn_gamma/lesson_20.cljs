(ns gampg.learn-gamma.lesson-20
  (:require [gamma.api :as g]
            [gamma.program :as p]
            [gamma-driver.api :as gd]
            [gamma-driver.drivers.basic :as driver]
            [goog.webgl :as ggl]
            [thi.ng.geom.core :as geom]
            [thi.ng.geom.core.matrix :as mat :refer [M44]]))

;; Skyboxes from http://www.humus.name/index.php?page=Textures&start=24

;; With some better architecture,
;; this could easily go away
(declare app-state)

(def title
  "20. Skybox")

(def u-p-matrix
  (g/uniform "uPMatrix" :mat4))

(def u-inverse-mv-matrix
  (g/uniform "uInverseMVMatrix" :mat4))

(def a-position
  (g/attribute "aVertexPosition" :vec2))

(def a-texture-coord
  (g/attribute "aTextureCoord" :vec3))

(def v-texture-coord
  (g/varying "vTextureCoord" :vec3 :highp))

(def u-sampler
  (g/uniform "uSampler" :samplerCube))

(def program-sky-box
  (p/program
   {:vertex-shader   {(g/gl-position) (g/vec4 a-position 0 1)
                      v-texture-coord (-> u-inverse-mv-matrix
                                          (g/* u-p-matrix)
                                          (g/* (g/gl-position))
                                          (g/swizzle :xyz))}
    :fragment-shader {(g/gl-frag-color) (g/textureCube u-sampler v-texture-coord)}
    :precision       {:float :mediump}}))

(defn get-perspective-matrix
  "Be sure to 
  1. pass the WIDTH and HEIGHT of the canvas *node*, not
  the GL context
  2. (set! (.-width/height canvas-node)
  width/height), respectively, or you may see no results, or strange
  results"
  [width height & [fov]]
  (mat/perspective (or fov 45) (/ width height) 0.1 100))

(defn get-normal-matrix [mv]
  (-> mv
      (geom/invert)
      (geom/transpose)
      (mat/matrix44->matrix33)))

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

(defn make-app-state [width height]
  {:last-rendered 0
   :canvas        {:width  width
                   :height height}
   :scene         {:mv (mat/matrix44)
                   :p  (get-perspective-matrix width height)
                   :skybox {:fov 130}}})

(defn get-data [p mv texture texture-coords]
  {u-p-matrix          p
   u-inverse-mv-matrix mv
   u-sampler           texture
   a-position          {:data [-1 -1, 3 -1, -1 3]
                        :count 3} ;; vertices
   a-texture-coord     texture-coords})

(defn get-perspective-matrix-inverse [fov width height]
  (let [near   0.1
        far    100
        aspect (/ width height)
        h      (js/Math.tan (* 0.5 fov))
        w      (* h aspect)
        z0     (/ (- far near) (* -2 far near))
        z1     (/ (+ far near) (* -2 far near))]
    (mat/matrix44 [w 0 0 0
                   0 h 0 0
                   0 0 0 z0
                   0 0 1 z1])))

(defn rotate-x-y [ax ay]
  (let [cos-x (js/Math.cos ax)
        sin-x (js/Math.sin ax)
        cos-y (js/Math.cos ay)
        sin-y (js/Math.sin ay)]
    (mat/matrix44 [cos-y                0     (- sin-y)           0
                   (* (- sin-x) sin-y)  cos-x (* (- sin-x cos-y)) 0
                   (* cos-x sin-y)      sin-x (* cos-x cos-y)     0
                   0                    0     0                   1])))

(defn draw-sky-box [driver program p sky-box ax ay]
  (let [mv         (-> (rotate-x-y ax ay)
                       (geom/invert))
        final-data (select-keys (get-data p mv sky-box nil)
                                (:inputs program))]
    (gd/draw-arrays driver (gd/bind driver program final-data)
                    {:draw-mode :triangles
                     :count 3})))

(defn draw-fn [gl driver program]
  (fn [state]
    (let [now (.getTime (js/Date.))
          rot (* 2 js/Math.PI (/ now 10000))
          {:keys [width height]} (:canvas state)
          p (get-perspective-matrix width height 130)]
      (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
      (draw-sky-box driver program p (get-in state [:skybox :texture]) 0 rot))))

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
        ns         (-> state
                       (assoc-in [:last-rendered] time-now))]
    ns))

(defn load-cube-map [gl base cb]
  (let [loader  (atom [[0 (str base "/" "posx.jpg") ggl/TEXTURE_CUBE_MAP_POSITIVE_X nil]
                       [1 (str base "/" "negx.jpg") ggl/TEXTURE_CUBE_MAP_NEGATIVE_X nil]
                       [2 (str base "/" "posy.jpg") ggl/TEXTURE_CUBE_MAP_POSITIVE_Y nil]
                       [3 (str base "/" "negy.jpg") ggl/TEXTURE_CUBE_MAP_NEGATIVE_Y nil]
                       [4 (str base "/" "posz.jpg") ggl/TEXTURE_CUBE_MAP_POSITIVE_Z nil]
                       [5 (str base "/" "negz.jpg") ggl/TEXTURE_CUBE_MAP_NEGATIVE_Z nil]])]
    (doseq [[n src face loaded?] @loader]
      (let [img (js/Image.)]
        (set! (.-onload img) ((fn [texture face image]
                                (fn []
                                  (swap! loader (fn [loader]
                                                         (update-in loader [n] (fn [[n src face _]]
                                                                                 [n src face img]))))
                                  (when (every? last @loader)
                                    (let [faces @loader
                                          final {:faces {:x [(last (nth faces 0))
                                                             (last (nth faces 1))]
                                                         :y [(last (nth faces 2))
                                                             (last (nth faces 3))]
                                                         :z [(last (nth faces 4))
                                                             (last (nth faces 5))]}
                                                 :filter {:min :linear
                                                          :mag :linear}}]
                                      (js/console.log "Loaded all images: " (clj->js final))
                                      (cb final))))) face img))
        (set! (.-src img) src)))))

(defn main [gl node]  
  (let [width   (.-clientWidth node)
        height  (.-clientHeight node)
        driver  (driver/basic-driver gl)
        program (gd/program driver program-sky-box)
        state   (atom (make-app-state width height))]
    (def app-state state)
    (reset-gl-canvas! node)    (.enable gl (.-DEPTH_TEST gl))
    (.clearColor gl 0 0 0 1)
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (load-cube-map gl "/images/skybox/citadella"
                   (fn [cube-texture]
                     (swap! state assoc-in [:skybox :texture] cube-texture)
                     (let [image (js/Image.)]
                       (aset image "onload"
                             (fn [] (let [texture {:data       image
                                                  :filter     {:min :linear
                                                               :mag :nearest}
                                                  :flip-y     true}]
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
                                                             (swap! state assoc-in [:movement direction] speed))))))))))





