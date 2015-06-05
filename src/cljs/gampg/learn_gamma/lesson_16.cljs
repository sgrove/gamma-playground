(ns ^:figwheel-always gampg.learn-gamma.lesson-16
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
                   :p               (utils/get-perspective-matrix width height)
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

(defn draw-fn [gl driver programs]
  (fn [state]
    (let [{:keys [p mv
                  color-texture specular-texture
                  model
                  rotation]}      (:scene state)
                  now                     (/ (.getTime (js/Date.)) 50)
                  rotation                (- (utils/deg->rad now))
                  program                 (get programs :simple)
                  point-lighting-location {:data       #js[-10 4 0]
                                           :immutable? false}
                  square-mv               (-> mv
                                              ;;(geom/translate [(* rotation 4) -0.5 -5])
                                              (geom/translate [(* (js/Math.sin (/ now 8)) 4) (* (js/Math.cos (/ now 3)) 2) -5])
                                              (geom/* (-> (mat/matrix44)
                                                          (geom/rotate-around-axis [0 1 0] rotation)
                                                          (geom/rotate-around-axis [1 0 0] (- (/ js/Math.PI 3)))))
                                              object-array)
                  model-mv                (-> mv
                                              (geom/translate [0 -0.5 -2.5])
                                              (geom/* (-> M44
                                                          (geom/rotate-around-axis [0 1 0] rotation)
                                                          (geom/rotate-around-axis [1 0 0] (- (/ js/Math.PI 2))))))
                  scene-data              (-> (get-data p model-mv (:vertices model) (:normals model) color-texture (:texture-coords model) point-lighting-location #js[0.2 0.2 0.2]  #js[0.2 0.2 0.2]  #js[0.2 0.2 0.2])
                                              (select-keys (get-in programs [:specular :inputs]))
                                              (assoc {:tag :element-index} (:indices model)))
                  screen-data             (-> (get-data p model-mv (:vertices laptop-screen) (:normals laptop-screen) (get-in state [:framebuffer :color])
                                                        (:texture-coords laptop-screen) point-lighting-location #js[0.2 0.2 0.2]  #js[0.2 0.2 0.2]  #js[0.2 0.2 0.2])
                                              (select-keys (get-in programs [:specular :inputs]))
                                              (assoc {:tag :element-index} (:indices model)))
                  screen-texture-data     {progs/u-p-matrix  p
                                           progs/u-mv-matrix square-mv
                                           progs/a-position  (get-in state [:scene :square-vertices])
                                           progs/a-color     (get-in state [:scene :square-colors])}]
      (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
      ;; First draw to the framebuffer
      (.viewport gl 0 0 512 512)
      (.bindFramebuffer gl ggl/FRAMEBUFFER (get-in state [:framebuffer :frame-buffer]))
      (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
      (skybox/draw-skybox driver (get-in programs [:skybox]) p (-> M44
                                                                   (geom/rotate-around-axis [1 0 0] (js/Math.cos (/ now 10)))
                                                                   (geom/rotate-around-axis [0 1 0] (js/Math.sin (/ now 100)))
                                                                   ) (get-in state [:skybox :texture]) (:framebuffer state))
      (.clear gl (.-DEPTH_BUFFER_BIT gl))
      (gd/bind driver (get programs :simple) screen-texture-data)
      (gd/draw-arrays driver (get programs :simple) {:draw-mode :triangle-strip
                                                     :count     4} (:framebuffer state))
      #_(gd/bind driver (get programs :specular) (update-in scene-data [progs/u-mv-matrix]
                                                          #(geom/rotate-around-axis % [0 1 0] rotation)))
      #_(gd/draw-elements driver (get programs :specular)
                        {:draw-mode :triangles
                         :first     0
                         :count     (get-in model [:indices :count])} (:framebuffer state))
      
      (.viewport gl 0 0 (get-in state [:canvas :width]) (get-in state [:canvas :height]))
      (.bindTexture gl ggl/TEXTURE_2D (get-in state [:framebuffer :color :texture]))
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

(defn main [global-app-state node]
  (let [gl            (.getContext node "webgl")
        gl-extensions [(.getExtension gl "WEBGL_depth_texture")]
        width         (.-clientWidth node)
        height        (.-clientHeight node)
        driver        (utils/make-driver gl)
        programs      {:specular (gd/program driver progs/program-specular)
                       :simple   (gd/program driver lesson-02/program-source)
                       :skybox   (gd/program driver skybox/program-skybox)}
        ;; WxH must be a power of two (e.g. 64, 128, 256, 512, 1024, etc.)
        fb-width      512
        fb-height     512
        fb            (utils/make-frame-buffer driver fb-width fb-height)
        local-state   (-> (app-state width height)
                          (assoc :framebuffer fb))
        _             (swap! global-app-state merge local-state)
        state         global-app-state]
    (utils/reset-gl-canvas! node)
    (.enable gl (.-DEPTH_TEST gl))
    (.clearColor gl 0 0 0 1)
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
            skybox-ch   (chan)
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
                                  :flip-y     true
                                  :immutable? true
                                  :id         :laptop-texture}
            _                    (swap! state (fn [state]
                                                (-> state
                                                    (assoc-in [:scene :color-texture] color-texture)
                                                    (assoc-in [:scene :model] model)
                                                    (assoc-in [:skybox :texture] (assoc skybox
                                                                                        :immutable? true
                                                                                        :texture-id 3))
                                                    (assoc-in [:webgl :extensions] gl-extensions))))
            next-tick            (fn [] (animate (draw-fn gl driver programs) tick state))]
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
  {:title       title
   :enter       main
   :explanation explanation})
