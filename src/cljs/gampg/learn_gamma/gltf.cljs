(ns ^:figwheel-load gampg.learn-gamma.gltf
    (:require [gamma.api :as g]
              [gamma.program :as p]
              [gamma.tools :as gt]
              [gamma-driver.drivers.basic :as driver]
              [gamma-driver.protocols :as dp]
              [gampg.gltf :as gltf]
              [goog.Uri]
              [goog.webgl :as ggl]
              [thi.ng.geom.core :as geom]
              [thi.ng.geom.core.matrix :as mat :refer [M44]]
              [thi.ng.geom.webgl.arrays :as arrays])
    (:import [goog.net XhrIo]))



(def title
  "Loading a Collada model (by not loading it).")

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

;; precision highp float;
;; varying vec3 v_normal;
;; uniform vec4 u_diffuse;
;; void main(void) {
;; vec3 normal = normalize(v_normal);
;; vec4 color = vec4(0., 0., 0., 0.);
;; vec4 diffuse = vec4(0., 0., 0., 1.);
;; diffuse = u_diffuse;
;; diffuse.xyz *= max(dot(normal,vec3(0.,0.,1.)), 0.);
;; color.xyz += diffuse.xyz;
;; color = vec4(color.rgb * diffuse.a, diffuse.a);
;; gl_FragColor = color;
;; }


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
     u-diffuse            (or diffuse [1 1 0 1])
     u-light-angle        [x y z]
     a-position           vertices
     a-vertex-normal      normals
     u-sampler            texture
     a-tex-coord-0        texture-coords}))

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
   :scene         {:rotation 0
                   :mv       (mat/matrix44)
                   :p        (get-perspective-matrix width height)}})

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
          scale           (/ (get-in state [:scroll] 1000) 1000)
          gltf            (:gltf state)
          scene           (get-in gltf [:scenes (:scene gltf)])
          render-node     (fn render-node [[node-name node] mv]
                            (let [mv (geom/* mv (:matrix node))]
                              (doseq [mesh (:meshes node)]
                                (doseq [mesh (:primitives mesh)]
                                  (let [vertices         (get-in mesh [:attributes :p :data])
                                        p-stride         (get-in mesh [:attributes :p :byteStride])
                                        tex-coord-0      (get-in mesh [:attributes :tex-coord-0 :data])
                                        normals          (get-in mesh [:attributes :normal :data])
                                        n-stride         (get-in mesh [:attributes :normal :byteStride])
                                        indices          (get-in mesh [:indices])
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
                                        program          (if texture-program?
                                                           (get-in state [:runtime :programs 3])
                                                           (get-in state [:runtime :programs 1]))
                                        scene-data       (when normals
                                                           (select-keys (get-data now
                                                                                  p mv
                                                                                  vertices
                                                                                  normals
                                                                                  diffuse
                                                                                  material
                                                                                  tex-coord-0)
                                                                        (:inputs program)))]
                                    ;;(js/console.log (clj->js diffuse))
                                    ;;(js/console.log (clj->js scene-data))
                                    ;;(js/console.log (clj->js program))
                                    ;; Check for normals so we don't try to draw e.g. lines right now.
                                    (when normals
                                      (try
                                        (driver/draw-elements driver program
                                                              (assoc scene-data
                                                                     {:tag :element-index} indices)
                                                              {:draw-mode (:draw-mode mesh)
                                                               :count     draw-count})
                                        (catch js/Error e
                                          nil))))))
                              (doseq [child (vals (:children node))]
                                (render-node [(:name child) child] mv))))]
      (doseq [node (:nodes scene)]
        (render-node [(:name node) node] (-> mv
                                             (geom/translate [0 0 -7])
                                             (geom/scale scale scale scale)
                                             (geom/rotate-around-axis [0 1 0] (/ mouse-x 50))
                                             (geom/rotate-around-axis [0 0 1] (- (/ mouse-y 50)))))))))

(def manual-step-frame-by-frame?
  false)

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
  (let [time-now  (.getTime (js/Date.))
        elapsed   (- time-now (:last-rendered state))
        scroll    (.-mouseScroll js/window)]
    (set! (.-mouseScroll js/window) 0) 
    (-> state
        (assoc-in [:runtime :current-program] (.-mouseClick js/window))
        (assoc-in [:mouse] (.-mousePos js/window))
        (update-in [:scroll] + scroll)
        (assoc-in [:last-rendered] time-now))))

(defn http-get [url cb]
  (XhrIo.send (str url)
              (fn [e]
                (let [xhr (.-target e)]
                  (cb (.getResponseText xhr))))))

(defn install-event-listeners! [document node starting-program]
  (document.addEventListener "mousemove"
                             (fn [event]
                               (set! (.-mousePos js/window)
                                     [(.-pageX event)
                                      (.-pageY event)])))
  (document.addEventListener "click"
                             (fn [event]
                               (set! (.-mouseClick js/window)
                                     (inc (or (.-mouseClick js/window) starting-program)))))
  (.addEventListener node "mousewheel"
                     (fn [event]
                       (.preventDefault event)
                       (set! (.-mouseScroll js/window) (.-deltaY event)))))

;; This uses some gross global-state (js/window.mousePos,
;; js/window.mouseClick, js/window.mouseScroll). In a real app it
;; would be part of the event loop and be all local state.
(defn main [gl node]
  (let [width            (.-clientWidth node)
        height           (.-clientHeight node)
        driver           (make-driver gl)
        starting-program 0
        programs         (mapv #(dp/program driver %)
                               [program-diffuse-flat
                                program-diffuse-light
                                program-texture-flat
                                program-texture-light])
        state            (-> (app-state width height)
                             (assoc-in [:runtime :programs] programs)
                             (assoc-in [:runtime :current-program] 0))]
    (reset-gl-canvas! node)
    (.enable gl (.-DEPTH_TEST gl))
    (.clearColor gl 0 0 0 1)
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (set! (.-mouseClick js/window) starting-program)
    (set! (.-mouseScroll js/window) 1000)
    (install-event-listeners! js/document node starting-program)
    (http-get "/models/duck.gltf"
              (fn [data]
                (set! (.-glHandle js/window) gl)
                (let [json (js/JSON.parse data)
                      _ (set! (.-gltfStuff js/window) json)
                      edn  (js->clj json :keywordize-keys true)
                      gltf (gltf/process-gltf edn)]
                  (set! (.-processedGLTF js/window) (clj->js gltf))
                  (set! (.-processedGLTFEDN js/window) gltf)
                  (set! (.-debugRedraw js/window) (fn []
                                                    (animate (draw-fn gl driver) tick (-> state
                                                                                          (assoc-in [:gltf] gltf)))))
                  (animate (draw-fn gl driver) tick (-> state
                                                                (assoc-in [:gltf] gltf))))))))
