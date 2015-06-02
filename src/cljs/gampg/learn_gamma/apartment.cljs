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
              [goog.webgl :as ggl]
              [thi.ng.geom.core :as geom]
              [thi.ng.geom.core.matrix :as mat :refer [M44]]
              [thi.ng.geom.core.vector :as vec])
    (:require-macros [cljs.core.async.macros :as async :refer [go]])
    (:import [goog.net XhrIo]))

(defn append-buffers [buffer-1 buffer-2]
  (let [cl  (+ (.-byteLength buffer-1)
               (.-byteLength buffer-2))
        tmp (js/Uint8Array. cl)]
    (.set tmp (js/Uint8Array. (.-buffer buffer-1)) 0)
    (.set tmp (js/Uint8Array. (.-buffer buffer-2)) (.-byteLength buffer-1))
    (.-buffer tmp)))

;; TODO: Factor in matrix offsets for meshes
(defn combine-primitives [primitive-1 primitive-2]
  (let [offset-indices       (fn [offset original-indices-buffer]
                               (let [tmp (js/Uint16Array. (.-length original-indices-buffer))]
                                 (dotimes [i (.-length original-indices-buffer)]
                                   (let [original-value (aget original-indices-buffer i)]
                                     (aset tmp i (+ offset original-value))))
                                 tmp))
        combine-indices      (fn [original-first-buffer indices-1 indices-2]
                               (let [entries-per-element (get {:float 1
                                                               :byte  1
                                                               :vec3  3
                                                               :vec4  4
                                                               :mat3  9
                                                               :mat4  16} (:clj-type original-first-buffer))
                                     tmp (js/Uint16Array. (+ (.-length indices-1)
                                                             (.-length indices-2)))
                                     offset (/ (.-length (:data original-first-buffer))
                                               entries-per-element)
                                     transformed-indices-2 (offset-indices offset indices-2)]
                                 (.set tmp indices-1)
                                 (.set tmp transformed-indices-2 (.-length indices-1))
                                 tmp))
        mesh-1               {:p           (get-in primitive-1 [:attributes :p])
                              :normal      (get-in primitive-1 [:attributes :normal])
                              :tex-coord-0 (get-in primitive-1 [:attributes :tex-coord-0])
                              :indices     (get-in primitive-1 [:indices])
                              :material    (get-in primitive-1 [:material])}
        mesh-2               {:p           (get-in primitive-2 [:attributes :p])
                              :normal      (get-in primitive-2 [:attributes :normal])
                              :tex-coord-0 (get-in primitive-2 [:attributes :tex-coord-0])
                              :indices     (get-in primitive-2 [:indices])
                              :material    (get-in primitive-2 [:material])}
        combined-p           (js/Float32Array. (append-buffers (get-in mesh-1 [:p :data]) (get-in mesh-2 [:p :data])))
        combined-n           (js/Float32Array. (append-buffers (get-in mesh-1 [:normal :data]) (get-in mesh-2 [:normal :data])))
        combined-tex-coord-0 (when (and (get-in mesh-1 [:tex-coord-0 :data]) (get-in mesh-2 [:tex-coord-0 :data]))
                               (js/Float32Array. (append-buffers (get-in mesh-1 [:tex-coord-0 :data]) (get-in mesh-2 [:tex-coord-0 :data]))))
        combined             {:attributes (merge {:p           (merge (:p mesh-1)
                                                                      {:data combined-p})
                                                  :normal      (merge (:normal mesh-1)
                                                                      {:data combined-n})}
                                                 (when combined-tex-coord-0
                                                   {:tex-coord-0 (merge (:tex-coord-0 mesh-1)
                                                                        {:data combined-tex-coord-0})}))
                              :indices  (merge (:indices mesh-1)
                                               {:data (combine-indices (get-in mesh-1 [:p]) (get-in mesh-1 [:indices :data]) (get-in mesh-2 [:indices :data]))
                                                :count (+ (get-in mesh-1 [:indices :count])
                                                          (get-in mesh-2 [:indices :count]))})
                              :material (merge (:material mesh-1)
                                               (get-in mesh-1 [:material]))}]
    combined))

