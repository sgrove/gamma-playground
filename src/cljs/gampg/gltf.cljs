(ns gampg.gltf
  (:require [clojure.string :as s]
            [gampg.utils :as utils]
            [goog.crypt.base64 :as b64]
            [thi.ng.geom.core.matrix :as mat :refer [M44]]))

;; Helper namespace for processing a gltf file. Note that this only
;; supports entirely embedded documents (only data-uris, no external
;; uris), and even then there may be race conditions with image
;; data. The next major rework will turn it into an asynchronously
;; loading library to remove this major limitation.

;; TODO: Support a pre-processing step to do all of this and save it
;; out to transit, hopefully with similar file size and already 99%
;; processed upon load.

(defn decode-data-uri-text [base-64? data]
  (let [result (js/window.decodeURIComponent data)]
    (if base-64?
      (b64/decodeString result)
      result)))

(defn decode-data-uri-array-buffer [data base-64?]
  (let [byte-string (decode-data-uri-text base-64? data)
        buffer      (js/ArrayBuffer. (count byte-string))
        view        (js/Uint8Array. buffer)]
    ;; XXX: this *looks* like the right way to load data, but not 100%
    ;; sure
    (dotimes [i (count byte-string)]
      (let [value (.charCodeAt byte-string i)]
        (aset view i value)))
    buffer))

