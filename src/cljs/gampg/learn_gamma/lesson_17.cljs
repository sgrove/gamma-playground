(ns ^:figwheel-always gampg.learn-gamma.lesson-17
    (:require [cljs.core.async :as async :refer [<! put! chan]]
              [gamma.api :as g]
              [gamma.program :as p]
              [gamma-driver.api :as gd]
              [gamma-driver.drivers.basic :as driver]
              [gampg.learn-gamma.lesson-01 :as lesson-01]
              [gampg.learn-gamma.lesson-02 :as lesson-02]
              [gampg.utils :as utils]
              [goog.webgl :as ggl]
              [thi.ng.geom.core :as geom]
              [thi.ng.geom.core.matrix :as mat :refer [M44]])
    (:require-macros [cljs.core.async.macros :as async :refer [go]]))

(def title
  "17. Rendering videos textures")

(def u-p-matrix
  (g/uniform "uPMatrix" :mat4))

(def u-mv-matrix
  (g/uniform "uMVMatrix" :mat4))

(def u-n-matrix
  (g/uniform "uNMatrix" :mat3))

(def u-point-lighting-location
  (g/uniform "uPointLightingLocation" :vec3))

(def u-point-lighting-color
  (g/uniform "uPointLightingColor" :vec3))

(def a-position
  (g/attribute "aVertexPosition" :vec3))

(def a-vertex-normal
  (g/attribute "aVertexNormal" :vec3))

(def a-texture-coord
  (g/attribute "aTextureCoord" :vec2))

(def v-texture-coord
  (g/varying "vTextureCoord" :vec2 :mediump))

(def v-transformed-normal
  (g/varying "vTransformedNormal" :vec3 :mediump))

(def v-position
  (g/varying "vPosition" :vec4 :highp))

(def u-material-shininess
  (g/uniform "uMaterialShininess" :float))

(def u-point-lighting-diffuse-color
  (g/uniform "uPointLightingSpecularColor" :vec3))

(def u-point-lighting-specular-color
  (g/uniform "uPointLightingSpecularColor" :vec3))

(def u-material-emissive-color
  (g/uniform "uMaterialEmissiveColor" :vec3))

(def u-ambient-lighting-color
  (g/uniform "uAmbientLightingColor" :vec3))

(def u-material-ambient-color
  (g/uniform "uMaterialAmbientColor" :vec3))

(def u-material-diffuse-color
  (g/uniform "uMaterialDiffuseColor" :vec3))

(def u-material-specular-color
  (g/uniform "uMaterialDiffuseColor" :vec3))

(def u-sampler
  (g/uniform "uSampler" :sampler2D))

