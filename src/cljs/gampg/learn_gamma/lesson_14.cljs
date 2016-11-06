(ns ^:figwheel-always gampg.learn-gamma.lesson-14
    (:require [cljs.core.async :as async :refer [<! put! chan]]
              [gamma.api :as g]
              [gamma.program :as p]
              [gamma-driver.api :as gd]
              [gamma-driver.drivers.basic :as driver]
              [gampg.utils :as utils]
              [thi.ng.geom.core :as geom]
              [thi.ng.geom.core.matrix :as mat :refer [M44]])
    (:require-macros [cljs.core.async.macros :as async :refer [go]]))

(def title
  "14. Specular highlights and loading a JSON model")

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

(def u-point-lighting-location
  (g/uniform "uPointLightingLocation" :vec3))

(def u-point-lighting-color
  (g/uniform "uPointLightingColor" :vec3))

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

(def v-transformed-normal
  (g/varying "vTransformedNormal" :vec3 :mediump))

(def v-position
  (g/varying "vPosition" :vec4 :highp))

(def u-sampler
  (g/uniform "uSampler" :sampler2D))

(def u-material-shininess
  (g/uniform "uMaterialShininess" :float))

(def u-point-lighting-diffuse-color
  (g/uniform "uPointLightingSpecularColor" :vec3))

(def u-point-lighting-specular-color
  (g/uniform "uPointLightingSpecularColor" :vec3))

(def program-specular
  (p/program
   {:vertex-shader   {v-position           (g/* u-mv-matrix (g/vec4 a-position 1))
                      (g/gl-position)      (g/* u-p-matrix v-position)
                      v-texture-coord      a-texture-coord
                      v-transformed-normal (g/* u-n-matrix a-vertex-normal)}
    :fragment-shader (let [v-pos-xyz                 (g/swizzle v-position :xyz)
                           light-direction           (g/normalize (g/- u-point-lighting-location v-pos-xyz))
                           normal                    (g/normalize v-transformed-normal)
                           direction-light-weighting (g/max (g/dot (g/normalize v-transformed-normal) light-direction) 0)
                           eye-direction             (-> (g/* -1 v-pos-xyz)
                                                         g/normalize)
                           reflection-direction      (g/reflect (g/* -1 light-direction) normal)
                           specular-light-weighting  (-> (g/dot reflection-direction eye-direction)
                                                         (g/max 0)
                                                         (g/power u-material-shininess))
                           diffuse-light-weighting (-> (g/dot normal light-direction)
                                                       (g/max 0))
                           light-weighting           (-> (g/+ u-ambient-color u-point-lighting-specular-color)
                                                         (g/* specular-light-weighting)
                                                         (g/+ u-point-lighting-diffuse-color)
                                                         (g/* direction-light-weighting))
                           texture-color             (g/texture2D u-sampler (g/vec2 (g/swizzle v-texture-coord :st)))
                           a                         (g/swizzle texture-color :a)
                           rgb                       (g/* (g/swizzle texture-color :rgb) light-weighting)]
                       {(g/gl-frag-color) (do (g/vec4 rgb (g/swizzle texture-color :a))
                                              (g/vec4 (g/* (g/swizzle texture-color :rgb)
                                                           light-weighting) 1))})
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

(defn app-state [width height]
  {:last-rendered 0
   :scene         {:mv (mat/matrix44)
                   :p  (get-perspective-matrix width height)}})

(defn get-data [p mv vertices normals texture texture-coords point-lighting-location]
  (let [now           (/ (.getTime (js/Date.)) 1000)
        use-lighting? true]
    {u-p-matrix                      p
     u-mv-matrix                     mv
     u-n-matrix                      (get-normal-matrix mv)
     u-ambient-color                 [0.2 0.2 0.2]
     u-alpha                         1
     u-lighting-direction            [-0.25 0.25 1]
     u-directional-color             [0.8 0.8 0.8]
     u-point-lighting-location       point-lighting-location
     u-point-lighting-diffuse-color  [0.8 0.8 0.8]
     u-point-lighting-specular-color [0.8 0.8 0.8]
     u-material-shininess            32
     u-sampler                       texture
     u-use-lighting                  use-lighting?
     a-position                      vertices
     a-texture-coord                 texture-coords
     a-vertex-normal                 normals}))

(defn draw-fn [gl driver programs]
  (fn [state]
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (let [{:keys [p mv
                  texture model
                  rotation]}      (:scene state)
                  now                     (/ (.getTime (js/Date.)) 1000)
                  rotation                (* js/Math.PI (js/Math.sin now))
                  program                 (get programs :specular)
                  point-lighting-location {:data       #js[-10 4 -20]
                                           :immutable? false}
                  model-mv                 (-> mv
                                               (geom/translate [-4 0 -40])
                                               (geom/* (-> (mat/matrix44)
                                                           (geom/rotate-around-axis [1 0 0] 0.7719505037767067)
                                                           (geom/rotate-around-axis [0 1 0] rotation)
                                                           )))
                  model-scene            (-> (get-data p model-mv (:vertices model) (:normals model) texture (:texture-coords model) point-lighting-location)
                                             (select-keys (:inputs program))
                                             (assoc {:tag :element-index} (:indices model)))]
      (gd/draw-elements driver
                        (gd/bind driver program model-scene)
                        {:draw-mode :triangles
                         :first     0
                         :count     (get-in model [:indices :count])}))))

(def manual-step-frame-by-frame?
  (do
    true
    false
    ))

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

(defn process-immutable-json-model [id model-json key-mapping]
  (let [process-key (fn [[json-key edn-key]]
                      {edn-key {:id         (keyword (str (name id) "-" (name edn-key)))
                                :immutable? true
                                :data       (js->clj (aget model-json json-key))}})
        model       (reduce merge {} (map process-key key-mapping))
        indices-count (when-let [data (get-in model [:indices :data])]
                        (if-let [length (.-length data)]
                          length
                          (count data)))]
    (if indices-count
      (assoc-in model [:indices :count] indices-count)
      model)))

(defn main [_ node]
  (let [gl       (.getContext node "webgl")
        width    (.-clientWidth node)
        height   (.-clientHeight node)
        driver   (driver/basic-driver gl)
        programs {:specular (gd/program driver program-specular)}
        state    (time (app-state width height))]
    (reset-gl-canvas! node)
    (.enable gl (.-DEPTH_TEST gl))
    (.clearColor gl 0 0 0 1)
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (go
      (let [texture-loaded-ch (chan)
            texture-source    "/images/metal.jpg"
            _                 (utils/load-texture! texture-source #(put! texture-loaded-ch %))
            model-loaded-ch   (chan)
            model-source      "/models/teapot.json"
            _                 (utils/http-get model-source #(put! model-loaded-ch %))
            model-json        (js/JSON.parse (<! model-loaded-ch))
            model             (process-immutable-json-model :teapot model-json {"vertexPositions"     :vertices
                                                                                "vertexNormals"       :normals
                                                                                "vertexTextureCoords" :texture-coords
                                                                                "indices"             :indices})
            image             (<! texture-loaded-ch)
            texture           {:data       image
                               :filter     {:min :linear
                                            :mag :nearest}
                               :flip-y     true
                               :immutable? true}
            next-tick         (fn [] (animate (draw-fn gl driver programs) tick (-> state
                                                                                   (assoc-in [:scene :texture] texture)
                                                                                   (assoc-in [:scene :model] model))))]
        (if manual-step-frame-by-frame?
          (set! (.-tick js/window) next-tick)
          (next-tick))))))

(def explanation
  nil)

(def summary
  {:title       title
   :enter       main
   :explanation explanation})
