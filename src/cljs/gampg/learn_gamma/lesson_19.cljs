(ns ^:figwheel-always gampg.learn-gamma.lesson-19
    (:require [cljs.core.async :as async :refer [<! put! chan]]
              [gamma.api :as g]
              [gamma.program :as p]
              [gamma-driver.api :as gd]
              [gamma-driver.drivers.basic :as driver]
              [gampg.learn-gamma.lesson-01 :as lesson-01]
              [gampg.learn-gamma.lesson-02 :as lesson-02]
              [gampg.learn-gamma.programs :as progs]
              [gampg.learn-gamma.programs.skybox :as skybox]
              [gampg.utils :as utils]
              [goog.webgl :as ggl]
              [thi.ng.geom.core :as geom]
              [thi.ng.geom.core.matrix :as mat :refer [M44]]
              [thi.ng.geom.core.vector :as v :refer [vec2 vec3]]
              [thi.ng.geom.core.utils :as gu]
              [thi.ng.geom.aabb :as a]
              [thi.ng.geom.plane :as pl]
              [thi.ng.geom.quad :as q]
              [thi.ng.geom.webgl.core :as gl]
              [thi.ng.geom.webgl.buffers :as buf])
    (:require-macros [cljs.core.async.macros :as async :refer [go]]))

(def title
  "19. Shadow maps")

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