(def program-specular
  (p/program
   {:vertex-shader   {v-texture-coord      a-texture-coord
                      v-transformed-normal (g/* u-n-matrix a-vertex-normal)
                      v-position           (g/* u-mv-matrix (g/vec4 a-position 1))
                      (g/gl-position)      (g/* u-p-matrix v-position)}
    :fragment-shader (let [ambient-light-weighting   u-ambient-lighting-color
                           light-direction           (g/normalize (g/- u-point-lighting-location (g/swizzle v-position :xyz)))
                           normal                    (g/normalize v-transformed-normal)
                           eye-direction             (-> (g/swizzle v-position :xyz)
                                                         (g/* -1)
                                                         (g/normalize))
                           reflection-direction      (-> (g/* -1 light-direction)
                                                         (g/reflect normal))
                           specular-light-brightness (-> (g/dot reflection-direction eye-direction)
                                                         (g/max 0)
                                                         (g/pow u-material-shininess))
                           specular-light-weighting  (g/* u-point-lighting-diffuse-color specular-light-brightness)
                           diffuse-light-brightness  (-> (g/dot normal light-direction)
                                                         (g/max 0))
                           diffuse-light-weighting   (g/* u-point-lighting-diffuse-color diffuse-light-brightness)
                           texture-color             (g/texture2D u-sampler (g/swizzle v-texture-coord :st))
                           texture-rgb               (g/swizzle texture-color :rgb)
                           alpha                     (g/swizzle texture-color :a)
                           material-ambient-color    (g/* u-material-ambient-color texture-rgb)
                           material-diffuse-color    (g/* u-material-diffuse-color texture-rgb)
                           material-specular-color   (g/* u-material-specular-color texture-rgb)
                           material-emissive-color   (g/* u-material-emissive-color texture-rgb)
                           sum-color                 (-> (g/* material-ambient-color ambient-light-weighting)
                                                         (g/+ (g/* material-diffuse-color diffuse-light-weighting))
                                                         (g/+ (g/* material-specular-color specular-light-weighting))
                                                         (g/+ material-emissive-color)
                                                         (g/vec4 alpha))]
                       {(g/gl-frag-color) sum-color})
    :precision {:float :mediump}}))

;; TODO: Move this into core, and then pass in initial-query-map as
;; part of the app-state.
(def initial-query-map
  (let [parsed-uri (goog.Uri. (.. js/window -location -href))
        ks         (.. parsed-uri getQueryData getKeys)
        defaults   {:tick-first-frame?    false
                    :manual-tick?         false
                    :capture-first-frame? false}
        initial    (reduce merge {} (map (partial utils/uri-param parsed-uri) (clj->js ks)))]
    (merge defaults initial)))

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
   :debug-keys    [[:scene :mv]
                   [:last-rendered]]
   :scene         {:mv              (mat/matrix44)
                   :p               (get-perspective-matrix width height)
                   :square-vertices {:data       [[ 1  1  0]
                                                  [-1  1  0]
                                                  [ 1 -1  0]
                                                  [-1 -1  0]]
                                     :immutable? true
                                     :id         :square-vertices}
                   :square-colors   {:data       [[1 0 0 1]
                                                  [0 1 0 1]
                                                  [0 0 1 1]
                                                  [1 1 1 1]]
                                     :immutable? true
                                     :id         :square-colors}}
   :canvas {:width  width
            :height height}})

(def laptop-screen
  {:vertices       {:data       [ 0.580687 0.659 0.813106
                                 -0.580687 0.659 0.813107
                                  0.580687 0.472 0.113121
                                 -0.580687 0.472 0.113121]
                    :immutable? true
                    :id         :laptop-screen-vertices}
   :normals        {:data       [0.000000 -0.965926 0.258819
                                 0.000000 -0.965926 0.258819
                                 0.000000 -0.965926 0.258819
                                 0.000000 -0.965926 0.258819]
                    :immutable? true
                    :id         :laptop-screen-normals}
   :texture-coords {:data       [0.0 0.0
                                 1.0 0.0
                                 0.0 1.0
                                 1.0 1.0]
                    :immutable? true
                    :id         :laptop-screen-texture-coords}})

(defn get-data [p mv vertices normals color-texture texture-coords point-lighting-location diffuse-color emissive-color ambient-color]
  (let [now (/ (.getTime (js/Date.)) 1000)]
    {u-p-matrix                      p
     u-mv-matrix                     mv
     u-n-matrix                      (get-normal-matrix mv)
     u-ambient-lighting-color        [0.8 0.8 0.8]
     u-point-lighting-location       point-lighting-location
     u-point-lighting-diffuse-color  [0.8 0.8 0.8]
     u-point-lighting-specular-color [0.8 0.8 0.8]
     u-material-ambient-color        ambient-color
     u-material-diffuse-color        diffuse-color
     u-material-emissive-color       emissive-color
     u-material-shininess            32
     u-sampler                       color-texture
     a-position                      vertices
     a-texture-coord                 texture-coords
     a-vertex-normal                 normals}))

(defn deg->rad [degrees]
  (-> (* degrees js/Math.PI)
      (/ 180)))

(defn draw-fn [gl driver programs]
  (fn [state]
    (let [{:keys [p mv
                  color-texture specular-texture
                  model
                  rotation]}      (:scene state)
                  now                     (/ (.getTime (js/Date.)) 50)
                  rotation                (- (deg->rad now))
                  program                 (get programs :simple)
                  point-lighting-location {:data       #js[-10 4 0]
                                           :immutable? false}
                  square-mv               (-> mv
                                              (geom/translate [(* (js/Math.sin (/ now 8)) 4) (* (js/Math.cos (/ now 3)) 2) -5])
                                              (geom/* (-> (mat/matrix44)
                                                          (geom/rotate-around-axis [0 1 0] rotation)
                                                          (geom/rotate-around-axis [1 0 0] (- (/ js/Math.PI 3)))))
                                              object-array)
                  model-mv                (-> mv
                                              (geom/translate [0 -0.5 -1.5])
                                              (geom/* (-> (mat/matrix44)
                                                          (geom/rotate-around-axis [0 1 0] rotation)
                                                          (geom/rotate-around-axis [1 0 0] (- (/ js/Math.PI 2)))
                                                          )))
                  scene-data              (-> (get-data p model-mv (:vertices model) (:normals model) color-texture (:texture-coords model) point-lighting-location #js[0.2 0.2 0.2]  #js[0.2 0.2 0.2]  #js[0.2 0.2 0.2])
                                              (select-keys (get-in programs [:specular :inputs]))
                                              (assoc {:tag :element-index} (:indices model)))
                  screen-data             (-> (get-data p model-mv (:vertices laptop-screen) (:normals laptop-screen) (get-in state [:scene :video-texture]) (:texture-coords laptop-screen) point-lighting-location #js[0.2 0.2 0.2]  #js[0.2 0.2 0.2]  #js[0.2 0.2 0.2])
                                              (select-keys (get-in programs [:specular :inputs]))
                                              (assoc {:tag :element-index} (:indices model)))]
      (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
      (gd/bind driver (get programs :specular) screen-data)
      (gd/draw-arrays driver (get programs :specular) {:draw-mode :triangle-strip
                                                       :count     4})
      (gd/draw-elements driver (gd/bind driver (get programs :specular) scene-data) {:draw-mode :triangles
                                                                                     :first     0
                                                                                     :count     (get-in model [:indices :count])}))))

(defn animate [draw-fn step-fn state]
  (js/requestAnimationFrame
   (fn [time]
     (let [next-value (swap! state step-fn time)]
       (draw-fn next-value)
       (if (:manual-tick? initial-query-map)
         (set! (.-tick js/window)
               #(animate draw-fn step-fn state))
         (animate draw-fn step-fn state))))))

(defn tick
  "Takes the old world value and produces a new world value, suitable
  for rendering"
  [state time]
  ;; We get the elapsed time since the last render to compensate for
  ;; lag, etc.
  (let [time-now     (.getTime (js/Date.))
        elapsed      (- time-now (:last-rendered state))
        cube-diff    (/ (* 75 elapsed) 100000)]
    (-> state
        (update-in [:scene :cube-rotation] + cube-diff)
        (assoc-in [:last-rendered] time-now))))

(defn my-input-fn [driver program binder-fn variable old-spec new-spec]
  (let [t (:tag new-spec)]
    (if (and false old-spec new-spec (not= :uniform t) (not= t :variable) (not= :texture t) (= (old-spec t) (new-spec t)))
      old-spec
      (if (and (= :uniform t) (:immutable? old-spec)
               (not= :texture (:type variable))
               (not= :samplerCube (:type variable)))
        old-spec
        (binder-fn (gd/gl driver) program variable new-spec)))))

(defn make-driver [gl]
  (driver/map->BasicDriver
   {:gl             gl
    :resource-state (atom {})
    :mapping-fn     (fn [x] (or (:id x) (:element x) x))
    :input-state    (atom {})
    :input-fn       my-input-fn
    :produce-fn     driver/default-produce-fn}))

(defn main [global-app-state node]
  (let [gl          (.getContext node "webgl")
        width       (.-clientWidth node)
        height      (.-clientHeight node)
        driver      (make-driver gl)
        programs    {:specular (gd/program driver program-specular)
                     :simple   (gd/program driver lesson-02/program-source)}
        ;; WxH must be a power of two (e.g. 64, 128, 256, 512, 1024, etc.)
        local-state (-> (app-state width height)
                        (assoc-in [:scene :square :vertices] lesson-02/square-vertices)
                        (assoc-in [:scene :square :colors] lesson-02/square-colors))
        _           (swap! global-app-state merge local-state)
        state       global-app-state]
    (reset-gl-canvas! node)
    (.enable gl (.-DEPTH_TEST gl))
    (.clearColor gl 0 0 0 1)
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (go
      (let [video-ch             (chan)
            _                    (utils/make-video-element "video-1" "/videos/Firefox.ogv"
                                                           (fn [element]
                                                             (put! video-ch element)
                                                             (.play element))
                                                           (fn [element]
                                                             (js/console.log "Element " element " has finished playing")))
            texture-loaded-ch    (chan)
            color-texture-source "/images/earth.jpg"
            _                    (utils/load-texture! color-texture-source #(put! texture-loaded-ch %))
            model-loaded-ch      (chan)
            model-source         "/models/laptop.json"
            _                    (utils/http-get model-source #(put! model-loaded-ch %))
            model-json           (js/JSON.parse (<! model-loaded-ch))
            model                (utils/process-immutable-json-model :laptop model-json {"vertexPositions"     :vertices
                                                                                         "vertexNormals"       :normals
                                                                                         "vertexTextureCoords" :texture-coords
                                                                                         "indices"             :indices})
            color-image          (<! texture-loaded-ch)
            color-texture        {:data       color-image
                                  :filter     {:min :linear
                                               :mag :nearest}
                                  :flip-y    true
                                  :immutable? true
                                  :id         :laptop-texture}
            video-el             (<! video-ch)
            video-texture        {:data        video-el
                                  :filter      {:min :linear
                                                :mag :linear}
                                  :wrap        {:s :clamp-to-edge
                                                :t :clamp-to-edge}
                                  :flip-y     true
                                  :texture-id  1
                                  :continuous? true
                                  ;;:immutable? false
                                  ;;:id         :video-texture
                                  }
            _                    (swap! state (fn [state]
                                                (-> state
                                                    (assoc-in [:scene :color-texture] color-texture)
                                                    (assoc-in [:scene :video-el] video-el)
                                                    (assoc-in [:scene :video-texture] video-texture)
                                                    (assoc-in [:scene :model] model))))
            next-tick            (fn [] (animate (draw-fn gl driver programs) tick state))]
        (js/console.log "Model: " (clj->js model))
        ;; Wait 100ms, and then fix the WebGL inspector if it's there.
        (<! (async/timeout 100))
        (utils/fix-webgl-inspector-quirks true true 250)
        (if (:tick-first-frame? initial-query-map)
          (set! (.-tick js/window) next-tick)
          (do (<! (async/timeout 100))
              (next-tick)))))))
