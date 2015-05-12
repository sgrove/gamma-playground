(ns gampg.core
  (:require [clojure.string :as s]
            [gamma.api :as g]
            [gamma.program :as p]
            [gamma.tools :as gt]
            [goog.webgl :as ggl]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

(enable-console-print!)

(def vertex-position (g/attribute "aVertexPosition" :vec3))
(def u-p-matrix (g/uniform "uPMatrix" :mat4))
(def u-mv-matrix (g/uniform "uMVMatrix" :mat4))
(def vertex-color    (g/attribute "a_VertexColor" :vec4))
(def vtx-time        (g/uniform "u_Time" :vec2))
(def v-color         (g/varying "vColor" :vec4 :mediump))

(comment
  (let [rotation    (g/vec4 (g/+ (g/* (g/swizzle vertex-position :x) (g/swizzle vtx-time :y))
                                 (g/* (g/swizzle vertex-position :y) (g/swizzle vtx-time :x)))
                            (g/- (g/* (g/swizzle vertex-position :y) (g/swizzle vtx-time :y))
                                 (g/* (g/swizzle vertex-position :x) (g/swizzle vtx-time :x)))
                            0 0)
        translation (g/vec4 (g/swizzle vertex-position :x) (g/sin (g/swizzle vtx-time :x)) 0)]
    (g/+ rotation translation)))
;;(g/+ (g/vec4 (g/swizzle vtx-time :x) (g/swizzle vtx-time :y) 0 0))
;;(g/+ (g/vec4 (g/swizzle vtx-time :x) 0 0 0))

(def vertex-shader
  {(g/gl-position) (-> (g/* u-p-matrix u-mv-matrix)
                       (g/* (g/vec4 vertex-position 1)))
   v-color         (g/vec4 (g/swizzle vtx-time :x) (g/swizzle vtx-time :y) 1 1)})

(def fragment-shader {(g/gl-frag-color) v-color})

(def gl-lesson-01
  (p/program
   {:vertex-shader   vertex-shader
    :fragment-shader fragment-shader}))

(defonce app-state (atom {:text "Gamma, Figwheel, and some elbow grease"
                          :reverse? false
                          :gl {:p gl-lesson-01}
                          :live {}}))

(defn set-matrix-uniforms! [gl pgm p mv]
  (let [p-loc  (.getUniformLocation gl pgm "uPMatrix")
        mv-loc (.getUniformLocation gl pgm "uMVMatrix")]
    (doto gl
      (.uniformMatrix4fv p-loc false p)
      (.uniformMatrix4fv mv-loc false mv))))

(defn setup-gl! [gl node]
  (set! (.-myGL js/window) gl)
  (let [w (.-clientWidth node)
        h (.-clientHeight node)]
    (set! (.-width node) w)
    (set! (.-height node) h)
    (set! (.-viewportWidth gl)  (.-width node))
    (set! (.-viewportHeight gl) (.-height node))
    (doto gl
      (.clearColor 0 0 0 1)
      (.enable (.-DEPTH_TEST gl))
      (.viewport gl 0 0 w h)
      (.clear (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl))))))

(defn init-buffers! [gl]
  (let [triangle-vertex-position-buffer (.createBuffer gl)
        triangle-vertices #js[ 0.0,  1.0,  0.0,
                              -1.0, -1.0,  0.0,
                              1.0, -1.0,  0.0]
        triangle-item-size 3
        triangle-num-items 3
        square-vertex-position-buffer (.createBuffer gl)
        square-vertices #js[ 1.0,  1.0,  0.0,
                            -1.0,  1.0,  0.0,
                            1.0, -1.0,  0.0,
                            -1.0, -1.0,  0.0]
        square-item-size 3
        square-num-items 4]
    (js/console.log "Whoops!!!!!!")
    (doto gl
      (.bindBuffer ggl/ARRAY_BUFFER triangle-vertex-position-buffer)
      (.bufferData ggl/ARRAY_BUFFER (js/Float32Array. triangle-vertices) ggl/STATIC_DRAW)
      (.bindBuffer ggl/ARRAY_BUFFER square-vertex-position-buffer)
      (.bufferData ggl/ARRAY_BUFFER (js/Float32Array. square-vertices) ggl/STATIC_DRAW))
    (js/console.log "Square vertices: " (js/Float32Array. square-vertices))
    {:triangle {:buff  triangle-vertex-position-buffer
                :size  3
                :count 3}
     :square    {:buff  square-vertex-position-buffer
                 :size  3
                 :count 4}}))

(defn install-scene! [gl state]
  (let [main {:vs  (.createShader gl ggl/VERTEX_SHADER)
              :fs  (.createShader gl ggl/FRAGMENT_SHADER)
              :pgm (.createProgram gl)
              :xs  (js/Float32Array. #js [-1 -1 0 -1 1 1])
              :buf (.createBuffer gl)}]
    (let [{:keys [vs fs pgm xs buf]} main]
      (println "->" (:name vtx-time))
      (js/console.log "pgm->" pgm)
      (doto gl
        (.shaderSource (:vs main) (-> gl-lesson-01 :vertex-shader :glsl))
        (js/console.log "1")
        (.compileShader (:vs main))
        (js/console.log "2")
        (.shaderSource (:fs main) (-> gl-lesson-01 :fragment-shader :glsl))
        (js/console.log "3")
        (.compileShader (:fs main))
        (js/console.log "4")
        (.attachShader pgm vs)
        (js/console.log "5")
        (.attachShader pgm fs)
        (js/console.log "6")
        (.linkProgram pgm)
        )
      (let [linked? (.getProgramParameter gl pgm (.-LINK_STATUS gl))]
        (println "Linked? " linked?)
        (println "ProgramInfoLog: "(.getProgramInfoLog gl pgm)))
      
      (doto gl
        (js/console.log "7")
        (.bindBuffer ggl/ARRAY_BUFFER buf)
        (js/console.log "8")
        (.bufferData ggl/ARRAY_BUFFER xs ggl/STATIC_DRAW)
        (js/console.log "9")
        (.enableVertexAttribArray (.getAttribLocation gl pgm (:name vertex-position)))
        (js/console.log "10")
        (.vertexAttribPointer (.getAttribLocation gl pgm (:name vertex-position))
                              2 ggl/FLOAT false 0 0))
      (js/console.log "11")
      (swap! app-state assoc-in [:live :compiled :main] main))))

(defn install-alt-scene! [gl state]
    (let  [main {:vs  (.createShader gl ggl/VERTEX_SHADER)
                 :fs  (.createShader gl ggl/FRAGMENT_SHADER)
                 :pgm (.createProgram gl)
                 :buf (.createBuffer gl)}
           {:keys [pgm vs fs]} main]
      (doto gl
        (.shaderSource (:vs main) (-> gl-lesson-01 :vertex-shader :glsl))
        (.compileShader (:vs main))
        (.shaderSource (:fs main) (-> gl-lesson-01 :fragment-shader :glsl))
        (.compileShader (:fs main))
        (.attachShader pgm vs)
        (.attachShader pgm fs)
        (.linkProgram pgm))
      (let [linked? (.getProgramParameter gl pgm (.-LINK_STATUS gl))]
        (println "Linked? " linked?)
        (println "ProgramInfoLog: "(.getProgramInfoLog gl pgm)))
      (js/console.log (.getUniformLocation gl pgm (:name u-p-matrix)))
      (js/console.log (.getUniformLocation gl pgm (:name u-mv-matrix)))
      (swap! app-state
             (fn [state]
               (-> state
                   (assoc-in [:live :compiled :main] main)
                   (assoc-in [:live :data] (init-buffers! gl)))))))

(defn draw-scene! [gl gamma-pgm]
  (let [vpw (.-viewportWidth gl)
        vph (.-viewportHeight gl)
        p   (.create js/mat4)
        mv  (.create js/mat4)
        triangle-pos-buff (get-in @app-state [:live :data :triangle :buff])
        square-pos-buff (get-in @app-state [:live :data :square :buff])
        factor 1000
        now (.getTime (js/Date.))
        x      (js/Math.sin (/ now factor))
        y      (js/Math.cos (/ now factor))
        deg->rad (fn [n] (* n (/ js/Math.PI 180)))]
    (.useProgram gl (:pgm gamma-pgm))

    (.perspective js/mat4 p 45 (/ vpw vph) 0.1 1000)
    (.identity js/mat4 mv)
    (.translate js/mat4 mv mv #js[3 0 -7])
    (.rotateZ js/mat4 mv mv (deg->rad (/ now 10)))
    (set-matrix-uniforms! gl (:pgm gamma-pgm) p mv)
    (doto gl
      (.viewport 0 0 vpw vph)
      (.clear (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
      (.enableVertexAttribArray (.getAttribLocation gl (:pgm gamma-pgm) (:name vertex-position)) 3 ggl/FLOAT false 0 0)
      (.bindBuffer ggl/ARRAY_BUFFER triangle-pos-buff)
      (.vertexAttribPointer (.getAttribLocation gl (:pgm gamma-pgm) (:name vertex-position)) 3 ggl/FLOAT false 0 0)
      (.uniform2f (.getUniformLocation gl (:pgm gamma-pgm) (:name vtx-time)) x y)
      (.drawArrays (.-TRIANGLES gl) 0 3))
    (.identity js/mat4 mv)
    (.translate js/mat4 mv mv #js[(* 4 (js/Math.cos (/ now 1000)))
                                  (* 4 (js/Math.sin (/ now 1000)))
                                  (- (js/Math.abs (* 7 (js/Math.sin (/ now 1000)))))])
    (set-matrix-uniforms! gl (:pgm gamma-pgm) p mv)
    (doto gl
      (.bindBuffer ggl/ARRAY_BUFFER square-pos-buff)
      (.vertexAttribPointer (.getAttribLocation gl (:pgm gamma-pgm) (:name vertex-position)) 3 ggl/FLOAT false 0 0)
      (.drawArrays (.-TRIANGLE_STRIP gl) 0 4))))

(defn start-render-loop! [gl state]
  (let [pgm            (get-in state [:live :compiled :main])
        fps            60
        n              (rand-int 100)
        render-loop-id (js/window.setInterval
                        (fn []
                          (let [factor 1000
                                now    (.getTime (js/Date.))
                                x      (js/Math.sin (/ now factor))]
                            (draw-scene! gl pgm)))
                        (/ 1000 fps))]
    (println "render loop id: " render-loop-id)
    (swap! app-state assoc-in [:live :render-loop-id] render-loop-id)
    render-loop-id))

(defn canvas [data owner opts]
  (reify
    om/IDidMount
    (did-mount [_]
      (let [cb   (:cb opts)
            node (om/get-node owner)
            gl   (.getContext node "webgl")]
        (when gl
          (setup-gl! gl node)
          (cb gl))))
    om/IRender
    (render [_]
      (dom/canvas #js{:style #js{:width "100%"
                                 :border (str "2px solid " (rand-nth ["black" "blue" "green" "pink"]))}}))))

(defn main []
  (om/root
   (fn [app owner]
     (reify
       om/IRender
       (render [_]
         (dom/div nil
                  (dom/h2 #js{:onClick (fn [event] (om/transact! app :reverse? not))}
                          (if (:reverse? app)
                            (s/reverse (:text app))
                            (:text app)))
                  (dom/small nil
                             (dom/pre #js{:style #js{:float "left"
                                                     :borderRight "1px dotted black"
                                                     :width "45%"
                                                     :overflow "hidden"}}
                                      "Vertex Shader:\n--------------\n\n"
                                      (-> gl-lesson-01
                                          :vertex-shader :glsl)))
                  (dom/small nil
                             (dom/pre #js{:style #js{:width "50%"
                                                     :float "left"
                                                     
                                                     :marginLeft 4
                                                     :paddingLeft 4}}
                                      "Fragment Shader:\n----------------\n"
                                      (-> gl-lesson-01
                                          :fragment-shader :glsl)))
                  (om/build canvas (:text app) {:opts {:cb (fn [gl node]
                                                    (when-let [id (get-in @app-state [:live :render-loop-id])]
                                                      (js/window.clearInterval id))
                                                    (swap! app-state update-in [:live] assoc
                                                           :gl gl
                                                           :node node)
                                                    (install-alt-scene! gl @app-state)
                                                    (let [render-loop-id (start-render-loop! gl @app-state)]
                                                      (om/transact! app [:live] (fn [live]
                                                                                  (-> live
                                                                                      (assoc :render-loop-id render-loop-id))))))}})))))
   app-state
   {:target (. js/document (getElementById "app"))}))
