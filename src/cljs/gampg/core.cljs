(ns ^:figwheel-always gampg.core
  (:require [gampg.learn-gamma.lesson-01 :as lg01]
            [gampg.learn-gamma.lesson-02 :as lg02]
            [gampg.learn-gamma.lesson-03 :as lg03]
            [gampg.learn-gamma.lesson-04 :as lg04]
            [gampg.learn-gamma.lesson-05 :as lg05]
            [gampg.learn-gamma.lesson-06 :as lg06]
            [gampg.learn-gamma.lesson-07 :as lg07]
            [gampg.learn-gamma.lesson-08 :as lg08]
            [gampg.learn-gamma.lesson-09 :as lg09]
            [gampg.learn-gamma.lesson-10 :as lg10]
            [gampg.learn-gamma.lesson-11 :as lg11]
            [gampg.learn-gamma.lesson-12 :as lg12]
            [gampg.learn-gamma.lesson-13 :as lg13]
            [gampg.learn-gamma.lesson-14 :as lg14]
            [gampg.learn-gamma.lesson-15 :as lg15]
            ;;[gampg.learn-gamma.lesson-20 :as lg20]
            [gampg.learn-gamma.apartment :as lg-apartment]
            [gampg.learn-gamma.gltf :as lg-gltf]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

(enable-console-print!)

;; TODO: Add a menu for selecting examples
;;
;; XXX: We're not properly reusing gl context's here, hurts fighweel's
;; reloadability
(def title   lg-apartment/title)
(def prog    lg-apartment/program-diffuse-per-fragment)
(def gl-main lg-apartment/main)

;; (def title   lg14/title)
;; (def prog    lg14/program-specular)
;; (def gl-main lg14/main)

(defonce app-state (atom {:live {}}))

(defn canvas [data owner opts]
  (reify
    om/IDidMount
    (did-mount [_]
      (let [cb   (:cb opts)
            node (om/get-node owner)
            gl   (.getContext node "webgl")]
        (when gl
          (cb gl node))))
    om/IRender
    (render [_]
      (dom/canvas #js{:id "gl-canvas"
                      :style #js{:width "100%"
                                 :border (str "2px solid " (rand-nth ["black" "blue" "green" "pink"]))}}))))

(defn main* []
  (let [node (js/document.getElementById "glcanvas")
        gl   (.getContext node "webgl")]
    (om/root
     (fn [app owner]
       (reify
         om/IRender
         (render [_]
           (dom/div nil
                    (dom/h2 #js{:onClick (fn [event] (om/transact! app :reverse? not))}
                            title)
                    (dom/small nil
                               (dom/pre #js{:style #js{:float "left"
                                                       :borderRight "1px dotted black"
                                                       :width "45%"
                                                       :overflow "hidden"}}
                                        "Vertex Shader:\n--------------\n\n"
                                        (get-in prog [:vertex-shader :glsl])))
                    (dom/small nil
                               (dom/pre #js{:style #js{:width "50%"
                                                       :float "left"
                                                       :marginLeft 4
                                                       :paddingLeft 4}}
                                        "Fragment Shader:\n----------------\n"
                                        (get-in prog [:fragment-shader :glsl])))))))
     app-state
     {:target (. js/document (getElementById "app"))})
    (gl-main node)))

;; Temporarily here for debugging

(defn main []
  (let [node (js/document.getElementById "glcanvas")]
    (main*)))

(aset js/window "reinstallApp"
      (fn []
        (main*)))