(defn walk-tree-combining-meshes [grouping-fn max-element-count level result-buffers [node-name root-node]]
  ;; 1. If we have children, walk children and combine their meshes.
  ;; 2. Combine node's meshes, if any
  ;; 3. Combine node's meshes with combined children's meshes
  (let [children-combined     (remove nil? (flatten (map (comp vals (partial walk-tree-combining-meshes grouping-fn max-element-count (inc level) result-buffers)) (:children root-node))))
        unfiltered-primitives (vec (mapcat #(get-in % [:primitives]) (:meshes root-node)))
        primitives            (filter (fn [primitive]
                                        (and primitive
                                             (= 4 (get-in primitive [:draw-mode])))) unfiltered-primitives)
        all-primitives        (concat primitives (remove nil? children-combined))
        _ (js/console.log  level ". all-primitives: " (clj->js all-primitives))
        node-combined         (reduce (fn [run next-primitive]
                                        (let [next-primitive-key (grouping-fn next-primitive)
                                              current-primitive  (last (get run next-primitive-key))
                                              new-length         (+ (.-length (get-in next-primitive [:attributes :p :data]))
                                                                    (if current-primitive (.-length (get-in current-primitive [:attributes :p :data]))
                                                                        0))]
                                          (js/console.log (string/join (repeat level "\t")) "run length: " (count run) ", new length: " new-length)
                                          (js/console.log (string/join (repeat level "\t")) "key/run/current/next: " (pr-str next-primitive-key) (clj->js run) (clj->js current-primitive) (clj->js next-primitive))
                                          (if (or (nil? current-primitive)
                                                  (< max-element-count new-length))
                                            (update-in run [next-primitive-key] #(vec (conj % next-primitive)))
                                            (update-in run [next-primitive-key] #(vec (conj (drop-last 1 %)
                                                                                            (combine-primitives current-primitive next-primitive))))))) (group-by grouping-fn (take 1 all-primitives)) (drop 1 all-primitives))
        combined              node-combined]
    combined))

(defn transform-mesh [transform-matrix f32a-vertices]
  (let [new-data (js/Float32Array. (.-length f32a-vertices))]
    (dotimes [n (/ (.-length f32a-vertices) 3)]
      (let [offset (* n 3)
            x      (aget f32a-vertices (+ offset 0))
            y      (aget f32a-vertices (+ offset 1))
            z      (aget f32a-vertices (+ offset 2))
            vertex (geom/transform-vector transform-matrix
                                          (vec/vec3 x y z))]
        (doto new-data
          (aset (+ offset 0) (:x vertex))
          (aset (+ offset 1) (:y vertex))
          (aset (+ offset 2) (:z vertex)))))
    new-data))

(defn walk-tree-transforming-meshes [root-node]
  (->> (mapv (partial (fn helper [current-matrix [node-name node]]
                  (let [{:keys [matrix meshes]} node
                        transform-matrix        (geom/* current-matrix matrix)]
                    {node-name (-> node
                                   (update-in [:meshes]
                                              (fn [meshes]
                                                (mapv (fn [mesh-description]
                                                        (-> mesh-description
                                                            (update-in [:primitives]
                                                                       (partial mapv (fn [primitive]
                                                                                 (let [p             (get-in primitive [:attributes :p :data])
                                                                                       transformed-p (transform-mesh transform-matrix p)]
                                                                                   (-> primitive
                                                                                       (assoc-in [:attributes :p :original-data] p)
                                                                                       (assoc-in [:attributes :p :data] transformed-p)))))))) meshes)))
                                   (update-in [:children] (fn [children]
                                                            (reduce merge {} (mapv (partial helper transform-matrix) children)))))}))
                (mat/matrix44)) (:children root-node))
       (reduce merge {})
       (assoc root-node :children)))

(defn fix-webgl-inspector-quirks [capture-first-frame? show? & [height]]
  (let [inspector-capture-nodes (js/document.querySelectorAll "[title='Capture frame (F12)']")
        inspector-ui-nodes      (js/document.querySelectorAll "[title='Show full inspector (F11)']")
        inspector-windows       (js/document.querySelectorAll ".splitter-horizontal")
        target-capture-node     (aget inspector-capture-nodes 1)
        synthetic-click         (doto (js/document.createEvent "MouseEvent")
                                  (.initMouseEvent "click", true, true, js/window, 0, 0, 0, 0, 0, false, false, false, false, 0))]
    (when (= 3 (.-length inspector-capture-nodes))
      (.remove (aget inspector-capture-nodes 0))
      (.remove (aget inspector-ui-nodes 0))
      (.remove (aget inspector-capture-nodes 2))
      (.remove (aget inspector-ui-nodes 2))
      (.remove (.-parentNode (aget inspector-windows 0)))
      (.remove (.-parentNode (aget inspector-windows 2)))
      (when capture-first-frame?
        (.dispatchEvent target-capture-node synthetic-click))
      (aset js/window "captureNextFrame"
            (fn [] (.dispatchEvent target-capture-node synthetic-click)))
      (let [inspector-window (.-parentNode (aget inspector-windows 1))
            existing-height  (.-clientHeight inspector-window)]
        (.setAttribute inspector-window "style" (str "height: " height "px;" (when-not show? "display: none;")))))))

(def default-model
  "apartment_3.gltf")

;; Remember to (:import goog.Uri) in your ns declaration
;; Example input/output:
;; http://localhost:10555/?model=duck&manual-tick=false&collapse-all=true&capture-first-frame=true&skybox-name=sky1.png&age=30&money=300.30
;; => {:model                "duck",
;;     :tick-first-frame?    false,
;;     :manual-tick?         false,
;;     :collapse-all?        true,
;;     :capture-first-frame? true,
;;     :skybox-name          ["sky1" "png"],
;;     :age                  30,
;;     :money                300.3}
(defn uri-param [parsed-uri param-name & [not-found]]
  (let [v (.getParameterValue parsed-uri param-name)]
    (cond
      (= v "")                          [(keyword param-name) not-found]
      (undefined? v)                    [(keyword param-name) not-found]
      (= v "true")                      [(keyword (str param-name "?")) true]
      (= v "false")                     [(keyword (str param-name "?")) false]
      (= (.toString (js/parseInt v)) v) [(keyword param-name) (js/parseInt v)]
      (re-matches #"^\d+\.\d*" v)       [(keyword param-name) (js/parseFloat v)]
      :else                             [(keyword param-name) v])))

(def initial-query-map
  (let [parsed-uri (goog.Uri. (.. js/window -location -href))
        ks         (.. parsed-uri getQueryData getKeys)
        defaults   {:model                default-model
                    :tick-first-frame?    false
                    :manual-tick?         false
                    :collapse-all?        false
                    :capture-first-frame? false}
        initial    (reduce merge {} (map (partial uri-param parsed-uri) (clj->js ks)))
        ;; Use this if you need to do any fn-based changes, e.g. split on a uri param
        special {:skybox-name (when-let [skybox-name (second (uri-param parsed-uri "skybox-name" "sky1.png"))]
                                (vec (string/split skybox-name ".")))}]
    (merge defaults initial special)))


(js/console.log "initial-query-map: " (clj->js initial-query-map))

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

(def u-point-lighting-location
  (g/uniform "uPointLightingLocation" :vec3))

(def u-point-lighting-color
  (g/uniform "uPointLightingColor" :vec3))

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

(def program-diffuse-per-fragment
  (p/program
   {:vertex-shader   {v-position           (g/* u-mv-matrix (g/vec4 a-position 1))
                      (g/gl-position)      (g/* u-p-matrix v-position)
                      v-transformed-normal (g/* u-n-matrix a-vertex-normal)}
    :fragment-shader (let [light-direction           (g/normalize (g/- u-point-lighting-location (g/swizzle v-position :xyz)))
                           direction-light-weighting (g/max (g/dot (g/normalize v-transformed-normal) light-direction) 0)
                           light-weighting           (g/* (g/+ u-ambient-color u-point-lighting-color)
                                                          direction-light-weighting)
                           l-diffuse                 u-diffuse
                           a                         (g/swizzle l-diffuse :a)
                           rgb                       (g/* (g/swizzle l-diffuse :rgb) light-weighting)]
                       {(g/gl-frag-color) (g/vec4
                                           ;;1 0 0 1
                                           rgb (g/swizzle l-diffuse :a)
                                           ;;(g/swizzle l-diffuse :rg) 1 1
                                           )})
    :precision       {:float :mediump}}))

(def program-texture-per-fragment
  (p/program
   {:vertex-shader   {v-position           (g/* u-mv-matrix (g/vec4 a-position 1))
                      (g/gl-position)      (g/* u-p-matrix v-position)
                      v-texture-coord      a-tex-coord-0
                      v-transformed-normal (g/* u-n-matrix a-vertex-normal)}
    :fragment-shader (let [light-direction           (g/normalize (g/- u-point-lighting-location (g/swizzle v-position :xyz)))
                           direction-light-weighting (g/max (g/dot (g/normalize v-transformed-normal) light-direction) 0)
                           light-weighting           (g/* (g/+ u-ambient-color u-point-lighting-color)
                                                          direction-light-weighting)
                           texture-color             (g/texture2D u-sampler (g/vec2 (g/swizzle v-texture-coord :st)))
                           a                         (g/swizzle texture-color :a)
                           rgb                       (g/* (g/swizzle texture-color :rgb) light-weighting)]
                       {(g/gl-frag-color) (g/vec4 rgb (g/swizzle texture-color :a))})
    :precision {:float :mediump}}))

(def program-texture-specular-per-fragment
  (p/program
   {:vertex-shader   {v-position           (g/* u-mv-matrix (g/vec4 a-position 1))
                      (g/gl-position)      (g/* u-p-matrix v-position)
                      v-texture-coord      a-tex-coord-0
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
                                                         (g/pow u-material-shininess))
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

(def u-sky-box-p-matrix
  (g/uniform "uSkyBoxPositionMatrix" :mat4))

(def u-sky-box-mv-matrix
  (g/uniform "uSkyBoxMVMatrix" :mat4))

(def u-sky-box-inverse-mv-matrix
  (g/uniform "uSkyBoxInverseMVMatrix" :mat4))

(def a-sky-box-position
  (g/attribute "position" :vec3))

(def a-sky-box-texture-coord
  (g/attribute "aSkyBoxTextureCoord" :vec3))

(def v-sky-box-texture-coord
  (g/varying "vSkyBoxTextureCoord" :vec3 :highp))

(def u-sky-box-sampler
  (g/uniform "skybox" :samplerCube))

(def u-m4-matrix
  (g/uniform "um4_matrix" :mat4))

(def v-sky-box-position
  (g/varying "v_position" :vec4 :mediump))

#_(def -program-sky-box
  (p/program
   {:vertex-shader   {(g/gl-position)    a-sky-box-position
                      v-sky-box-position a-sky-box-position}
    :fragment-shader {(g/gl-frag-color) (let [t (g/* u-view-direction-projection-inverse v-sky-box-position)]
                                          (->> (g/div (g/swizzle t :xyz)
                                                      (g/swizzle t :w))
                                               g/normalize 
                                               (g/textureCube u-sky-box-sampler ))
                                          ;;(g/vec4 1 (g/swizzle v-sky-box-position :y) 0 1)
                                          )}
    :precision       {:float :mediump}}))
;; attribute vec3 a_position
;; uniform mat4 um4_matrix
;; varying vec3 v_tex_coord
;; void main() {
;;  gl_Position = um4_matrix * vec4(a_position, 1.0)
;;  v_tex_coord = a_position
;; }

;; precision mediump float
;; varying vec3 v_tex_coord
;; uniform samplerCube s_c
;; void main(){
;;   gl_FragColor = textureCube(s_c, v_tex_coord)
;; }
(def program-sky-box
  (p/program
   {:vertex-shader   {(g/gl-position)         (g/* u-sky-box-p-matrix (g/* u-sky-box-mv-matrix (g/vec4 a-sky-box-position 1)))
                      v-sky-box-texture-coord a-sky-box-texture-coord}
    :fragment-shader {(g/gl-frag-color) (g/textureCube u-sky-box-sampler v-sky-box-texture-coord)}
    :precision       {:float :mediump}}))

(defn get-perspective-matrix
  "Be sure to 
   1. pass the WIDTH and HEIGHT of the canvas *node*, not
      the GL context
   2. (set! (.-width/height canvas-node)
      width/height), respectively, or you may see no results, or strange
      results"
  [fov width height]
  (mat/perspective fov (/ width height) 0.01 100))

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
                 (* 10 (js/Math.cos now))
                 (js/Math.sin (* now 2))]]
    ;; Sun {:pitch -0.3307963267948957, :yaw -6.459999999999956, :x -4.948962346401, :y 10.64499999999996, :z 32.03640082585146}
    ;; Kitchen light: {:pitch -1.5092036732051048, :yaw -3.149999999999983, :x 1.3032935760308053, :y 2.379999999999997, :z -5.1902121897564815}
    ;; Hallway light: {:pitch -1.5092036732051042, :yaw -3.129999999999983, :x 3.594661978694604, :y 2.418550000000021, :z -6.344956105011467}
    ;; Bathroom light: {:pitch -1.4892036732051053, :yaw -6.269999999999996, :x 1.1613571469259034, :y 2.2384000000000035, :z -6.520717947851242}

    {u-p-matrix           (object-array p)
     u-mv-matrix          (object-array mv)
     u-n-matrix           (get-normal-matrix mv)
     u-ambient-color      #js [0.5 0.5 0.5]
     u-lighting-direction #js [-0.3307963267948957 -6.459999999999956 0]
     u-directional-color  #js [0 1 0]
     u-point-lighting-location  [1.3032935760308053 2.379999999999997 -5.1902121897564815] ;;#js[1.45781326737297734, -8.890483745091034, -0.814036282373294]
     #_[
      x
      y
      z
      ;;-0.9102980807223761, 2, 5.252247216779038
      ]
     u-point-lighting-color #js[0.8 0.8 0.8]
     u-point-lighting-diffuse-color  #js[0.8 0.8 0.8]
     u-point-lighting-specular-color #js[0.8 0.8 0.8]
     u-material-shininess            1
     u-use-lighting       true
     u-light-angle        #js [;;-1 0 -1
                               -1.5092036732051048 -3.149999999999983, 1
                               ;;x y z
                               ]
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
(defn app-state [width height gl node hmds now]
  (let [cw                (.-drawingBufferWidth gl)
        ch                (.-drawingBufferHeight gl)
        eye-division-line (/ cw 2)
        left-fov          (get-in hmds [:left-eye :rec-fov :left])
        right-fov         (get-in hmds [:right-eye :rec-fov :right])]
    {:last-rendered 0
     :max-indices   65000000000
     :mouse         {:pos         [0 0]
                     :sensitivity 0.01}
     :walk-speed    0.003
     :scene         {:rotation          0
                     :mv                (mat/matrix44)
                     :p                 (get-perspective-matrix 45 width height)
                     :left-p            (get-perspective-matrix left-fov (/ cw 2) ch)
                     :right-p           (get-perspective-matrix right-fov (/ cw 2) ch)
                     :left-scissor      [0 0 eye-division-line ch]
                     :right-scissor     [eye-division-line 0 eye-division-line ch]
                     :eye-division-line eye-division-line
                     :sky-box           {}
                     :hmd-pos #js[0 0 0]
                     :apt-camera        {:pitch -0.039203673205104164, :yaw -5.3000000000000105, :x 3.7236995248610296, :y 1, :z -1.7775460356400952}
                     :camera            {:pitch -0.039203673205104164, :yaw -5.3000000000000105, :x 3.7236995248610296, :y 1, :z -1.7775460356400952}}
     :webgl         {:canvas {:node node
                              :gl   gl}}
     }))

(defn rotate-x-y [ax ay]
  (let [cos-x (js/Math.cos ax)
        sin-x (js/Math.sin ax)
        cos-y (js/Math.cos ay)
        sin-y (js/Math.sin ay)]
    (mat/matrix44 [cos-y                0     (- sin-y)           0
                   (* (- sin-x) sin-y)  cos-x (* (- sin-x cos-y)) 0
                   (* cos-x sin-y)      sin-x (* cos-x cos-y)     0
                   0                    0     0                   1])))

(def sky-box-vertices
  {:data [-1  1  1 
              1  1  1 
              1 -1  1 
              -1 -1  1 
              -1  1 -1 
              1  1 -1 
              1 -1 -1 
              -1 -1 -1]
   :id :sky-box-vertices
   :immutable? true})

(def sky-box-indices
  {:data [3,2,0,0,2,1,2,6,1,1,6,5,0,1,4,4,1,5,5,6,4,6,7,4,4,7,0,7,3,0,6,2,7,2,3,7]
   :id :sky-box-indices
   :immutable? true})

(defn rotation-x [rx]
  (let [c (js/Math.cos rx)
        s (js/Math.sin rx)]
    [1 0 0 0
     0 c s 0
     0 (- s) c 0
     0 0 0 1]))

(defn rotation-y [ry]
  (let [c (js/Math.cos ry)
        s (js/Math.sin ry)]
    [(- c) 0 (- s) 0
     0 1 0 0
     s 0 (- c) 0
     0 0 0 1]))

(def mat44
  (mat/matrix44))

(defn draw-sky-box [driver program state p sky-box pitch yaw]
  (let [gl            (gd/gl driver)
        base-data     (select-keys {u-sky-box-sampler       sky-box
                                    a-sky-box-position      sky-box-vertices
                                    a-sky-box-texture-coord sky-box-vertices
                                    u-sky-box-mv-matrix     {:data (-> mat44
                                                                       (geom/rotate-x pitch)
                                                                       (geom/rotate-y yaw))}}
                                   (:inputs program))
        [lx ly lw lh] (get-in state [:scene :left-scissor])
        [rx ry rw rh] (get-in state [:scene :right-scissor])
        left-data     (assoc base-data
                             u-sky-box-p-matrix {:data (get-in state [:scene :left-p])}
                             {:tag :element-index} sky-box-indices)
        right-data    (assoc base-data
                             u-sky-box-p-matrix {:data (get-in state [:scene :right-p])}
                             {:tag :element-index} sky-box-indices)]
    ;; Left-eye
    (.viewport gl lx ly lw lh)
    (.scissor gl  lx ly lw lh)
    (gd/draw-elements driver (gd/bind driver program left-data)
                      {:draw-mode :triangles
                       :count     36})
    ;; Right-eye
    (.viewport gl rx ry rw rh)
    (.scissor gl rx ry rw rh)
    (gd/draw-elements driver (gd/bind driver program right-data)
                      {:draw-mode :triangles
                       :count     36})))

(def drawables
  #{:ID60 :ID104-split-0 :ID72 :ID112 :ID25-split-0 :ID38 :ID19 :ID118 :ID52-split-0 :ID104-split-1 :ID13 :ID96-split-0 :ID84 :ID25-split-1 :ID52-split-1 :ID46 :ID66 :ID96-split-1 :ID90 :ID78 :ID5})

(defn primitive->program-name [primitive]
  (let [material         (get-in primitive [:material :values :diffuse])
        ;; If diffuse is a vector,
        ;; it's a color, otherwise if
        ;; it's a map it's a texture
        texture-program? (map? material)]
    (if texture-program?
      (do
        :texture-specular-per-fragment
        :texture-per-fragment-light
        ;;:texture-flat
        )
      (do
        :diffuse-per-fragment-light
        :diffuse-light
        ;;:diffuse-flat
        ))))

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
                          (let [mv mv ;;(geom/* mv (:matrix node))
                                ]
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
                                      draw-count       (min (:max-indices state) (:count indices))
                                      program-name     (primitive->program-name mesh)
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
                                  (when (and vertices normals diffuse
                                             #_(or (and (not (get-in state [:debug :collapse-all?]))
                                                        (get drawables (keyword (:name mesh))))
                                                   (get-in state [:debug :collapse-all?])))
                                    ;;(js/console.log "draw-elements " (pr-str program-name))
                                    #_(js/console.log "\tscene-data:" (clj->js (assoc scene-data
                                                                                      {:tag :element-index} indices)))
                                    (gd/draw-elements driver (gd/bind driver program
                                                                      (assoc scene-data
                                                                             {:tag :element-index} indices))
                                                      {:draw-mode (:draw-mode mesh)
                                                       :count     draw-count})))))
                            (doseq [child (vals (:children node))]
                              (render-node [(:name child) child] mv))))
        ;;
        ]
    ;;(js/console.log "draw-sky-box")
    
    (draw-sky-box driver (get-in state [:runtime :programs :sky-box]) state p (get-in state [:skybox :texture])
                  (- (:pitch camera))
                  (- (:yaw camera)))
    (.clear gl (.-DEPTH_BUFFER_BIT gl))
    (doseq [[current-eye eye] [[:left (get-in state [:hmds :left-eye])]
                               [:right (get-in state [:hmds :right-eye])]]]
      (let [[sx sy sw sh] (get-in state [:scene (if (= current-eye :left) :left-scissor :right-scissor)])]
        ;;(js/console.log (pr-str current-eye) (clj->js [sx sy sw sh]))
        (.viewport gl sx sy sw sh)
        (.scissor  gl sx sy sw sh)
        (.clear gl (.-DEPTH_BUFFER_BIT gl))
        ;;(.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
        ;;(js/console.log "eye: " (clj->js eye))
        (doseq [node (:nodes scene)]
          (let [scale (max (js/Math.abs (* rot 2)))]
            ;;(js/console.log "Rendering node: " (clj->js node))
            (render-node [(:name node) node] (-> mv
                                                 (geom/rotate-around-axis [1 0 0] (- (:pitch camera)))
                                                 (geom/rotate-around-axis [0 1 0] (- (:yaw camera)))
                                                 (geom/translate [(- (:x camera) (get-in eye [:translation :x]))
                                                                  (- (:y camera))
                                                                  (- (:z camera) (get-in eye [:translation :z]))])
                                                 ;;(geom/translate [-3 0 5.5])
                                                 (geom/rotate-around-axis [1 0 0] (* js/Math.PI 1.5))
                                                 ;;(geom/rotate-around-axis [0 0 1] (if (:flip-y? camera) js/Math.PI 0))
                                                 ;;(geom/scale scale scale scale)
                                                 ;;(geom/rotate-around-axis [0 1 0] rot)
                                                 ;;(geom/rotate-around-axis [0 0 1] (- rot))
                                                 ))))))
    ))

(defn animate-pure [draw-fn step-fn current-value]
  (js/requestAnimationFrame
   (fn [time]
     (let [next-value (step-fn time current-value)]
       (draw-fn next-value)
       (when-let [ext (.getExtension (.-glHandle js/window) "GLI_frame_terminator")]
         ;; Useful for WebGL inspector until we have Gamma-Inspector
         ;;(.frameTerminator ext)
         )
       (if (:manual-tick? initial-query-map)
         (set! (.-tick js/window)
               #(animate-pure draw-fn step-fn next-value))
         (animate-pure draw-fn step-fn next-value))))))

(defn tick
  "Takes the old world value and produces a new world value, suitable
  for rendering"
  [state]
  ;; We get the elapsed time since the last render to compensate for
  ;; lag, etc.
  (let [time-now      (.getTime (js/Date.))
        elapsed       (- time-now (:last-rendered state))
        pos           (when-let [sensor (get-in state [:hmds :pos])]
                        (.getState sensor))
        ;; TODO: change these to abstract :forward/:backward lookups
        ;; if it's not too expensive
        ;; TODO: Make this configurable at runtime to tweak with the values
        last-hmd-pos  (get-in state [:scene :hmd-pos])
        hmd-move-factor 10
        hmd-x         (aget pos "position" "x")
        hmd-y         (aget pos "position" "y")
        hmd-z         (aget pos "position" "z")
        hmd-dx        (* hmd-move-factor (- hmd-x (aget last-hmd-pos 0)))
        hmd-dy        (* hmd-move-factor (- hmd-y (aget last-hmd-pos 1)))
        hmd-dz        (* hmd-move-factor (- hmd-z (aget last-hmd-pos 2)))
        slow-factor   (cond
                        (get-in state [:keyboard :shift?]) 100
                        (get-in state [:keyboard :ctrl?])  10
                        (get-in state [:keyboard :alt?])   0.1
                        :else                              1)
        walk-speed    (:walk-speed state)
        delta-walk    (+ (/ (+ (if (get-in state [:keyboard :w?]) walk-speed 0)
                               (if (get-in state [:keyboard :s?]) (- walk-speed) 0))
                            slow-factor)
                         hmd-dz)
        delta-strafe  (+ (/ (+ (if (get-in state [:keyboard :a?]) walk-speed 0)
                               (if (get-in state [:keyboard :d?]) (- walk-speed) 0))
                            slow-factor)
                         hmd-dx)
        up-down       (+ (/ (* 5 (+ (if (get-in state [:keyboard :q?]) walk-speed 0)
                                    (if (get-in state [:keyboard :e?]) (- walk-speed) 0)))
                            slow-factor)
                         hmd-dy)
        camera        (-> (get-in state [:scene :camera])
                          (assoc :yaw (aget pos "orientation" "y"))
                          (assoc :pitch (aget pos "orientation" "x")))]
    (if (zero? (:last-rendered state))
      (-> state
          (assoc :last-rendered time-now)
          (assoc :now time-now))
      (-> state
          (assoc-in [:scene :camera] camera)
          (update-in [:scene :camera :x] (fn [x] (- x (+ (* (js/Math.sin (:yaw camera)) delta-walk elapsed) (* (js/Math.sin (+ half-pi (:yaw camera))) delta-strafe elapsed)))))
          (update-in [:scene :camera :z] (fn [z] (- z (+ (* (js/Math.cos (:yaw camera)) delta-walk elapsed) (* (js/Math.cos (+ half-pi (:yaw camera))) delta-strafe elapsed)))))
          (update-in [:scene :camera :y] (fn [y] (- y up-down)))
          (assoc-in [:scene :hmd-pos] #js[hmd-x hmd-y hmd-z])
          (assoc-in [:last-rendered] time-now)
          (assoc :now time-now)))))

(defmulti control-event
  (fn [message args state] message))

(defmethod control-event :default
  [message args state]
  ;;(print "Unhandled message in controls: " message)
  state)

(defmethod control-event :key-state-changed
  [message [{:keys [key-name-kw depressed?]}] state]
  (when-let [capture (and (= key-name-kw :c?) (.-captureNextFrame js/window))]
    (.call capture))
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
     (if (:manual-tick? initial-query-map)
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
    (def as app-state)
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
                       ;;(print "Controls Message: " (pr-str msg)  " -> " (pr-str data))
                       (let [previous-state @app-state]
                         (swap! app-state
                                (fn [state]
                                  (tick (control-event msg data state))))
                         (post-control-event! msg data previous-state @app-state)))
          ;; XXX: Should probably remove this for replay needs
          (async/timeout 15) ([]
                              (swap! app-state tick)))
        (recur)))))

(defn preprocess-red-egg-cube [scene]
  (let [path       [:scenes :defaultScene :nodes 0 :children :node_0]
        mesh-1     (get-in scene [:meshes :ID2])
        mesh-2     (get-in scene [:meshes :ID10])
        material   {:values {:diffuse [0.800000011920929 0.09411760419607162 0.16862799227237701 1]}, :name "material_1"}
        combined   (let [comb (combine-primitives (get-in mesh-1 [:primitives 0])
                                                  (get-in mesh-2 [:primitives 0]))]
                     (js/console.log "comb: " (clj->js comb))
                     {:name "combined"
                      :primitives [{:attributes {:p      {:id         :combined-p
                                                          :data       (get-in comb [:attributes :p :data])
                                                          :immutable? true}
                                                 :normal {:id         :combined-normal
                                                          :data       (get-in comb [:attributes :normal :data])
                                                          :immutable? true}}
                                    :indices    (merge (:indices comb)
                                                       {:id :combined-indices
                                                        :immutable? true})
                                    :draw-mode  4
                                    :material (:material comb)
                                    :name "combined-primitive"
                                    :primitive 4}]})]
    (js/console.log "preprocess-red-egg-cube: " (clj->js combined))
    combined))

(defn preprocess-test-02 [scene]
  (let [path       [:scenes :defaultScene :nodes 0 :children :node_0]
        mesh-1     (get-in scene [:meshes :ID2])
        mesh-2     (get-in scene [:meshes :ID10])
        mesh-3     (get-in scene [:meshes :ID16])
        material   {:values {:diffuse [0.800000011920929 0.09411760419607162 0.16862799227237701 1]}, :name "material_1"}
        combined   (let [comb (-> (get-in mesh-1 [:primitives 0])
                                  (combine-primitives (get-in mesh-2 [:primitives 0]))
                                  (combine-primitives (get-in mesh-3 [:primitives 0])))]
                     
                     (js/console.log "comb: " (clj->js comb))
                     (js/console.log "m3: " (clj->js (get-in mesh-3 [:primitives 0])))
                     (js/console.log "comb m3: " (clj->js (combine-primitives comb (get-in mesh-3 [:primitives 0]))))
                     {:name "combined"
                      :primitives [{:attributes {:p      {:id         :combined-p
                                                          :data       (get-in comb [:attributes :p :data])
                                                          :immutable? true}
                                                 :normal {:id         :combined-normal
                                                          :data       (get-in comb [:attributes :normal :data])
                                                          :immutable? true}}
                                    :indices    (merge (:indices comb)
                                                       {:id :combined-indices
                                                        :immutable? true})
                                    :draw-mode  4
                                    :material (:material comb)
                                    :name "combined-primitive"
                                    :primitive 4}]})]
    (js/console.log "preprocess-red-egg-cube: " (clj->js combined))
    combined))

(defn preprocess-steve-manual [scene]
  (let [path       [:scenes :defaultScene :nodes 0 :children :node_0 :children :ID2 :children :ID3]
        mesh-1     (get-in scene [:meshes :ID4])
        mesh-2     (get-in scene [:meshes :ID12])
        mesh-3     (get-in scene [:meshes :ID20])
        mesh-4     (get-in scene [:meshes :ID72])
        mesh-5     (get-in scene [:meshes :ID80])
        mesh-6     (get-in scene [:meshes :ID32])
        mesh-7     (get-in scene [:meshes :ID140])
        material   {:values {:diffuse [0.800000011920929 0.09411760419607162 0.16862799227237701 1]}, :name "material_1"}
        combined   (let [comb (-> (combine-primitives (get-in mesh-1 [:primitives 0])
                                                      (get-in mesh-2 [:primitives 0]))
                                  (combine-primitives (get-in mesh-3 [:primitives 0]))
                                  (combine-primitives (get-in mesh-4 [:primitives 0]))
                                  (combine-primitives (get-in mesh-5 [:primitives 0]))
                                  (combine-primitives (get-in mesh-6 [:primitives 0]))
                                  (combine-primitives (get-in mesh-7 [:primitives 0])))]
                     (js/console.log "comb: " (clj->js comb))
                     {:name "combined"
                      :primitives [{:attributes {:p      {:id         :combined-p
                                                          :data       (get-in comb [:attributes :p :data])
                                                          :immutable? true}
                                                 :normal {:id         :combined-normal
                                                          :data       (get-in comb [:attributes :normal :data])
                                                          :immutable? true}}
                                    :indices    (merge (:indices comb)
                                                       {:id :combined-indices
                                                        :immutable? true})
                                    :draw-mode  4
                                    :material (:material comb)
                                    :name "combined-primitive"
                                    :primitive 4}]})]
    (js/console.log "preprocess-steve: " (clj->js combined))
    combined))

(defn preprocess-steve-auto [scene]
  (let [path     [:scenes :defaultScene :nodes 0 :children :node_0 :children :ID2 :children :ID3]
        meshes   (filter (fn [[_ md]]
                           (= 4 (get-in md [:primitives 0 :draw-mode]))) (:meshes scene))
        _ (js/console.log "meshes: " (clj->js meshes))
        _ (js/console.log "\t1st: " (pr-str (first meshes)))
        material {:values {:diffuse [0.800000011920929 0.09411760419607162 0.16862799227237701 1]}, :name "material_1"}
        combined (let [_       (js/console.log "here we go!"
                                               (clj->js (get-in (first meshes) [1 :primitives 0]))
                                               (clj->js (get-in (second meshes) [1 :primitives 0])))
                       initial (combine-primitives (get-in (first meshes) [1 :primitives 0])
                                                   (get-in (second meshes) [1 :primitives 0]))
                       _ (js/console.log "DUUUUVK")
                       comb    (reduce (fn [run [_ next-mesh]]
                                         (combine-primitives run (get-in next-mesh [:primitives 0]))) initial (drop 2 meshes)
                                         )]
                   (js/console.log "comb: " (clj->js comb))
                   {:name "combined"
                    :primitives [{:attributes {:p      {:id         :combined-p
                                                        :data       (get-in comb [:attributes :p :data])
                                                        :immutable? true}
                                               :normal {:id         :combined-normal
                                                        :data       (get-in comb [:attributes :normal :data])
                                                        :immutable? true}}
                                  :indices    (merge (:indices comb)
                                                     {:id :combined-indices
                                                      :immutable? true})
                                  :draw-mode  4
                                  :material material
                                  :name "combined-primitive"
                                  :primitive 4}]})]
    (js/console.log "preprocess-steve: " (clj->js combined))
    combined))

(defn preprocess-scene [scene]
  (let [meshes   (filter (fn [[_ md]]
                           (and md
                                (= 4 (get-in md [:primitives 0 :draw-mode])))) (:meshes scene))
        _ (js/console.log "meshes: " (clj->js meshes))
        _ (js/console.log "\t1st: " (pr-str (first meshes)))
        material {:values {:diffuse [0.800000011920929 0.09411760419607162 0.16862799227237701 1]}, :name "material_1"}
        combined (let [_       (js/console.log "here we go!"
                                               (clj->js (get-in (first meshes) [1 :primitives 0]))
                                               (clj->js (get-in (second meshes) [1 :primitives 0])))
                       initial (if (second meshes)
                                 (combine-primitives (get-in (first meshes) [1 :primitives 0])
                                                     (get-in (second meshes) [1 :primitives 0]))
                                 (get-in (first meshes) [1 :primitives 0]))
                       _       (js/console.log "DUUUUVK")
                       comb    (reduce (fn [run [_ next-mesh]]
                                         (combine-primitives run (get-in next-mesh [:primitives 0]))) initial (drop 2 meshes)
                                         )]
                   (js/console.log "comb: " (clj->js comb))
                   {:name "combined"
                    :primitives [{:attributes {:p      {:id         :combined-p
                                                        :data       (get-in comb [:attributes :p :data])
                                                        :immutable? true}
                                               :normal {:id         :combined-normal
                                                        :data       (get-in comb [:attributes :normal :data])
                                                        :immutable? true}}
                                  :indices    (merge (:indices comb)
                                                     {:id :combined-indices
                                                      :immutable? true})
                                  :draw-mode  4
                                  :material material
                                  :name "combined-primitive"
                                  :primitive 4}]})]
    (js/console.log "preprocess-chair: " (clj->js combined))
    combined))

(defn get-hmds [cb]
  (let [dev-ch      (chan)
        dev-promise (js/navigator.getVRDevices)
        fov->clj    (fn [fov]
                      {:up    (aget fov "upDegrees")
                       :down  (aget fov "downDegrees")
                       :left  (aget fov "leftDegrees")
                       :right (aget fov "rightDegrees")})
        dom-rect->clj (fn [dom-rect]
                        {:bottom (aget dom-rect "bottom")
                         :height (aget dom-rect "height")
                         :left   (aget dom-rect "left")
                         :right  (aget dom-rect "right")
                         :top    (aget dom-rect "top")
                         :width  (aget dom-rect "width")
                         :x      (aget dom-rect "x")
                         :w      (aget dom-rect "y")})
        dom-point->clj (fn [dom-point]
                         {:w (aget dom-point "w")
                          :x (aget dom-point "x")
                          :y (aget dom-point "y")
                          :z (aget dom-point "z")})]
    (.then dev-promise
           (fn [result]
             (let [devices    (js->clj result)
                   hmd        (some #(and (= (type %) js/HMDVRDevice)
                                          %) devices)
                   pos-sensor (when hmd
                                (some #(and (= (type %) js/PositionSensorVRDevice)
                                            (= (.-hardwareUnitId %)
                                               (.-hardwareUnitId hmd))
                                            %) devices))
                   devices    {:hmd hmd
                               :pos pos-sensor}]
               ;; Initialize the sensors, gather basic unchanging data
               ;; (fov, eye translations, etc.)
               (.resetSensor pos-sensor)
               (let [left-eye  (.getEyeParameters hmd "left")
                     left-eye  (-> {}
                                   (assoc :fov (fov->clj (aget left-eye "currentFieldOfView")))
                                   (assoc :max-fov (fov->clj (aget left-eye "maximumFieldOfView")))
                                   (assoc :min-fov (fov->clj (aget left-eye "minimumFieldOfView")))
                                   (assoc :rec-fov (fov->clj (aget left-eye "recommendedFieldOfView")))
                                   (assoc :translation (dom-point->clj (aget left-eye "eyeTranslation")))
                                   (assoc :render-rect (dom-rect->clj (aget left-eye "renderRect"))))
                     right-eye (js->clj (.getEyeParameters hmd "right"))
                     right-eye (-> {}
                                   (assoc :fov (fov->clj (aget right-eye "currentFieldOfView")))
                                   (assoc :max-fov (fov->clj (aget right-eye "maximumFieldOfView")))
                                   (assoc :min-fov (fov->clj (aget right-eye "minimumFieldOfView")))
                                   (assoc :rec-fov (fov->clj (aget right-eye "recommendedFieldOfView")))
                                   (assoc :translation (dom-point->clj (aget right-eye "eyeTranslation")))
                                   (assoc :render-rect (dom-rect->clj (aget right-eye "renderRect"))))]
                 (cb (merge devices
                            {:left-eye  left-eye
                             :right-eye right-eye}))))))))

(defn main [global-app-state node]
  (let [stop      (async/chan)
        controls  (async/chan)
        keyboard  (async/chan)
        gl        (.getContext node "webgl" #js {:antialias true})
        width     (.-clientWidth node)
        height    (.-clientHeight node)
        driver    (make-driver gl)
        programs  {:diffuse-flat                  (gd/program driver program-diffuse-flat)
                   :diffuse-light                 (gd/program driver program-diffuse-light)
                   :texture-flat                  (gd/program driver program-texture-flat)
                   :texture-light                 (gd/program driver program-texture-light)
                   :diffuse-per-fragment-light    (gd/program driver program-diffuse-per-fragment)
                   :texture-per-fragment-light    (gd/program driver program-texture-per-fragment)
                   :texture-specular-per-fragment (gd/program driver program-texture-specular-per-fragment)
                   :sky-box                       (gd/program driver program-sky-box)}
        now       (.getTime (js/Date.))
        watch-key (gensym)]
    (set! (.-glHandle js/window) gl)
    (reset-gl-canvas! node)
        (doto gl
          (.enable (.-DEPTH_TEST gl))
          (.enable ggl/SCISSOR_TEST)
          (.clearColor 0 0 0 1)
          (.clear (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl))))
    (go
      (let [fix-webgl-ch (<! (async/timeout 500))
            texture-ch   (chan)
            scene-ch     (chan)
            hmds-ch      (chan)
            _            (get-hmds #(put! hmds-ch %))
            ;; Try to get the hmds within 10ms or bail. Seems to be reliable.
            hmds         (async/alt! hmds-ch ([v] v)
                                     )
            app-state    (-> (app-state width height gl node hmds now)
                             (update-in [:comms] merge {:controls controls
                                                        :stop     stop
                                                        :keyboard keyboard})
                             (assoc-in [:runtime :programs] programs)
                             (assoc-in [:runtime :driver] driver)
                             (assoc-in [:debug] initial-query-map))
            _            (utils/load-cube-map gl (str "/images/skybox/" (get-in initial-query-map [:skybox-name 0]))
                                              (get-in initial-query-map [:skybox-name 1])
                                              {:min :linear
                                               :mag :linear}
                                              {:s :clamp-to-edge
                                               :t :clamp-to-edge}
                                              #(put! texture-ch %))
            _            (utils/http-get (str "/models/" (:model initial-query-map) ".gltf")
                                         #(put! scene-ch (-> (js/JSON.parse %)
                                                             (js->clj :keywordize-keys true)
                                                             (gltf/process-gltf))))
            skybox       (<! texture-ch)
            scene        (<! scene-ch)
            ;;red-egg-cube-path [:scenes :defaultScene :nodes 0 :children :node_0]
            ;;my-mesh           (preprocess-red-egg-cube scene)
            ;; chair-path        [:scenes :defaultScene :nodes 0 :children :node_0 :children :ID2 :children :ID3 :children :ID4]
            ;; my-mesh           (preprocess-chair scene)
            ;; steve-path        [:scenes :defaultScene :nodes 0 :children :node_0 :children :ID2 :children :ID3]
            ;; my-mesh           (preprocess-steve-auto scene)
            ;; test-02-path [:scenes :defaultScene :nodes 0 :children :node_0]
            ;; my-mesh           (preprocess-test-02 scene)
            ;; couch-path        [:scenes :defaultScene :nodes 0 :children :node_0 :children :ID2 :children :ID3]
            ;; my-mesh           (preprocess-chair scene)
            apt-path     [:scenes :defaultScene :nodes 0 ;; :children :node_0
                          ]
            sharp-path   [:scenes :defaultScene :nodes 0 :children :node_0 :children :ID1492 :children]
            ;;my-mesh      (preprocess-scene scene)
            ;;duck-path     [:scenes :defaultScene :nodes 0 :children]
            ;;my-mesh      (preprocess-chair scene)
            scene        (as-> scene new-scene
                           (update-in new-scene [:scenes :defaultScene :nodes]
                                      (fn [nodes]
                                        (mapv walk-tree-transforming-meshes nodes)))
                           (assoc-in new-scene [:originalScene] (get-in scene [:scenes :defaultScene]))
                           (assoc-in new-scene [:transformedScene] (get-in new-scene [:scenes :defaultScene]))
                           (update-in new-scene [:scenes :defaultScene :nodes]
                                      (fn [nodes]
                                        (if (:collapse-all? initial-query-map)
                                          (let [combined-primitives (->> nodes
                                                                         (map (fn [node] [(:name node) node]))
                                                                         (map (partial walk-tree-combining-meshes
                                                                                 #(get-in % [:material :values :diffuse]) 65532 0 []))
                                                                         (mapcat vals)
                                                                         flatten
                                                                         (sort-by primitive->program-name))
                                                all-meshes          (mapv (fn [idx primitive]
                                                                            (js/console.log "primitive: " (clj->js primitive))
                                                                            (let [tagged-primitive (-> primitive
                                                                                                       (update-in [:attributes :p] merge {:id (keyword (str "combined-p-" idx))
                                                                                                                                          :immutable? true})
                                                                                                       (update-in [:attributes :normal] merge {:id (keyword (str "combined-normal-" idx))
                                                                                                                                               :immutable? true})
                                                                                                       (update-in [:indices] merge {:id (keyword (str "combined-indices-" idx))
                                                                                                                                    :immutable? true}))
                                                                                  new-primitive    {:name (str "combined_mesh_" idx)
                                                                                                    :children []
                                                                                                    :matrix (mat/matrix44)
                                                                                                    :meshes [{:name       :combined-mesh
                                                                                                              :primitives [(merge {:primitive 4
                                                                                                                                   :draw-mode 4}
                                                                                                                                  tagged-primitive
                                                                                                                                  ;; Remove once we support materials
                                                                                                                                  ;;{:material  {:values {:diffuse [0.800000011920929 0.09411760419607162 0.16862799227237701 1]}, :name "material_1"}}
                                                                                                                                  )]}]}]
                                                                              (js/console.log "\tnew: " (clj->js new-primitive))
                                                                              new-primitive))
                                                                          (range) combined-primitives)]
                                            (js/console.log "all-meshes: " (clj->js all-meshes))
                                            all-meshes)
                                          nodes))))
            app-state    (-> app-state
                             (assoc-in [:skybox :texture] (assoc skybox
                                                                 :immutable? true
                                                                 :texture-id 1))
                             (assoc-in [:gltf] scene)
                             (assoc-in [:hmds] hmds))
            _            (swap! global-app-state merge app-state)
            app-state    global-app-state
            next-tick    (fn []
                           (main* app-state {:comms {:stop     stop
                                                     :controls controls
                                                     :keyboard keyboard}}))]
        (<! (async/timeout 500))
        (fix-webgl-inspector-quirks true true 250)
        (aset js/window "processedGLTF" (clj->js scene))
        (aset js/window "scene" scene)
        
        (when-let [capture (and (:capture-first-frame? initial-query-map)
                                (.-captureNextFrame js/window))]
          (.call capture))
        (if (:tick-first-frame? initial-query-map)
          (set! (.-tick js/window) next-tick)
          (next-tick))))))


(comment
  (let [node                   (get-in @as [:gltf :scenes :defaultScene :nodes 0])
        mesh                   (get-in node [:meshes 0])
        primitives             (get-in mesh [:primitives])
        max-entries-per-buffer 65532
        partition-buffers      (fn [element-type-constructor original-typed-array]
                                 (let [original-buffer-length (.-length original-typed-array)
                                       original-buffer        (.-buffer original-typed-array)
                                       bpe                    (.-BYTES_PER_ELEMENT element-type-constructor)]
                                   (loop [new-buffers   []
                                          byte-offset 0]
                                     ;; If there are more
                                     ;; entries remaining from
                                     ;; the byte-offset, we cut it up
                                     ;; and loop again, else
                                     ;; return what we have
                                     (let [remaining-entries         (- original-buffer-length (/ byte-offset bpe))
                                           next-buffer-element-count (min remaining-entries max-entries-per-buffer)
                                           next-buffer               (element-type-constructor. original-buffer byte-offset next-buffer-element-count)]
                                       (println byte-offset max-entries-per-buffer remaining-entries bpe original-buffer-length (/ byte-offset bpe) "\n")
                                       (if (< max-entries-per-buffer remaining-entries)
                                         (recur (conj new-buffers next-buffer) (+ byte-offset (* max-entries-per-buffer bpe)))
                                         (conj new-buffers next-buffer))))))]
    (mapv (fn [primitive]
            (let [p       (get-in primitive [:attributes :p :data])
                  normal  (get-in primitive [:attributes :normal :data])
                  indices (get-in primitive [:indices :data])]
              {:ps      (partition-buffers js/Float32Array p)
               :normals (partition-buffers js/Float32Array normal)})) primitives)))