(defn data-uri->mime-type [uri]
  (-> uri
      (s/split #";")
      first
      (s/split ":")
      last
      (s/split "/")
      (->>
       (mapv keyword))))

(defn data-uri->array-buffer [uri]
  (-> uri
      (s/split #",")
      second
      (decode-data-uri-array-buffer true)))

(defn process-buffers [gltf]
  ;; TODO: handle different buffer types, perhaps?
  (let [buffers (:buffers gltf)]
    (assoc gltf :buffers
           (reduce merge {} (map (fn [[buffer-name buffer-description]]
                                   {buffer-name (assoc buffer-description
                                                       :data (data-uri->array-buffer (:uri buffer-description))
                                                       :immutable? true)}) buffers)))))

(defn process-buffer-views [gltf]
  (let [buffer-views (:bufferViews gltf)]
    (assoc gltf
           :bufferViews
           (reduce merge {}
                   (map (fn [[bv-name bv-description]]
                          (let [buffer      (get-in gltf [:buffers (keyword (:buffer bv-description))])
                                byte-offset (:byteOffset bv-description)
                                byte-length (:byteLength bv-description)
                                data        (js/DataView. (:data buffer) byte-offset byte-length)]
                            {bv-name (assoc bv-description
                                            :data data
                                            :immutable? true)})) buffer-views)))))

(defn process-accessors [gltf]
  (let [accessors (:accessors gltf)]
    (assoc gltf :accessors
           (reduce merge {}
                   (map (fn [[acc-name acc-description]]
                          (let [type->constructor {:byte           js/Int8Array
                                                   :unsigned-byte  js/Uint8Array
                                                   :short          js/Int16Array
                                                   :unsigned-short js/Uint16Array
                                                   :float          js/Float32Array}
                                
                                data-type         (utils/value->enum (:componentType acc-description))
                                constructor       (get type->constructor data-type)
                                clj-type->count   {:scalar 1
                                                   :vec2   2
                                                   :vec3   3
                                                   :vec4   4
                                                   :mat2   4
                                                   :mat3   9
                                                   :mat4   16}
                                clj-type          (keyword (s/lower-case (:type acc-description)))
                                elems-per-read    (get clj-type->count clj-type)
                                bv                (get-in gltf [:bufferViews (keyword (:bufferView acc-description))])
                                offset            (:byteOffset acc-description)
                                stride            (:byteStride acc-description)
                                size              (:count acc-description)
                                bv-byte-length    (.-byteLength (:data bv))
                                bytes-per-element (.-BYTES_PER_ELEMENT constructor)
                                total-size        (/ bv-byte-length bytes-per-element )
                                data              (constructor. (.-buffer (:data bv)) offset (* size elems-per-read))
                                data2             (constructor. (* size elems-per-read))
                                little-endian?    true]
                            
                            ;; XXX: Count seems off by factor of 6 if
                            ;; componentType is a Float, but maybe
                            ;; that doesn't matter, it should just be
                            ;; passed to the GPU
                            ;;(js/console.log (pr-str acc-name) " Reading " size (pr-str clj-type) ", " elems-per-read " elems/read, " bytes-per-element " bytes/elem, into " (* size elems-per-read) " byte buffer from offset " offset)
                            (dotimes [i size]
                              (let [root-read-offset        (+ offset (* (max 2 stride) i))
                                    [method little-endian?] (condp = data-type
                                                              :byte           [(.-getInt8 (:data bv)) true]
                                                              :unsigned-byte  [(.-getUint8 (:data bv)) true]
                                                              :short          [(.-getInt16 (:data bv)) true]
                                                              :unsigned-short [(.-getUint16 (:data bv)) true]
                                                              :float          [(.-getFloat32 (:data bv)) true])]
                                ;; XXX: This formula is BUGGED! It will fail for Uint8 since we stride a minimum of 2 bytes per read. FIXIT!
                                ;; clj-type: scalar
                                ;; elems-per-read: 1
                                ;; offset: 72
                                ;; stride: 0
                                ;; i: 0
                                ;; bytes-per-element: 2
                                ;; read 72 (+ offset (* stride i) (* bytes-per-element n)) => (+ 72 0 0) => 72
                                ;; i: 1
                                ;; read 74 (+ offset (* stride i) (* bytes-per-element n)) => (+ 72 0 0) => 72
                                ;; i: 2
                                ;; read 76 (+ offset (* stride i) (* bytes-per-element n)) => (+ 72 0 0) => 72

                                ;; clj-type :vec3
                                ;; elems-per-read: 3
                                ;; offset: 0
                                ;; i: 0
                                ;; bytes-per-element: 4
                                ;; stride: 12
                                ;; read 3:
                                ;; read 0 0 + 0
                                ;; read 4 0 + 1
                                ;; read 8 0 + 2
                                ;; i: 1
                                ;; read 12 (* stride 1) + (* bytes-per-element 0) 
                                ;; read 16 (* stride 1) + (* bytes-per-element 1)
                                ;; read 20 (* stride 1) + (* bytes-per-element 2)
                                ;; i: 2
                                ;; read 24 (* stride 2) + 0
                                ;; read 28
                                ;; read 32
                                (try
                                  (dotimes [n elems-per-read]
                                    (let [byte-offset (+ root-read-offset (* n bytes-per-element))
                                          value       (.call method (:data bv) byte-offset little-endian?)
                                          idx         (+ (* i elems-per-read) n)]
                                      ;; (js/console.log (pr-str clj-type) (pr-str data-type) n i (+ i n) offset (+ offset (* i bytes-per-element)) (* stride i) " => " root-read-offset byte-offset " ==> " value)
                                      ;; (js/console.log "\t" idx " -> " value)
                                      
                                      (aset data2 idx value)))
                                  (catch js/Error e
                                    (do
                                      (js/console.log "Failed to read " (pr-str data-type) "(" bytes-per-element "/element) @ root-offset: " root-read-offset (+ root-read-offset (* stride i)))
                                      (throw e))))))
                            ;;(js/console.log "Data read: " (.-length data2) " length")
                            #_(js/console.log "Process-Accessors: " (pr-str acc-name)
                                            (pr-str {:data-type      (pr-str data-type)
                                                     :offset         offset
                                                     :stride         stride
                                                     :size           size
                                                     :bv-byte-length bv-byte-length
                                                     :bpe            bytes-per-element
                                                     "Total size: "  (* size (get clj-type->count clj-type))
                                                     :data2 data2})
                                            data2)
                            {acc-name (assoc acc-description
                                             :data data2
                                             :immutable? true
                                             :buffer-view bv
                                             :clj-type clj-type)})) accessors)))))

(defn process-images [gltf]
  (assoc gltf :images
         (reduce merge {}
                 (map (fn [[image-name image-description]]
                        (let [buffer-data (data-uri->array-buffer (:uri image-description))
                              mime-type   (data-uri->mime-type (:uri image-description))
                              image       (js/Image.)]
                          (set! (.-src image) (:uri image-description))
                          {image-name (merge image-description
                                             {:data        image
                                              :immutable?  true
                                              :buffer-data buffer-data
                                              :mime        mime-type})})) (:images gltf)))))

(defn process-samplers [gltf]
  (assoc gltf :samplers
         (reduce merge {}
                 (map (fn [[sampler-name sampler-description]]
                        (let [min-filter :nearest ;;(utils/value->enum (:minFilter sampler-description))
                              mag-filter :nearest ;;(utils/value->enum (:magFilter sampler-description))
                              wrap-s     (utils/value->enum (:wrapS sampler-description))
                              wrap-t     (utils/value->enum (:wrapT sampler-description))]
                          {sampler-name (merge sampler-description
                                               {:filter {:min min-filter
                                                         :mag mag-filter}
                                                :wrap {:s wrap-s
                                                       :t wrap-t}})})) (:samplers gltf)))))

(defn process-textures [gltf]
  (assoc gltf :textures
         (reduce merge
                 (map (fn [[texture-name texture-description]]
                        (let [clj-fmt          (utils/value->enum (:format texture-description))
                              clj-internal-fmt (utils/value->enum (:internalFormat texture-description))
                              sampler          (get-in gltf [:samplers (keyword (:sampler texture-description))])
                              src              (get-in gltf [:images (keyword (:source texture-description))])
                              target           (utils/value->enum (:target texture-description))
                              type             (utils/value->enum (:type texture-description))]
                          {texture-name (merge texture-description
                                               {:clj-format          clj-fmt
                                                :clj-internal-format clj-internal-fmt
                                                :sampler             sampler
                                                :target              target
                                                :type                type}
                                               ;; Merging this here as
                                               ;; well so it conforms
                                               ;; with Gamma's view of
                                               ;; texture
                                               ;; :filter/:wrap key
                                               ;; placement
                                               sampler
                                               src)})) (:textures gltf)))))

(defn process-shaders [gltf]
  (let [shaders (:shaders gltf)]
    (assoc gltf :shaders
           (reduce merge {}
                   (mapv (fn [[shader-name shader-description]]
                           (let [clj-type (get {35632 :fragment-shader
                                                35633 :vertex-shader} (:type shader-description))
                                 uri      (:uri shader-description)
                                 src      (-> uri
                                              (s/split #",")
                                              last
                                              js/window.atob)]
                             {shader-name (assoc shader-description
                                                 :clj-type clj-type
                                                 :src src)})) shaders)))))


(defn process-programs [gltf]
  (let [programs (:programs gltf)]
    (assoc gltf :programs
           (reduce merge {}
                   (map
                    (fn [[program-name program-description]]
                      (let [fs (get-in gltf [:shaders (keyword (:fragmentShader program-description))])
                            vs (get-in gltf [:shaders (keyword (:vertexShader program-description))])]
                        {program-name (assoc program-description
                                             :fragment-shader fs
                                             :vertex-shader vs)}))
                    programs)))))

(defn process-techniques [gltf]
  (js/console.log "XXX: Implement process-techniques")
  gltf)

(defn process-materials [gltf]
  (assoc gltf :materials
         (reduce merge {}
                 (map
                  (fn [[material-name material-description]]
                    (let [values  (get-in material-description [:instanceTechnique :values])
                          ;; XXX Check if this is a vector or a map,
                          ;; and change accordingly (right now
                          ;; hard-coding for the duck)
                          ;; XXX: Map this over every value rather than just diffuse
                          diffuse (get-in gltf [:textures (keyword (:diffuse values))])]
                      {material-name {:values (merge values
                                                     (when diffuse {:diffuse diffuse}))
                                      :name   (:name material-description)}})) (:materials gltf)))))

(defn process-attr [gltf [attr-name accessor-name]]
  (let [accessor (get-in gltf [:accessors (keyword accessor-name)])
        normalized-attr-name (get {:NORMAL      :normal
                                   :POSITION    :p
                                   :TEXCOORD_0 :tex-coord-0
                                   :TEXCOORD_1 :tex-coord-1
                                   :TEXCOORD_2 :tex-coord-2
                                   :TEXCOORD_3 :tex-coord-3
                                   :TEXCOORD_4 :tex-coord-4} attr-name (keyword attr-name))]
    {normalized-attr-name (merge accessor
                                 {:accessor-name accessor-name})}))

(defn process-meshes [gltf]
  ;; XXX: Still have to handle materials <- techniques
  (assoc gltf :meshes
         (reduce merge {}
                 (map
                  (fn [[mesh-name mesh-description]]
                    ;; Is each primitive meant to be a draw-call?
                    {mesh-name (assoc mesh-description
                                      :primitives
                                      (let [primitives (:primitives mesh-description)]
                                        (map (fn [primitive]
                                               (let [attrs (reduce merge {} (mapv (partial process-attr gltf) (:attributes primitive)))
                                                     indices (merge (get-in gltf [:accessors (keyword (:indices primitive))])
                                                                    {:accessor-name (keyword (:indices primitive))
                                                                     :immutable? true})
                                                     material (get-in gltf [:materials (keyword (:material primitive))])]
                                                 (assoc primitive
                                                        :name mesh-name
                                                        :attributes attrs
                                                        :indices indices
                                                        :material material
                                                        :draw-mode (:primitive primitive)))) primitives)))})
                  (:meshes gltf)))))

(defn process-nodes [gltf]
  (let [nodes (:nodes gltf)]
    (assoc gltf :nodes
           (reduce merge {}
                   (map (fn helper [[node-name node-description]]
                          (let [children (reduce merge {} (mapv (fn [child-name]
                                                                 (helper [child-name (get-in gltf [:nodes (keyword child-name)])])) (:children node-description)))
                                meshes   (map #(get-in gltf [:meshes (keyword %)]) (:meshes node-description))
                                ;; TODO: Parameterize constructors so
                                ;; caller can decide on hydrated data
                                ;; structure
                                matrix   (mat/matrix44 (js->clj (:matrix node-description)))
                                node     (assoc node-description
                                                :children children
                                                :meshes meshes
                                                :matrix matrix)]
                            {node-name node})) nodes)))))

(defn process-scenes [gltf]
  (let [scenes (:scenes gltf)]
    (assoc gltf :scenes
           (reduce merge {}
                   (map (fn [[scene-name scene-description]]
                          (let [nodes (map #(get-in gltf [:nodes (keyword %)]) (:nodes scene-description))]
                            {scene-name (assoc scene-description
                                               :nodes nodes)})) scenes)))))

(defn process-gltf [gltf]
  (-> gltf
      process-buffers
      process-buffer-views
      process-accessors
      process-shaders
      process-programs
      process-images
      process-samplers
      process-textures
      process-techniques
      process-materials
      process-meshes
      process-nodes
      process-scenes
      (assoc :scene (keyword (:scene gltf)))))
