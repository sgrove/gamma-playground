(ns ^:figwheel-load gampg.learn-gamma.apartment
    (:require [cljs.core.async :as async :refer [<! put! chan]]
              [clojure.string :as string]
              [gamma.api :as g]
              [gamma.program :as p]
              [gamma-driver.api :as gd]
              [gamma-driver.drivers.basic :as driver]
              [gampg.gltf :as gltf]
              [gampg.utils :as utils]
              [goog.Uri]
              [thi.ng.geom.core :as geom]
              [thi.ng.geom.core.matrix :as mat :refer [M44]])
    (:require-macros [cljs.core.async.macros :as async :refer [go]])
    (:import [goog.net XhrIo]))

(def half-pi
  (/ js/Math.PI 2))

(defn handle-mouse-move [cast! event]
  (cast! :mouse-moved [(.. event -pageX)
                       (.. event -pageY)] true))

(defn handle-mouse-down [cast! event]
  (cast! :mouse-depressed [(.. event -pageX)
                           (.. event -pageY)] false))

(defn handle-mouse-up [cast! event]
  (cast! :mouse-released [(.. event -pageX)
                          (.. event -pageY)] false))

(defn disable-mouse-wheel [event]
  (.stopPropagation event))

(def code->key
  "map from a character code (read from events with event.which)
  to a string representation of it.
  Only need to add 'special' things here."
  {8   "backspace"
   13  "enter"
   16  "shift"
   17  "ctrl"
   18  "alt"
   27  "esc"
   37  "left"
   38  "up"
   39  "right"
   40  "down"
   46  "del"
   91  "meta"
   32  "space"
   186 ";"
   191 "/"
   219 "["
   221 "]"
   187 "="
   189 "-"
   190 "."})

(defn track-key-state [cast! direction suppressed-key-combos event]
  (let [meta?      (when (.-metaKey event) "meta")
        shift?     (when (.-shiftKey event) "shift")
        ctrl?      (when (.-ctrlKey event) "ctrl")
        alt?       (when (.-altKey event) "alt")
        char       (or (get code->key (.-which event))
                       (js/String.fromCharCode (.-which event)))
        tokens     [shift? meta? ctrl? alt? char]
        key-string (string/join "+" (filter identity tokens))
        target-el-type (.. event -target -type)
        input-el?  (#{"text" "textarea" "input"} target-el-type)]
    (when (and (get suppressed-key-combos key-string)
               ;; Example problem: Backspace causes the browser to
               ;; navigate to the previous page normally. We want to
               ;; hijack backspace to prevent that default behavior,
               ;; and instead cause it delete selected layers. We
               ;; therefore add it to the suppressed key-combos (along
               ;; with meta-a, etc. that we make use of).  But now
               ;; backspace (and meta-a, etc.) doesn't work when
               ;; typing in textareas/text-inputs, because the default
               ;; behavior there is to delete the selected text (or
               ;; highlight it with meta-a, etc.).

               ;; As a hack, we .preventDefault on the event iff it's
               ;; listed in suppressed-key-combos *and* the currently
               ;; active element in the browser is not some kind of
               ;; text-input (in which case, none of our key shortcuts
               ;; should work anyway).
               (not input-el?))
      (.preventDefault event))
    (when-not (.-repeat event)
      (when-let [human-name (or (get code->key (.-which event))
                                (some-> (.-which event)
                                        js/String.fromCharCode
                                        .toLowerCase))]
        (let [key-name (keyword (str human-name "?"))]
          (cast! :key-state-changed [{:key-name-kw key-name
                                      :code        (.-which event)
                                      :depressed?  (= direction :down)}]))))))

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
      (mat/matrix44->matrix33)
      (object-array)
      ))

(defn get-data [now p mv vertices normals diffuse texture texture-coords]
  (assert (and p mv vertices normals) (str "Inputs cannot be null: " [(boolean p)
                                                                      (boolean mv)
                                                                      (boolean vertices)
                                                                      (boolean normals)]))
  (let [[x y z] [(js/Math.sin now)
                 (js/Math.cos now)
                 (js/Math.sin (* now 2))]]
    {u-p-matrix           (object-array p)
     u-mv-matrix          (object-array mv)
     u-n-matrix           (get-normal-matrix mv)
     u-ambient-color      #js [0.5 0.5 0.5]
     u-lighting-direction #js [-0.25 0.25 1]
     u-directional-color  #js [0 0 0]
     u-use-lighting       true
     u-light-angle        #js [x y z] ;;[1 1 1]
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
(defn app-state [width height gl node now]
  {:last-rendered 0
   :mouse         {:pos         [0 0]
                   :sensitivity 0.01}
   :walk-speed 0.003
   :scene         {:rotation 0
                   :mv       (mat/matrix44)
                   :p        (get-perspective-matrix width height)
                   :sky-box {}
                   :camera   {:pitch   0
                              :yaw     0 ;;59
                              :roll    0
                              :x       -6.5
                              :y       1.5
                              :z       15 ;;-9.5
                              :flip-y? true}
                   :-camera   {:pitch 0
                               :yaw   3.1
                               :x     0
                               :y     1.5
                               :z     -9.5
                               }}
   :webgl         {:canvas {:node node
                            :gl   gl}}})

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
                       (geom/invert)
                       (object-array))
        final-data (select-keys {u-p-matrix                  (object-array p)
                                 u-sky-box-sampler           sky-box
                                 a-sky-box-position          {:data [-1 -1, 3 -1, -1 3]
                                                              :id :sky-box-position
                                                              :immutable? true
                                                              :count 3}
                                 u-sky-box-inverse-mv-matrix mv}
                                (:inputs program))]
    (gd/draw-arrays driver (gd/bind driver program final-data)
                    {:draw-mode :triangles
                     :count 3})))