(defn app-state [width height]
  {:last-rendered 0
   :scene         {:mv              (mat/matrix44)
                   :p               (utils/get-perspective-matrix 45 width height)
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
   :texture-coords {:data       [1.0 1.0
                                 0.0 1.0
                                 1.0 0.0
                                 0.0 0.0]
                    :immutable? true
                    :id         :laptop-screen-texture-coords}})

(defn get-data [p mv vertices normals color-texture texture-coords point-lighting-location diffuse-color emissive-color ambient-color]
  (let [now (/ (.getTime (js/Date.)) 1000)]
    {progs/u-p-matrix                      p
     progs/u-mv-matrix                     mv
     progs/u-n-matrix                      (utils/get-normal-matrix mv)
     progs/u-ambient-lighting-color        [0.8 0.8 0.8]
     progs/u-point-lighting-location       point-lighting-location
     progs/u-point-lighting-diffuse-color  [0.8 0.8 0.8]
     progs/u-point-lighting-specular-color [0.8 0.8 0.8]
     progs/u-material-ambient-color        ambient-color
     progs/u-material-diffuse-color        diffuse-color
     progs/u-material-emissive-color       emissive-color
     progs/u-material-shininess            32
     progs/u-sampler                       color-texture
     progs/a-position                      vertices
     progs/a-texture-coord                 texture-coords
     progs/a-vertex-normal                 normals}))

(defn draw-scene [driver state now spot-location spot-direction  spot-inner-angle spot-outer-angle spot-radius spot-color mv target]
  (let [programs            (get-in state [:programs])
        {:keys [p
                color-texture specular-texture
                cube-1
                model
                rotation]}  (:scene state)
        
        rotation            (- (utils/deg->rad now))
        square-mv           (-> mv
                                ;;(geom/translate [(* rotation 4) -0.5 -5])
                                (geom/translate [(* (js/Math.sin (/ now 8)) 4) (* (js/Math.cos (/ now 3)) 2) -5])
                                (geom/* (-> (mat/matrix44)
                                            (geom/rotate-around-axis [0 1 0] rotation)
                                            (geom/rotate-around-axis [1 0 0] (- (/ js/Math.PI 3)))))
                                object-array)
        model-mv            (do (-> mv
                                    (geom/translate [0 -0.5 -7 ;;(- -1.0 (js/Math.abs (* 5 (js/Math.sin (/ now 20)))))
                                                     ])
                                    (geom/* (-> M44
                                                (geom/rotate-around-axis [1 0 0] (- (/ js/Math.PI 2)))))
                                    )
                                (-> (mat/look-at (vec3 0 -0.5 -7)
                                                 (vec3 (/ (get-in state [:mouse :pos 0]) 1000)
                                                       (/ (get-in state [:mouse :pos 2]) 1000)
                                                       1)
                                                 (vec3 0 0 1))
                                    (geom/rotate-around-axis [1 0 0] (- (/ js/Math.PI 2)))))
        cube-1-mv           (-> mv
                                (geom/translate [0 0 -10]))
        scene-data          (-> (get-data p model-mv (:vertices model) (:normals model) color-texture (:texture-coords model) (object-array spot-location) #js[0.2 0.2 0.2]  #js[0.2 0.2 0.2]  #js[0.2 0.2 0.2])
                                (select-keys (get-in programs [:specular :inputs]))
                                (assoc {:tag :element-index} (:indices model)))
        screen-data         (-> (get-data p model-mv (:vertices laptop-screen) (:normals laptop-screen) (get-in state [:framebuffer :depth])
                                          (:texture-coords laptop-screen) nil #js[0.2 0.2 0.2]  #js[0.2 0.2 0.2]  #js[0.2 0.2 0.2])
                                (select-keys (get-in programs [:specular :inputs]))
                                (assoc {:tag :element-index} (:indices model)))
        cube-1-data         (-> (get-data p cube-1-mv (:vertices cube-1) (:normals cube-1) color-texture (:texture-coords cube-1) (object-array spot-location) #js[0.2 0.2 0.2]  #js[0.2 0.2 0.2]  #js[0.2 0.2 0.2])
                                (select-keys (get-in programs [:specular :inputs]))
                                (assoc {:tag :element-index} (:indices cube-1)))
        screen-texture-data {progs/u-p-matrix  p
                             progs/u-mv-matrix square-mv
                             progs/a-position  (get-in state [:scene :square-vertices])
                             progs/a-color     (get-in state [:scene :square-colors])}]
    (when target
      (let [black  (:uniform-color programs)
            inputs (:inputs black)]
        (gd/draw-elements driver (gd/bind driver black (assoc (select-keys cube-1-data inputs)
                                                              progs/u-color #js[0 0 0 1]
                                                              {:tag :element-index} (get scene-data {:tag :element-index})))
                          {:draw-mode :triangle-strip
                           :first     0
                           :count     18 ;;(get-in cube-1 [:indices :count])
                           }
                          target)
        (gd/draw-elements driver (gd/bind driver black (assoc (select-keys scene-data inputs)
                                                              progs/u-color #js[0 0 0 1]
                                                              {:tag :element-index} (get scene-data {:tag :element-index})))
                          {:draw-mode :triangles
                           :first     0
                           :count     (get-in model [:indices :count])} target)))
    (when-not target
      (let [program (:shadow-map programs)
            inputs  (:inputs program)]
        (progs/draw-specular-with-shadow-map driver gd/draw-elements program
                                             p cube-1-mv (utils/get-normal-matrix cube-1-mv)
                                             (:vertices cube-1) (:normals cube-1)
                                             spot-location spot-direction
                                             spot-inner-angle spot-outer-angle
                                             spot-radius spot-color
                                             (:texture-coords cube-1) color-texture (get-in state [:framebuffer :depth])
                                             (:indices cube-1) :triangle-strip 0 36 ;;(get-in cube-1 [:indices :count])
                                             )
        
        (progs/draw-specular-with-shadow-map driver gd/draw-elements program
                                             p model-mv (utils/get-normal-matrix model-mv)
                                             (:vertices model) (:normals model)
                                             spot-location spot-direction
                                             spot-inner-angle spot-outer-angle
                                             spot-radius spot-color
                                             (:texture-coords model) color-texture (get-in state [:framebuffer :depth])
                                             (:indices model) :triangles 0 (get-in model [:indices :count]))

        (let [final-data (-> scene-data
                             (select-keys (get-in programs [:specular :inputs]))
                             (assoc {:tag :element-index} (get scene-data {:tag :element-index})))]
          (gd/draw-elements driver (gd/bind driver (get programs :specular) final-data) {:draw-mode :triangles
                                                                                          :first     0
                                                                                          :count     (get-in model [:indices :count])}))))))

(def square
  {:vertices {:data       [[ 1  1  0]
                           [-1  1  0]
                           [ 1 -1  0]
                           [-1 -1  0]]
              :immutable? true
              :id         :square-vertices}
   :colors   {:data       [[1 0 0 1]
                           [0 1 0 1]
                           [0 0 1 1]
                           [1 1 1 1]]
              :immutable? true
              :id         :square-colors}
   :texture-coords {:id         :square-texture-coords
                    :immutable? true
                    :data       [0.0 0.0
                                 1.0 0.0
                                 1.0 1.0
                                 0.0 1.0]}})

(defn draw-fn [gl driver]
  (fn [state]
    (let [programs (:programs state)
          gd-fb              (get-in state [:framebuffer])
          raw-fb             (get-in gd-fb [:frame-buffer])
          now                (/ (.getTime (js/Date.)) 250)
          spot-location      (vec3 [(* 20 js/Math.PI (js/Math.cos (/ now 3))) 0 0;; 0
                                    ]) ;; 0)
          spot-direction     (vec3  #_[(* 2 js/Math.PI (js/Math.sin now)) ;;(utils/deg->rad (* (get-in state [:mouse :pos 0]) 2))
                                       (* 2 js/Math.PI (js/Math.cos (/ now 3))) ;;
                                     1 ;;(utils/deg->rad (* (get-in state [:mouse :pos 1]) 2))
                                     
                                     ]
                                    [0 -0.5 -7])
          spot-inner-angle   0
          spot-outer-angle   2
          spot-radius        1
          spot-color         #js[0.7 0.7 0.7]
          light-view-matrix  (mat/look-at spot-location spot-direction (vec3 0 0 1))
          camera-view-matrix (-> M44
                                 (geom/translate [-3 1 2.5])
                                 (geom/rotate-around-axis [0 1 0] (utils/deg->rad -30)))]
      (js/console.log (object-array spot-location) (object-array spot-direction))
      ;; First draw to the framebuffer      
      (.viewport gl 0 0 512 512)
      (.bindFramebuffer gl ggl/FRAMEBUFFER raw-fb)
      (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
      (.bindFramebuffer gl ggl/FRAMEBUFFER nil)
      (draw-scene driver state now spot-location spot-direction spot-inner-angle spot-outer-angle spot-radius spot-color light-view-matrix gd-fb)
      ;; Reset the viewport and draw the "real" scene
      (.viewport gl 0 0 (get-in state [:canvas :width]) (get-in state [:canvas :height]))
      (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
      (draw-scene driver state now spot-location spot-direction  spot-inner-angle spot-outer-angle spot-radius spot-color camera-view-matrix nil)
      (let [program (:simple-texture programs)
            inputs  (:inputs program)
            p       (get-in state [:scene :p])
            mv      (-> M44
                        (geom/scale 0.2 0.2 1)
                        (geom/translate [0 0 -3]))
            data    {progs/u-p-matrix      p
                     progs/u-mv-matrix     mv
                     progs/a-position      (:vertices square)
                     progs/a-color         (:colors square)
                     progs/a-texture-coord (:texture-coords square)
                     progs/u-sampler       (if (pos? (js/Math.cos (/ now 10)))
                                             (:depth gd-fb)
                                             (:color gd-fb))}]
        (gd/draw-arrays driver (gd/bind driver program data) {:draw-mode :triangle-strip})))))

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

(defn render-loop [draw-fn state-atom stop-ch]
  (js/requestAnimationFrame
   (fn [time]
     (let [state      @state-atom
           driver     (get-in state [:runtime :driver])
           draw-frame (draw-fn (gd/gl driver) driver)]
       (draw-frame state))
     (if (:manual-tick? initial-query-map)
       (set! (.-tick js/window)
             #(render-loop draw-fn state-atom stop-ch))
       (render-loop draw-fn state-atom stop-ch)))))

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

(defn main* [app-state opts]
  (let [histories                (get opts :histories (atom {})) 
        [controls-ch
         stop-ch    
         keyboard-ch]            [(get-in @app-state [:comms :controls])
                                  (get-in @app-state [:comms :stop])
                                  (get-in @app-state [:comms :keyboard])]
        cast!                    (fn [message data & [elide-from-history?]]
                                   (async/put! controls-ch [message data elide-from-history?]))
        handle-mouse-move!       #(handle-mouse-move cast! %)
        handle-canvas-mouse-down #(handle-mouse-down cast! %)
        handle-canvas-mouse-up   #(handle-mouse-up   cast! %)
        handle-close!            #(cast! :application-shutdown [@histories])]
    (js/document.addEventListener "mousemove"    handle-mouse-move! false)
    (js/window.addEventListener   "beforeunload" handle-close!)
    (def as app-state)
    (set! (.-state js/window) app-state)
    (render-loop draw-fn app-state nil)
    (js/console.log "channels: " (clj->js [controls-ch stop-ch keyboard-ch]))
    (async/go
      (loop []
        (async/alt!
          controls-ch ([[msg data transient?]]
                       (js/console.log "Controls Message: " (pr-str msg)  " -> " (pr-str data))
                       (let [previous-state @app-state]
                         (swap! app-state
                                (fn [state]
                                  (tick (control-event msg data state) (.getTime (js/Date.)))))
                         (post-control-event! msg data previous-state @app-state)))
          ;; XXX: Should probably remove this for replay needs
          (async/timeout 15) ([]
                              (swap! app-state tick)))
        (recur)))))

(defn main [global-app-state node]
  (let [stop          (async/chan)
        controls      (async/chan)
        keyboard      (async/chan)
        gl            (.getContext node "webgl")
        gl-extensions [(.getExtension gl "WEBGL_depth_texture")]
        width         (.-clientWidth node)
        height        (.-clientHeight node)
        driver        (utils/make-driver gl)
        programs      {:specular      (gd/program driver progs/program-specular)
                       :shadow-map    (gd/program driver progs/program-specular-with-shadow-map)
                       :simple        (gd/program driver lesson-02/program-source)
                       :skybox        (gd/program driver skybox/program-skybox)
                       :uniform-color (gd/program driver progs/uniform-color)
                       :simple-texture (gd/program driver progs/simple-texture)}
        ;; WxH must be a power of two (e.g. 64, 128, 256, 512, 1024, etc.)
        fb-width      512
        fb-height     512
        fb            (utils/make-frame-buffer driver fb-width fb-height)
        local-state   (-> (app-state width height)
                          (assoc :framebuffer fb))
        _             (swap! global-app-state merge local-state)
        app-state     global-app-state]
    (utils/reset-gl-canvas! node)
    (.enable gl (.-DEPTH_TEST gl))
    (.clearColor gl 0 0.25 0 1)
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (go
      (let [texture-loaded-ch    (chan)
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
            skybox-ch            (chan)
            _                    (utils/load-cube-map gl (str "/images/skybox/" "sky1") "png"
                                                      
                                                      {:min :linear
                                                       :mag :linear}
                                                      {:s :clamp-to-edge
                                                       :t :clamp-to-edge}
                                                      #(put! skybox-ch %))
            skybox               (<! skybox-ch)
            color-image          (<! texture-loaded-ch)
            color-texture        {:data       color-image
                                  :filter     {:min :linear
                                               :mag :nearest}
                                  :wrap       {:s :repeat
                                               :t :repeat}
                                  :flip-y     true
                                  :immutable? true
                                  :id         :laptop-texture}
            _                    (swap! app-state (fn [state]
                                                    (-> state
                                                        (update-in [:comms] merge {:controls controls
                                                                                   :stop     stop
                                                                                   :keyboard keyboard})
                                                        (assoc-in [:runtime :driver] driver)
                                                        (assoc-in [:programs] programs)
                                                        (assoc-in [:scene :color-texture] color-texture)
                                                        (assoc-in [:scene :model] model)
                                                        ;;(assoc-in [:scene :sphere] (utils/generate-sphere :my-sphere 5 5 2))
                                                        (assoc-in [:scene :cube-1] (utils/generate-cube :cube-1 4 4 0.3))
                                                        (assoc-in [:skybox :texture] (assoc skybox
                                                                                            :immutable? true
                                                                                            :texture-id 3))
                                                        (assoc-in [:webgl :extensions] gl-extensions))))
            next-tick            (fn []
                                   (main* app-state {:comms {:stop     stop
                                                             :controls controls
                                                             :keyboard keyboard}}))]
        ;; Wait 100ms, and then fix the WebGL inspector if it's there.
        (<! (async/timeout 100))
        (set! (.-glHandle js/window) gl)
        (utils/fix-webgl-inspector-quirks true true 250)
        (if (:tick-first-frame? initial-query-map)
          (set! (.-tick js/window) next-tick)
          (do (<! (async/timeout 100))
              (next-tick)))))))

(def explanation
  nil)

(def summary
  {:debug-keys  [[:mouse :pos]]
   :title       title
   :enter       main
   :explanation explanation})
