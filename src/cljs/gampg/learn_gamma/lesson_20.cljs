(ns ^:figwheel-load gampg.learn-gamma.lesson-20
    (:require [cljs.core.async :as async :refer [<! put! chan]]
              [clojure.string :as string]
              [gamma.api :as g]
              [gamma.program :as p]
              [gamma-driver.api :as gd]
              [gamma-driver.drivers.basic :as driver]
              [gampg.utils :as utils]
              [goog.Uri]
              [goog.webgl :as ggl]
              [thi.ng.geom.core :as geom]
              [thi.ng.geom.core.matrix :as mat :refer [M44]]
              [thi.ng.geom.core.vector :as vec])
    (:require-macros [cljs.core.async.macros :as async :refer [go]])
    (:import [goog.net XhrIo]))

(def title
  "20. Skyboxes")

;; Generate your own skybox here (as of June 3, 2015)
;; http://www.nutty.ca/webgl/skygen/

(def initial-query-map
  (let [parsed-uri (goog.Uri. (.. js/window -location -href))
        ks         (.. parsed-uri getQueryData getKeys)
        defaults   {:tick-first-frame?    false
                    :manual-tick?         false
                    :capture-first-frame? false}
        initial    (reduce merge {} (map (partial utils/uri-param parsed-uri) (clj->js ks)))
        special {:skybox-name (when-let [skybox-name (second (utils/uri-param parsed-uri "skybox-name" "sky1.png"))]
                                (vec (string/split skybox-name ".")))}]
    (merge defaults initial special)))

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

(def program-sky-box
  (p/program
   {:id              :lesson-20-sky-box
    :vertex-shader   {(g/gl-position)         (g/* u-sky-box-p-matrix (g/* u-sky-box-mv-matrix (g/vec4 a-sky-box-position 1)))
                      v-sky-box-texture-coord a-sky-box-texture-coord}
    :fragment-shader {(g/gl-frag-color) (g/textureCube u-sky-box-sampler v-sky-box-texture-coord)}
    :precision       {:float :mediump}}))

(defn app-state [width height gl node now]
  {:last-rendered 0
   :mouse         {:pos         [0 0]
                   :sensitivity 0.01}
   :scene         {:rotation 0
                   :mv       (mat/matrix44)
                   :p        (utils/get-perspective-matrix 45 width height)
                   :sky-box  {}
                   :camera   {:pitch -0.039203673205104164, :yaw -5.3000000000000105, :x 3.7236995248610296, :y 1, :z -1.7775460356400952}}
   :webgl         {:canvas {:node node
                            :gl   gl}}})

(def sky-box-vertices
  {:data       [-1  1  1 
                1  1  1 
                1 -1  1 
                -1 -1  1 
                -1  1 -1 
                1  1 -1 
                1 -1 -1 
                -1 -1 -1]
   :id         :sky-box-vertices
   :immutable? true})

(def sky-box-indices
  {:data       [3,2,0,0,2,1,2,6,1,1,6,5,0,1,4,4,1,5,5,6,4,6,7,4,4,7,0,7,3,0,6,2,7,2,3,7]
   :id         :sky-box-indices
   :immutable? true})

(defn draw-sky-box [driver program state p sky-box pitch yaw]
  (let [gl   (gd/gl driver)
        data (-> (select-keys {u-sky-box-p-matrix      p
                               u-sky-box-sampler       sky-box
                               a-sky-box-position      sky-box-vertices
                               a-sky-box-texture-coord sky-box-vertices
                               u-sky-box-mv-matrix     {:data (-> M44
                                                                  (geom/rotate-x pitch)
                                                                  (geom/rotate-y yaw))}}
                              (:inputs program))
                 (assoc {:tag :element-index} sky-box-indices))]
    (gd/draw-elements driver (gd/bind driver program data)
                      {:draw-mode :triangles
                       :count     36})))

(defn draw-fn [driver state]
  (let [gl       (:gl driver)
        now      (/ (:now state) 1000)
        rot      (* js/Math.PI (js/Math.sin now))
        {:keys
         [p mv]} (:scene state)
        mouse-x  (get-in state [:mouse 0] 0)
        mouse-y  (get-in state [:mouse 1] 0)
        camera   (get-in state [:scene :camera])]
    ;; First draw your skybox
    (draw-sky-box driver (get-in state [:runtime :programs :sky-box])
                  state p (get-in state [:skybox :texture])
                  (- (:pitch camera)) (- (:yaw camera)))
    ;; Clear the depth buffer so that other things can be drawn on top
    ;; of our sky.
    (.clear gl (.-DEPTH_BUFFER_BIT gl))
    ;; Now draw the rest of the scene
    ))

(defn animate-pure [draw-fn step-fn current-value]
  (js/requestAnimationFrame
   (fn [time]
     (let [next-value (step-fn time current-value)]
       (draw-fn next-value)
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
  (let [time-now (.getTime (js/Date.))
        elapsed  (- time-now (:last-rendered state))
        pos      (when-let [sensor (get-in state [:hmds :pos])]
                   (.call (aget sensor "getState") sensor))
        camera   (get-in state [:scene :camera])]
    (-> state
        (assoc-in [:last-rendered] time-now)
        (assoc :now time-now))))

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

(defn main [global-app-state node]
  (let [stop      (async/chan)
        controls  (async/chan)
        keyboard  (async/chan)
        gl        (.getContext node "webgl" #js {:antialias true})
        width     (.-clientWidth node)
        height    (.-clientHeight node)
        driver    (utils/make-driver gl)
        programs  {:sky-box (gd/program driver program-sky-box)}
        now       (.getTime (js/Date.))
        watch-key (gensym)]
    (set! (.-glHandle js/window) gl)
    (utils/reset-gl-canvas! node)
    (doto gl
      (.enable (.-DEPTH_TEST gl))
      (.clearColor 0 0 0 1)
      (.clear (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl))))
    (go
      (let [fix-webgl-ch (<! (async/timeout 500))
            texture-ch   (chan)
            scene-ch     (chan)
            app-state    (-> (app-state width height gl node now)
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
            skybox       (<! texture-ch)
            app-state    (-> app-state
                             (assoc-in [:skybox :texture] (assoc skybox
                                                                 :immutable? true
                                                                 :texture-id 1)))
            _            (swap! global-app-state merge app-state)
            app-state    global-app-state
            next-tick    (fn []
                           (main* app-state {:comms {:stop     stop
                                                     :controls controls
                                                     :keyboard keyboard}}))]
        ;; Give the inspector 100ms to load if it's there, then try to fix it.
        (<! (async/timeout 100))
        (utils/fix-webgl-inspector-quirks true true 250)
        (when-let [capture (and (:capture-first-frame? initial-query-map)
                                (.-captureNextFrame js/window))]
          (.call capture))
        (if (:tick-first-frame? initial-query-map)
          (set! (.-tick js/window) next-tick)
          (next-tick))))))

(def explanation
  nil)

(def summary
  {:debug-keys [[:scene :camera]]
   :title       title
   :enter       main
   :explanation explanation})