(defn draw-fn [driver state]
  (let [gl              (:gl driver)
        now             (/ (:now state) 1000)
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
                              (render-node [(:name child) child] mv))))
        (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))]
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
                                             ))))))

(def manual-step-frame-by-frame?
  (do
    true
    false
    ))

(defn animate-pure [draw-fn step-fn current-value]
  (js/requestAnimationFrame
   (fn [time]
     (let [next-value (step-fn time current-value)]
       (draw-fn next-value)
       (when-let [ext (.getExtension (.-glHandle js/window) "GLI_frame_terminator")]
         ;; Useful for WebGL inspector until we have Gamma-Inspector
         (.frameTerminator ext))
       (if manual-step-frame-by-frame?
         (set! (.-tick js/window)
               #(animate-pure draw-fn step-fn next-value))
         (animate-pure draw-fn step-fn next-value))))))

(defn tick
  "Takes the old world value and produces a new world value, suitable
  for rendering"
  [state]
  ;; We get the elapsed time since the last render to compensate for
  ;; lag, etc.
  (let [time-now     (.getTime (js/Date.))
        elapsed      (- time-now (:last-rendered state))
        ;; TODO: change these to abstract :forward/:backward lookups
        ;; if it's not too expensive
        ;; TODO: Make this configurable at runtime to tweak with the values
        walk-speed   (:walk-speed state)
        delta-walk   (+ (if (get-in state [:keyboard :w?]) walk-speed 0)
                        (if (get-in state [:keyboard :s?]) (- walk-speed) 0))
        delta-strafe (+ (if (get-in state [:keyboard :a?]) walk-speed 0)
                        (if (get-in state [:keyboard :d?]) (- walk-speed) 0))
        camera       (get-in state [:scene :camera])]
    (-> state
        (update-in [:scene :camera :x] (fn [x] (- x (+ (* (js/Math.sin (:yaw camera)) delta-walk elapsed)
                                                      (* (js/Math.sin (+ half-pi (:yaw camera))) delta-strafe elapsed)))))
        (update-in [:scene :camera :z] (fn [z] (- z (+ (* (js/Math.cos (:yaw camera)) delta-walk elapsed)
                                                      (* (js/Math.cos (+ half-pi (:yaw camera))) delta-strafe elapsed)))))
        (assoc-in [:last-rendered] time-now)
        (assoc :now time-now))))

(defn http-get [url cb]
  (XhrIo.send (str url)
              (fn [e]
                (let [xhr (.-target e)]
                  (cb (.getResponseText xhr))))))


(defmulti control-event
  (fn [message args state] message))

(defmethod control-event :default
  [message args state]
  ;;(print "Unhandled message in controls: " message)
  state)

(defmethod control-event :key-state-changed
  [message [{:keys [key-name-kw depressed?]}] state]
  (assoc-in state [:keyboard key-name-kw] depressed?))

(defmethod control-event :mouse-moved
  [message [new-screen-x new-screen-y] state]
  (let [[old-screen-x
         old-screen-y] (get-in state [:mouse :pos])
         sensitivity    (get-in state [:mouse :sensitivity])
         dx             (* sensitivity (- old-screen-x new-screen-x))
         dy             (* sensitivity (- old-screen-y new-screen-y))
         new-pos        [new-screen-x new-screen-y]
         next-state     (-> state
                            (update-in [:scene :camera :yaw] + dx)
                            (update-in [:scene :camera :pitch] (fn [pitch]
                                                               (-> (+ pitch dy)
                                                                   (min half-pi)
                                                                   (max (- half-pi)))))
                            (assoc-in [:mouse :pos] new-pos))]
    next-state))

(defn post-control-event! [msg data previous-state new-state]
  nil)

(defn render-loop [draw-fn state-atom stop-ch]
  (js/requestAnimationFrame
   (fn [time]
     (let [state @state-atom]
       (draw-fn (get-in state [:runtime :driver]) state)
       (when-let [ext (.getExtension (get-in state [:runtime :driver :gl]) "GLI_frame_terminator")]
         ;; Useful for WebGL inspector until we have Gamma-Inspector
         (.frameTerminator ext)))
     (if manual-step-frame-by-frame?
       (set! (.-tick js/window)
             #(render-loop draw-fn state-atom stop-ch))
       (render-loop draw-fn state-atom stop-ch)))))

(defn main* [app-state opts]
  (let [histories                (or (:histories opts)
                                     (atom {}))
        [controls-ch
         stop-ch    
         keyboard-ch]            [(get-in @app-state [:comms :controls])
         (get-in @app-state [:comms :stop])
         (get-in @app-state [:comms :keyboard])]
        cast!                    (fn [message data & [elide-from-history?]]
                                   (async/put! controls-ch [message data elide-from-history?]))
        key-up-handler           (fn [])
        suppressed-key-combos    #{"meta+A" "meta+D" "meta+Z" "shift+meta+Z" "backspace"
                                   "shift+meta+D" "up" "down" "left" "right" "meta+G"}
        handle-key-down          (partial track-key-state cast! :down suppressed-key-combos)
        handle-key-up            (partial track-key-state cast! :up   suppressed-key-combos)
        handle-mouse-move!       #(handle-mouse-move cast! %)
        handle-canvas-mouse-down #(handle-mouse-down cast! %)
        handle-canvas-mouse-up   #(handle-mouse-up   cast! %)
        handle-close!            #(cast! :application-shutdown [@histories])]
    (js/document.addEventListener "keydown"      handle-key-down    false)
    (js/document.addEventListener "keyup"        handle-key-up      false)
    (js/document.addEventListener "mousemove"    handle-mouse-move! false)
    (js/window.addEventListener   "beforeunload" handle-close!)
    (set! (.-state js/window) app-state)
    (set! (.-assocInState js/window)
          (fn [path value]
            (swap! app-state assoc-in (mapv keyword (js->clj path)) value)))
    (set! (.-sendMessage js/window)
          (fn [channel msg value]
            (async/put! (get-in @app-state [:comms (keyword channel)]) [(keyword msg) [value]])))
    (render-loop draw-fn app-state nil)
    ;;(js/document.addEventListener "mousewheel"   disable-mouse-wheel false)
    (async/go
      (loop []
        (async/alt!
          controls-ch ([[msg data transient?]]
                       (print "Controls Message: " (pr-str msg)  " -> " (pr-str data))
                       (let [previous-state @app-state]
                         (swap! app-state
                                (fn [state]
                                  (tick (control-event msg data state))))
                         (post-control-event! msg data previous-state @app-state)))
          ;; XXX: Should probably remove this for replay needs
          (async/timeout 15) ([]
                              (swap! app-state tick)))
        (recur)))))

(defn main [node]
  (let [stop             (async/chan)
        controls         (async/chan)
        keyboard         (async/chan)
        gl               (.getContext node "webgl" #js {:antialias true})
        width            (.-clientWidth node)
        height           (.-clientHeight node)
        driver           (make-driver gl)
        starting-program 0
        programs         {:diffuse-flat  (gd/program driver program-diffuse-flat)
                          :diffuse-light (gd/program driver program-diffuse-light)
                          :texture-flat  (gd/program driver program-texture-flat)
                          :texture-light (gd/program driver program-texture-light)
                          :sky-box       (gd/program driver program-sky-box)}
        now              (.getTime (js/Date.))
        app-state        (-> (app-state width height gl node now)
                             (update-in [:comms] merge {:controls controls
                                                        :stop     stop
                                                        :keyboard keyboard})
                             (assoc-in [:runtime :programs] programs)
                             (assoc-in [:runtime :driver] driver))
        watch-key        (gensym)]

    (reset-gl-canvas! node)
    (.enable gl (.-DEPTH_TEST gl))
    (.clearColor gl 0 0 0 1)
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))

    (go
      (let [texture-ch (chan)
            scene-ch   (chan)]
        (utils/load-cube-map gl "/images/skybox/citadella"
                             (fn [cube-texture]
                               (let [cube-texture (assoc cube-texture :immutable? true)]
                                 (put! texture-ch {:cube-texture cube-texture}))))
        (http-get "/models/apartment_2.gltf"
                  (fn [data]
                    (let [json (js/JSON.parse data)
                          edn  (js->clj json :keywordize-keys true)
                          gltf (gltf/process-gltf edn)]
                      (set! (.-processedGLTF js/window) (clj->js gltf))
                      (put! scene-ch gltf))))
        (let [skybox    (<! texture-ch)
              scene     (<! scene-ch)
              app-state (-> app-state
                            (assoc-in [:skybox :texture] (:cube-texture skybox))
                            (assoc-in [:gltf] scene)
                            atom)]
          (main* app-state {:comms {:stop     stop
                                    :controls controls
                                    :keyboard keyboard}}))))))
