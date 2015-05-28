(ns ^:figwheel-load gampg.learn-gamma.apartment
    (:require [gamma.api :as g]
              [gamma.program :as p]
              [gamma-driver.api :as gd]
              [gamma-driver.drivers.basic :as driver]
              [gampg.gltf :as gltf]
              [gampg.utils :as utils]
              [goog.Uri]
              [thi.ng.geom.core :as geom]
              [thi.ng.geom.core.matrix :as mat :refer [M44]])
    (:import [goog.net XhrIo]))

(declare app-state)

(def title
  "Sean's apartment")

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

(def u-diffuse
  (g/uniform "uDiffuse" :vec4))

(def v-normal
  (g/varying "v_normal" :vec3 :highp))

(def a-tex-coord-0
  (g/attribute "a_texcoord0" :vec2))

(def v-tex-coord-0
  (g/varying "v_texcoord0" :vec2 :highp))

(def u-light-angle
  (g/uniform "uLightAngle" :vec3))

(def program-diffuse-flat
  (p/program
   {:vertex-shader   {(g/gl-position) (-> u-p-matrix
                                          (g/* u-mv-matrix)
                                          (g/* (g/vec4 a-position 1)))}
    :fragment-shader {(g/gl-frag-color) u-diffuse}
    :precision {:float :mediump}}))

(def program-texture-flat
  (p/program
   {:vertex-shader   {(g/gl-position) (-> u-p-matrix
                                          (g/* u-mv-matrix)
                                          (g/* (g/vec4 a-position 1)))
                      v-normal        (g/* u-n-matrix a-vertex-normal)
                      v-tex-coord-0   a-tex-coord-0}
    :fragment-shader (let [sampler   u-sampler
                           l-diffuse (g/texture2D sampler v-tex-coord-0)]
                       {(g/gl-frag-color) l-diffuse})
    :precision {:float :mediump}}))

(def program-diffuse-light
  (p/program
   {:vertex-shader   {(g/gl-position) (-> u-p-matrix
                                          (g/* u-mv-matrix)
                                          (g/* (g/vec4 a-position 1)))
                      v-normal        (g/* u-n-matrix a-vertex-normal)}
    :fragment-shader (let [l-normal    (g/normalize v-normal)
                           l-diffuse   (g/vec4 (g/* (g/swizzle u-diffuse :xyz)
                                                    (g/max (g/dot l-normal u-light-angle) 0))
                                               1)
                           l-color     u-ambient-color
                           l-color     (g/vec4 (g/+ (g/swizzle l-color :xyz)
                                                    (g/swizzle l-diffuse :xyz))
                                               1)]
                       {(g/gl-frag-color) l-color})
    :precision {:float :mediump}}))

(def program-texture-light
  (p/program
   {:vertex-shader   {(g/gl-position) (-> u-p-matrix
                                          (g/* u-mv-matrix)
                                          (g/* (g/vec4 a-position 1)))
                      v-normal        (g/* u-n-matrix a-vertex-normal)
                      v-tex-coord-0   a-tex-coord-0}
    :fragment-shader (let [sampler   u-sampler
                           texture   (g/texture2D sampler v-tex-coord-0)
                           l-diffuse (g/vec4 (g/* (g/swizzle texture :xyz)
                                                  (g/max (g/dot (g/normalize v-normal) u-light-angle) 0.5))
                                             1)
                           l-color   (g/vec4 (g/* (g/swizzle u-ambient-color :rgb)
                                                  (g/swizzle l-diffuse :rgb))
                                             (g/swizzle l-diffuse :a))]
                       {(g/gl-frag-color) l-color})
    :precision {:float :mediump}}))

(def u-sky-box-inverse-mv-matrix
  (g/uniform "uSkyBoxInverseMVMatrix" :mat4))

(def a-sky-box-position
  (g/attribute "aSkyBoxVertexPosition" :vec2))

(def a-sky-box-texture-coord
  (g/attribute "aSkyBoxTextureCoord" :vec3))

(def v-sky-box-texture-coord
  (g/varying "vSkyBoxTextureCoord" :vec3 :highp))

(def u-sky-box-sampler
  (g/uniform "uSkyBoxSampler" :samplerCube))

(def program-sky-box
  (p/program
   {:vertex-shader   {(g/gl-position)         (g/vec4 a-sky-box-position 0 1)
                      v-sky-box-texture-coord (-> u-sky-box-inverse-mv-matrix
                                                  (g/* u-p-matrix)
                                                  (g/* (g/gl-position))
                                                  (g/swizzle :xyz))
                      }
    :fragment-shader {(g/gl-frag-color) (g/textureCube u-sky-box-sampler v-sky-box-texture-coord)
                      }
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

(defn get-data [now p mv vertices normals diffuse texture texture-coords]
  (assert (and p mv vertices normals) (str "Inputs cannot be null: " [(boolean p)
                                                                      (boolean mv)
                                                                      (boolean vertices)
                                                                      (boolean normals)]))
  (let [[x y z] [(js/Math.sin now)
                 (js/Math.cos now)
                 (js/Math.sin (* now 2))]]
    {u-p-matrix           p
     u-mv-matrix          mv
     u-n-matrix           (get-normal-matrix mv)
     u-ambient-color      [0.5 0.5 0.5]
     u-lighting-direction [-0.25 0.25 1]
     u-directional-color  [0 0 0]
     u-use-lighting       true
     u-light-angle        [x y z] ;;[1 1 1]
     u-diffuse            (or diffuse [1 1 0 1])
     a-position           vertices
     a-vertex-normal      normals
     u-sampler            texture
     a-tex-coord-0        texture-coords}))

(defn my-produce-fn [driver constructor-fn old-spec new-spec]
  (if (and (:immutable? old-spec) (:immutable? new-spec)
           (= (:data old-spec) (:data new-spec)))
    old-spec
    (constructor-fn (gd/gl driver) (merge old-spec new-spec))))

(defn make-driver [gl]
  (driver/map->BasicDriver
    {:gl gl
     :resource-state (atom {})
     :mapping-fn (fn [x] (or (:id x) (:element x) x))
     :input-state (atom {})
     :input-fn driver/default-input-fn
     :produce-fn my-produce-fn}))

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

;; This is not serialization-friendly. Clean up before sharing this
;; too widely.
(defn app-state [width height]
  {:last-rendered 0
   :scene         {:rotation 0
                   :mv       (mat/matrix44)
                   :p        (get-perspective-matrix width height)
                   :sky-box {}
                   :camera   {:pitch   0
                              :yaw     0 ;;59
                              :x       -6.5
                              :y       1.5
                              :z       15 ;;-9.5
                              :flip-y? true}
                   :-camera   {:pitch 0
                               :yaw   3.1
                               :x     0
                               :y     1.5
                               :z     -9.5
                               }}})

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
        final-data (select-keys {u-p-matrix                  p
                                 u-sky-box-sampler           sky-box
                                 a-sky-box-position          {:data [-1 -1, 3 -1, -1 3]
                                                              :immutable? true
                                                              :count 3}
                                 u-sky-box-inverse-mv-matrix mv}
                                (:inputs program))]
    (gd/draw-arrays driver (gd/bind driver program final-data)
                    {:draw-mode :triangles
                     :count 3})))

(defn draw-fn [gl driver]
  (fn [state]
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (let [now             (/ (.getTime (js/Date.)) 1000)
          rot             (* js/Math.PI (js/Math.sin now))
          {:keys
           [p mv
            texture edn]} (:scene state)
          mouse-x         (get-in state [:mouse 0] 0)
          mouse-y         (get-in state [:mouse 1] 0)
          camera          (get-in state [:scene :camera])
          scale           (/ (get-in state [:scroll] 1000) 1000)
          gltf            (:gltf state)
          scene           (get-in gltf [:scenes (:scene gltf)])
          primitive-id    (fn [node-name mesh index attr]
                            (let [n (str node-name "-" (:name mesh) "-" index "-" attr)]
                              n))
          render-node     (fn render-node [[node-name node] mv]
                            (let [mv (geom/* mv (:matrix node))]
                              (doseq [mesh (:meshes node)]
                                (doseq [[index mesh] (map vector (range) (:primitives mesh))]
                                  (let [vertices         (assoc (get-in mesh [:attributes :p]) :id (primitive-id node-name mesh index :p))
                                        p-stride         (get-in mesh [:attributes :p :byteStride])
                                        tex-coord-0      (assoc (get-in mesh [:attributes :tex-coord-0]) :id (primitive-id node-name mesh index :tex-coord-0))
                                        normals          (assoc (get-in mesh [:attributes :normal]) :id (primitive-id node-name mesh index :normal))
                                        n-stride         (get-in mesh [:attributes :normal :byteStride])
                                        indices          (assoc (get-in mesh [:indices]) :id (primitive-id node-name mesh index :indices))
                                        material         (get-in mesh [:material :values :diffuse])
                                        ;; If diffuse is a vector,
                                        ;; it's a color, otherwise if
                                        ;; it's a map it's a texture
                                        texture-program? (map? material)
                                        diffuse          (if texture-program?
                                                           (:data material)
                                                           material)
                                        sampler          (:sampler material)
                                        draw-count       (:count indices)
                                        program-name     (if texture-program?
                                                           :texture-light
                                                           :diffuse-light)
                                        program          (get-in state [:runtime :programs program-name])
                                        scene-data       (when normals
                                                           (select-keys (get-data now
                                                                                  p mv
                                                                                  vertices
                                                                                  normals
                                                                                  diffuse
                                                                                  material
                                                                                  tex-coord-0)
                                                                        (:inputs program)))]
                                    ;;(js/console.log (pr-str program-name))
                                    ;; Check for normals so we don't try to draw e.g. lines right now.
                                    ;;(js/console.log "Program: " (clj->js program-name) (clj->js scene-data))
                                    (when (and vertices normals diffuse)
                                      ;;(js/console.log "draw-elements " (pr-str program-name))
                                      (gd/draw-elements driver (gd/bind driver program
                                                                        (assoc scene-data
                                                                               {:tag :element-index
                                                                                :id  (:name mesh)} indices))
                                                        {:draw-mode (:draw-mode mesh)
                                                         :count     draw-count})))))
                              (doseq [child (vals (:children node))]
                                (render-node [(:name child) child] mv))))]
      ;;(js/console.log "draw-sky-box")
      (draw-sky-box driver (get-in state [:runtime :programs :sky-box]) p (get-in state [:skybox :texture]) (- (:pitch camera)) (- (:yaw camera)))
      (.clear gl (.-DEPTH_BUFFER_BIT gl))
      (doseq [node (:nodes scene)]
        (let [scale (max (js/Math.abs (* rot 2)))]
          (render-node [(:name node) node] (-> mv
                                               (geom/rotate-around-axis [1 0 0] (- (:pitch camera)))
                                               (geom/rotate-around-axis [0 1 0] (- (:yaw camera)))
                                               (geom/translate [(- (:x camera))
                                                                (- (:y camera))
                                                                (- (:z camera))])
                                               ;;(geom/translate [-3 0 5.5])
                                               (geom/rotate-around-axis [0 0 1] (if (:flip-y? camera) js/Math.PI 0))
                                               ;;(geom/scale scale scale scale)
                                               ;;(geom/rotate-around-axis [0 1 0] rot)
                                               ;;(geom/rotate-around-axis [0 0 1] (- rot))
                                               )))))))

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
       (when-let [ext (.getExtension (.-glHandle js/window) "GLI_frame_terminator")]
         ;; Useful for WebGL inspector until we have Gamma-Inspector
         (.frameTerminator ext))
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
  (let [time-now   (.getTime (js/Date.))
        elapsed    (- time-now (:last-rendered state))
        scroll     (.-mouseScroll js/window)
        walk-speed (* (or (.-forward js/window) 0) 2)
        turn-speed (* (or (.-yaw js/window) 0) 2)
        pitch-speed (* (or (.-pitch js/window) 0) 2)
        camera     (get-in state [:scene :camera])]
    ;;(js/console.log (pr-str [walk-speed turn-speed]))
    (set! (.-mouseScroll js/window) 0)
    (set! (.-forward js/window) 0)
    (set! (.-yaw js/window) 0) 
    (-> state
        (assoc-in [:runtime :current-program] (.-mouseClick js/window))
        (assoc-in [:mouse] (.-mousePos js/window))
        (update-in [:scroll] + scroll)
        (update-in [:scene :camera :x] (fn [x] (- x (* (js/Math.sin (:yaw camera)) walk-speed elapsed))))
        (update-in [:scene :camera :z] (fn [z] (- z (* (js/Math.cos (:yaw camera)) walk-speed elapsed))))
        (update-in [:scene :camera :yaw] + (* turn-speed elapsed))
        (update-in [:scene :camera :pitch] + (* pitch-speed elapsed))
        (assoc-in [:last-rendered] time-now))))

(defn http-get [url cb]
  (XhrIo.send (str url)
              (fn [e]
                (let [xhr (.-target e)]
                  (cb (.getResponseText xhr))))))

;; This uses some gross global-state (js/window.mousePos,
;; js/window.mouseClick, js/window.mouseScroll). In a real app it
;; would be part of the event loop and be all local state.
(defn main [gl node]
  (let [width            (.-clientWidth node)
        height           (.-clientHeight node)
        state            (app-state width height)
        driver           (make-driver gl)
        starting-program 0
        programs         {:diffuse-flat  (gd/program driver program-diffuse-flat)
                          :diffuse-light (gd/program driver program-diffuse-light)
                          :texture-flat  (gd/program driver program-texture-flat)
                          :texture-light (gd/program driver program-texture-light)
                          :sky-box       (gd/program driver program-sky-box)}
        state            (-> state
                             (assoc-in [:runtime :programs] programs))]
    (reset-gl-canvas! node)
    (.enable gl (.-DEPTH_TEST gl))
    (.clearColor gl 0 0 0 1)
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (utils/load-cube-map gl "/images/skybox/citadella"
                         (fn [cube-texture]
                           (let [cube-texture (assoc cube-texture :immutable? true)]
                             (http-get "/models/apartment_2.gltf"
                                       (fn [data]
                                         (set! (.-glHandle js/window) gl)
                                         (let [json (js/JSON.parse data)
                                               _ (set! (.-gltfStuff js/window) json)
                                               edn  (js->clj json :keywordize-keys true)
                                               gltf (gltf/process-gltf edn)]
                                           (set! (.-processedGLTF js/window) (clj->js gltf))
                                           (set! (.-processedGLTFEDN js/window) gltf)
                                           (aset js/window "debugRedraw"
                                                 (fn []
                                                   (animate (draw-fn gl driver) tick (-> state
                                                                                         (assoc-in [:gltf] gltf)
                                                                                         (assoc-in [:skybox :texture] cube-texture)))))
                                           #_(animate (draw-fn gl driver) tick (-> state
                                                                                   (assoc-in [:gltf] gltf)
                                                                                   (assoc-in [:skybox :texture] cube-texture)))))))))
    
    (let [keycodes {37 :left
                    38 :up
                    39 :right
                    40 :down
                    65 :a
                    68 :d
                    69 :e
                    81 :q
                    87 :w
                    83 :s}]
      (js/document.addEventListener "keydown"
                                    (fn [event]
                                      (let [keycode (.-which event)
                                            key (get keycodes keycode)
                                            [direction speed] (condp = key
                                                                :w     [:forward 0.001]
                                                                :up    [:forward 0.001]
                                                                :a     [:yaw 0.001]
                                                                :left  [:yaw 0.001]
                                                                :d     [:yaw -0.001]
                                                                :right [:yaw -0.001]
                                                                :s     [:forward -0.001]
                                                                :down  [:forward -0.001]
                                                                :q     [:pitch 0.001]
                                                                :e     [:pitch -0.001]
                                                                nil)]
                                        (when direction
                                          (.preventDefault event)
                                          (condp = direction
                                            :forward (set! (.-forward js/window) speed)
                                            :yaw (set! (.-yaw js/window) speed)
                                            (set! (.-pitch js/window) speed))
                                          ;;(swap! state assoc-in [:movement direction] speed)
                                          ))))
      (js/document.addEventListener "keyup"
                                    (fn [event]
                                      (let [keycode (.-which event)
                                            key (get keycodes keycode)
                                            [direction speed] (condp = key
                                                                :w     [:forward 0.0]
                                                                :up    [:forward 0.0]
                                                                :a     [:yaw 0.0]
                                                                :left  [:yaw 0.0]
                                                                :d     [:yaw 0.0]
                                                                :right [:yaw 0.0]
                                                                :s     [:forward 0.0]
                                                                :down  [:forward 0.0]
                                                                :q     [:pitch 0.0]
                                                                :e     [:pitch 0.0]
                                                                nil)]
                                        (when direction
                                          (.preventDefault event)
                                          (condp = direction
                                            :forward (set! (.-forward js/window) speed)
                                            :yaw     (set! (.-yaw js/window) speed)
                                            (set! (.-pitch js/window) speed))
                                          ;;(swap! state assoc-in [:movement direction] speed)
                                          )))))))
