(ns ^:figwheel-always gampg.learn-gamma.lesson-11
  (:require [gamma.api :as g]
            [gamma.program :as p]
            [gamma-driver.api :as gd]
            [gamma-driver.drivers.basic :as driver]
            [thi.ng.geom.core :as geom]
            [thi.ng.geom.core.matrix :as mat :refer [M44]]))

(def title
  "11. Spheres, rotation matrices, and mouse events")

(def u-p-matrix
  (g/uniform "uPMatrix" :mat4))

(def u-mv-matrix
  (g/uniform "uMVMatrix" :mat4))

(def u-n-matrix
  (g/uniform "uNMatrix" :mat3))

(def u-ambient-color
  (g/uniform "uAmbientColor" :vec3))

(def u-lighting-direction
  (g/uniform "uLightingDirection" :vec3))

(def u-directional-color
  (g/uniform "uDirectionalColor" :vec3))

(def u-use-lighting
  (g/uniform "uUseLighting" :bool))

(def u-alpha
  (g/uniform "uAlpha" :float))

(def a-position
  (g/attribute "aVertexPosition" :vec3))

(def a-vertex-normal
  (g/attribute "aVertexNormal" :vec3))

(def a-texture-coord
  (g/attribute "aTextureCoord" :vec2))

(def v-texture-coord
  (g/varying "vTextureCoord" :vec2 :mediump))

(def v-light-weighting
  (g/varying "vLightWeighting" :vec3 :mediump))

(def u-sampler
  (g/uniform "uSampler" :sampler2D))

(def program-source
  (p/program
   {:vertex-shader   {(g/gl-position)   (-> u-p-matrix
                                            (g/* u-mv-matrix)
                                            (g/* (g/vec4 a-position 1)))
                      v-texture-coord   a-texture-coord
                      v-light-weighting (g/if (g/not u-use-lighting)
                                          (let [transformed-normal          (g/* u-n-matrix a-vertex-normal)
                                                directional-light-weighting (g/max (g/dot transformed-normal u-lighting-direction) 0.0)]
                                            (g/* directional-light-weighting
                                                 (g/+ u-ambient-color u-directional-color)))
                                          (g/vec3 1 1 1))}
    :fragment-shader (let [texture-color (g/texture2D u-sampler (g/vec2 (g/swizzle v-texture-coord :st)))
                           rgb           (g/* (g/swizzle texture-color :rgb) v-light-weighting)
                           a             (g/swizzle texture-color :a)]
                       {(g/gl-frag-color) (g/vec4 rgb (g/* a u-alpha))})
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
(defn app-state [width height sphere]
  {:last-rendered 0
   :scene         {:sphere sphere
                   :mv     (mat/matrix44)
                   :p      (get-perspective-matrix width height)}})

(defn get-data [p mv vertices normals texture texture-coords]
  (let [now           (/ (.getTime (js/Date.)) 1000)
        use-lighting? true]
    {u-p-matrix           p
     u-mv-matrix          mv
     u-n-matrix           (get-normal-matrix mv)
     u-ambient-color      [0 0 0]
     u-alpha              1
     u-lighting-direction [-0.25 0.25 1]
     u-directional-color  [0.8 0.8 0.8]
     u-sampler            texture
     u-use-lighting       use-lighting?
     a-position           vertices
     a-texture-coord      texture-coords
     a-vertex-normal      normals}))

(defn draw-fn [gl driver program]
  (fn [state]
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (let [{:keys [p mv
                  texture sphere
                  rotation]} (:scene state)
                  now (/ (.getTime (js/Date.)) 1000)
                  rotation (* js/Math.PI (js/Math.sin now))]
      (let [mv         (-> mv
                           (geom/translate [0 0 -10])
                           (geom/rotate-around-axis [1 0 0] rotation)
                           (geom/rotate-around-axis [0 -1 0] (/ rotation 10)))
            scene-data (select-keys (get-data p mv (:vertices sphere) (:normals sphere) texture (:texture-coords sphere))
                                    (:inputs program))
            scene-data (assoc scene-data
                              {:tag :element-index} (:indices sphere))]
        (gd/draw-elements driver
                          (gd/bind driver program scene-data)
                          {:draw-mode :triangles
                           :first     0
                           :count     (get-in sphere [:indices :count])})))))  

(def manual-step-frame-by-frame?
  false)

(defn animate [draw-fn step-fn current-value]
  (js/requestAnimationFrame
   (fn [time]
     (let [next-value (step-fn time current-value)]
       (draw-fn next-value)
       (if manual-step-frame-by-frame?
         (set! (.-tick js/window)
               #(animate draw-fn step-fn next-value))
         (animate draw-fn step-fn next-value))))))

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

;; TODO: Convert this to use transducers, it's waaaaaaaaaaay to slow,
;; unnecessarily
(defn generate-sphere [latitude-bands longitude-bands radius]
  (let [raw-sphere      (vec (mapcat (fn [lat-number]
                                       (let [theta (/ (* lat-number js/Math.PI) latitude-bands)
                                             sin-theta (js/Math.sin theta)
                                             cos-theta (js/Math.cos theta)]
                                         (mapv (fn [long-number]
                                                 (let [phi           (/ (* long-number 2 js/Math.PI) longitude-bands)
                                                       sin-phi       (js/Math.sin phi)
                                                       cos-phi       (js/Math.cos phi)
                                                       x             (* cos-phi sin-theta)
                                                       y             cos-theta
                                                       z             (* sin-phi sin-theta)
                                                       u             (- 1 (/ long-number longitude-bands))
                                                       v             (- 1 (/ lat-number latitude-bands))
                                                       normal        [x y z]
                                                       texture-coord [u v]
                                                       vertex        [(* x radius) (* y radius) (* z radius)]]
                                                   [normal texture-coord vertex])) (range (inc longitude-bands))))) (range (inc latitude-bands))))
        sphere (reduce (fn [run [normal texture-coord vertex]]
                         (-> run
                             (update-in [:normals :data] concat normal)
                             (update-in [:texture-coords :data] concat texture-coord)
                             (update-in [:vertices :data] concat vertex)))
                       {:normals        {:data       []
                                         :immutable? true}
                        :texture-coords {:data       []
                                         :immutable? true}
                        :vertices       {:data       []
                                         :immutable? true}} raw-sphere)
        indices (vec (mapcat (fn [lat-number]
                               (mapcat (fn [long-number]
                                         (let [fst  (+ (* lat-number (inc longitude-bands)) long-number)
                                               scnd (+ fst longitude-bands 1)]
                                           [fst scnd (inc fst)
                                            scnd (inc scnd) (inc fst)])) (range longitude-bands))) (range latitude-bands)))]
    (assoc sphere :indices {:data       indices
                            :count      (count indices)
                            :immutable? true})))

(defn main [gl node]  
  (let [width   (.-clientWidth node)
        height  (.-clientHeight node)
        driver  (driver/basic-driver gl)
        program (gd/program driver program-source)
        sphere  (time (generate-sphere 30 30 2))
        state   (time (app-state width height sphere))]
    (reset-gl-canvas! node)
    ;; Set the blending function
    ;;(.blendFunc gl (.-SRC_ALPHA gl) (.-ONE gl))
    ;;(.enable gl (.-BLEND gl))
    ;;(.disable gl (.-DEPTH_TEST gl))
    (.enable gl (.-DEPTH_TEST gl))
    (.clearColor gl 0 0 0 1)
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (let [image (js/Image.)]
      (aset image "onload"
            (fn [] (let [texture {:data       image
                                 :filter     {:min :linear
                                              :mag :nearest}
                                 :flip-y     true
                                 :immutable? true}]
                    (animate (draw-fn gl driver program) tick (assoc-in state [:scene :texture] texture)))))
      (aset image "src" "/images/moon.gif"))))
