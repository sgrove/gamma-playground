(ns gampg.learn-gamma.programs
  (:require [gamma.api :as g]
            [gamma.program :as p]
            [gamma-driver.api :as gd]
            [thi.ng.geom.core :as geom]
            [thi.ng.geom.core.matrix :as mat :refer [M44]]
            [thi.ng.geom.core.vector :as v :refer [vec2 vec3]]))

(def u-p-matrix
  (g/uniform "uPMatrix" :mat4))

(def u-mv-matrix
  (g/uniform "uMVMatrix" :mat4))

(def a-position
  (g/attribute "aVertexPosition" :vec3))

(def a-color
  (g/attribute "aVertexColor" :vec4))

(def v-color
  (g/varying "vColor" :vec4 :mediump))

(def u-n-matrix
  (g/uniform "uNMatrix" :mat3))

(def u-point-lighting-location
  (g/uniform "uPointLightingLocation" :vec3))

(def u-point-lighting-color
  (g/uniform "uPointLightingColor" :vec3))

(def a-vertex-normal
  (g/attribute "aVertexNormal" :vec3))

(def a-texture-coord
  (g/attribute "aTextureCoord" :vec2))

(def v-texture-coord
  (g/varying "vTextureCoord" :vec2 :mediump))

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

(def u-material-emissive-color
  (g/uniform "uMaterialEmissiveColor" :vec3))

(def u-ambient-lighting-color
  (g/uniform "uAmbientLightingColor" :vec3))

(def u-material-ambient-color
  (g/uniform "uMaterialAmbientColor" :vec3))

(def u-material-diffuse-color
  (g/uniform "uMaterialDiffuseColor" :vec3))

(def u-material-specular-color
  (g/uniform "uMaterialDiffuseColor" :vec3))

(def u-sampler
  (g/uniform "uSampler" :sampler2D))

(def u-color
  (g/uniform "uColor" :vec4))

(def uniform-color
  (p/program
   {:id              :common-uniform-color
    :vertex-shader   {(g/gl-position) (-> u-p-matrix
                                          (g/* u-mv-matrix)
                                          (g/* (g/vec4 a-position 1)))}
    :fragment-shader {(g/gl-frag-color) u-color}
    :precision       {:float :mediump}}))

(defn draw-black [driver draw program p mv vertices opts]
  (draw driver (gd/bind driver program {u-p-matrix  p
                                        u-mv-matrix mv
                                        a-position  vertices
                                        u-color      #js[0 0 0 1]}) opts))

(defn draw-white [driver draw program p mv vertices opts]
  (draw driver (gd/bind driver program {u-p-matrix  p
                                        u-mv-matrix mv
                                        a-position  vertices
                                        u-color      #js[1 1 1 1]}) opts))

(def flat-color
  (p/program
   {:id              :common-flat-color
    :vertex-shader   {(g/gl-position) (-> u-p-matrix
                                          (g/* u-mv-matrix)
                                          (g/* (g/vec4 a-position 1)))
                      v-color         a-color}
    :fragment-shader {(g/gl-frag-color) v-color}}))

(def simple-color
  (p/program
   {:id              :common-simple-color
    :vertex-shader   {(g/gl-position) (-> u-p-matrix
                                          (g/* u-mv-matrix)
                                          (g/* (g/vec4 a-position 1)))
                      v-color         a-color}
    :fragment-shader {(g/gl-frag-color) v-color}}))

(def simple-texture
  (p/program
   {:vertex-shader   {(g/gl-position) (-> u-p-matrix
                                          (g/* u-mv-matrix)
                                          (g/* (g/vec4 a-position 1)))
                      v-texture-coord a-texture-coord}
    :fragment-shader {(g/gl-frag-color)
                      (g/texture2D u-sampler (g/vec2 (g/swizzle v-texture-coord :st)))}}))

(defn draw-color [driver draw program p mv vertices colors opts]
  (draw driver (gd/bind driver program {u-p-matrix  p
                                        u-mv-matrix mv
                                        a-position  vertices
                                        a-color colors}) opts))

(def program-specular
  (p/program
   {:id              :specular
    :vertex-shader   {v-texture-coord      a-texture-coord
                      v-transformed-normal (g/* u-n-matrix a-vertex-normal)
                      v-position           (g/* u-mv-matrix (g/vec4 a-position 1))
                      (g/gl-position)      (g/* u-p-matrix v-position)}
    :fragment-shader (let [ambient-light-weighting   u-ambient-lighting-color
                           light-direction           (g/normalize (g/- u-point-lighting-location (g/swizzle v-position :xyz)))
                           normal                    (g/normalize v-transformed-normal)
                           eye-direction             (-> (g/swizzle v-position :xyz)
                                                         (g/* -1)
                                                         (g/normalize))
                           reflection-direction      (-> (g/* -1 light-direction)
                                                         (g/reflect normal))
                           specular-light-brightness (-> (g/dot reflection-direction eye-direction)
                                                         (g/max 0)
                                                         (g/power u-material-shininess))
                           specular-light-weighting  (g/* u-point-lighting-diffuse-color specular-light-brightness)
                           diffuse-light-brightness  (-> (g/dot normal light-direction)
                                                         (g/max 0))
                           diffuse-light-weighting   (g/* u-point-lighting-diffuse-color diffuse-light-brightness)
                           texture-color             (g/texture2D u-sampler (g/swizzle v-texture-coord :st))
                           texture-rgb               (g/swizzle texture-color :rgb)
                           alpha                     (g/swizzle texture-color :a)
                           material-ambient-color    (g/* u-material-ambient-color texture-rgb)
                           material-diffuse-color    (g/* u-material-diffuse-color texture-rgb)
                           material-specular-color   (g/* u-material-specular-color texture-rgb)
                           material-emissive-color   (g/* u-material-emissive-color texture-rgb)
                           sum-color                 (-> (g/* material-ambient-color ambient-light-weighting)
                                                         (g/+ (g/* material-diffuse-color diffuse-light-weighting))
                                                         (g/+ (g/* material-specular-color specular-light-weighting))
                                                         (g/+ material-emissive-color)
                                                         (g/vec4 alpha))]
                       {(g/gl-frag-color) sum-color})
    :precision       {:float :mediump}}))

(def u-spot-position
  (g/uniform "uLightPosition" :vec3))

(def u-shadow-map
  (g/uniform "uShadowMapSampler" :sampler2D))

(def depth-scale-matrix
  (g/mat4 0.5 0.0 0.0 0.0 0.0 0.5 0.0 0.0 0.0 0.0 0.5 0.0 0.5 0.5 0.5 1.0))

(def u-spot-color
  (g/uniform "uLightSpotColor" :vec3))

(def u-spot-direction
  (g/uniform "uLightSpotDirection" :vec3))

(def u-spot-inner-angle
  (g/uniform "uLightSpotInnerAngle" :float))

(def u-spot-outer-angle
  (g/uniform "uLightSpotOuterAngle" :float))

(def u-spot-radius
  (g/uniform "uLightSpotRadius" :float))

(def v-light-to-point
  (g/varying "vLightToPoint" :vec3 :mediump))

(def v-eye-to-point
  (g/varying "vEyeToPoint" :vec3 :mediump))

(def v-shadow-position
  (g/varying "vShadowPosition" :vec4 :mediump))

(def v-normal
  (g/varying "vNormal" :vec3 :mediump))

(defn compute-light [normal specular-level light-to-point eye-to-point
                     spot-color spot-radius spot-direction spot-inner-angle spot-outer-angle]
  (let [;; Lambert term
        l                (g/normalize light-to-point)
        n                (g/normalize normal)
        lambert-term     (-> (g/dot n l)
                             (g/max 0))

        ;;  Light attenuation
        light-dist       (g/length light-to-point)
        d                (-> (g/div light-dist spot-radius)
                             (g/max 0)
                             (g/div (g/+ spot-radius 1)))
        dist-attn        (g/div 1 (g/* d d))

        ;; Spot attenuation
        sd               (g/normalize spot-direction)
        spot-angle-delta (g/- spot-inner-angle spot-outer-angle)
        spot-angle       (g/dot (g/* l -1) sd)
        spot-attn        (-> (g/- spot-angle spot-outer-angle)
                             (g/div spot-angle-delta)
                             (g/clamp 0 1))
        light-value      (-> spot-color
                             (g/* lambert-term)
                             (g/* dist-attn)
                             (g/* spot-attn))

        ;; ;; Specular
        e                (g/normalize eye-to-point)
        r                (g/reflect (g/* l -1) n)
        shininess        8 ;; Why 8?
        specular-factor  (-> (g/dot r e)
                             (g/clamp 0 1)
                             (g/power shininess)
                             (g/* specular-level))
        light-value      (g/if (g/< lambert-term 0)
                           (g/vec3 0 0 0)
                           (g/+ light-value (g/* spot-color specular-factor)))]
    light-value))

(defn compute-shadow [shadow-position shadow-map]
  (let [depth         (g/div (g/swizzle shadow-position :xyz)
                             (g/swizzle shadow-position :w))
        depth-z       (g/* (g/swizzle depth :z)
                           0.999)
        shadow-coords (g/swizzle depth :xy)
        shadow-value  (-> shadow-map
                          (g/texture2D shadow-coords)
                          (g/swizzle :r))]
    (g/if (g/< shadow-value depth-z)
      0
      1)))

(def u-light-projection-matrix
  (g/uniform "uLightProjectionMatrix" :mat4))

(def u-light-view-matrix
  (g/uniform "uLightViewMatrix" :mat4))

(def a-normal
  (g/attribute "aNormal" :vec3))

(comment
  (def program-specular-with-shadow-map
    (let [world-position (g/* u-mv-matrix (g/vec4 a-position 1))]
      (p/program
        {:id              :specular-with-shadow-map
         :vertex-shader   {v-texture-coord   a-texture-coord
                           ;; Looks like probably don't need this
                           ;;v-transformed-normal (g/* u-n-matrix a-vertex-normal)
                           ;;v-transformed-normal (g/* a-vertex-normal u-n-matrix)
                           v-position        (g/* u-mv-matrix (g/vec4 a-position 1))
                           v-light-to-point  (g/- u-spot-position (g/swizzle world-position :xyz))
                           v-eye-to-point    (g/* -1 (g/swizzle world-position :xyz))
                           v-shadow-position (-> depth-scale-matrix
                                                 (g/* u-light-projection-matrix)
                                                 (g/* u-light-view-matrix)
                                                 (g/* world-position))
                           (g/gl-position)   (g/* u-p-matrix v-position)
                           v-normal          (g/* a-normal u-n-matrix)}
         :fragment-shader (let [light-value   (compute-light v-normal 0.5
                                                             v-light-to-point v-eye-to-point
                                                             u-spot-color u-spot-radius u-spot-direction u-spot-inner-angle u-spot-outer-angle)
                                shadow-value  (compute-shadow v-shadow-position u-shadow-map)
                                diffuse-color (g/texture2D u-sampler (g/swizzle v-texture-coord :st))
                                final-color   (-> (g/swizzle diffuse-color :rgb)
                                                  (g/* light-value)
                                                  (g/* shadow-value))]
                            {(g/gl-frag-color) (do (g/vec4 final-color (g/swizzle diffuse-color :a))
                                                   (g/vec4 (g/* (g/swizzle diffuse-color :rgb) shadow-value) 1))})
         :precision       {:float :mediump}}))))

(defn draw-specular-with-shadow-map [driver draw program p mv normal-matrix vertices normals
                                     spot-location spot-direction 
                                     spot-inner-angle spot-outer-angle
                                     spot-radius spot-color
                                     texture-coords
                                     color-texture shadow-map 
                                     indices draw-mode fst draw-count]
  (let [angle                   (* 2 spot-outer-angle (/ 180 js/Math.PI))
        light-projection-matrix (mat/perspective angle 1 1 256)
        light-view-matrix       (mat/look-at spot-location spot-direction (vec3 0 0 1))]
    (when (< (js/Math.random) 0)
      (js/console.log (pr-str (:id vertices)) " vertec count: " (count (:data vertices)))
      (js/console.log (pr-str (:id normals)) " normal count: " (count (:data normals)))
      (js/console.log (pr-str (:id texture-coords)) " texture-coords count: " (count (:data texture-coords)))
      (js/console.log (pr-str (:id indices)) " texture-coords count: " (count (:data indices)) draw-count)
      (js/console.log "Spotlight from " (clj->js spot-location) " looking at " (clj->js spot-direction) " radius: " spot-radius))
    (gd/bind driver program
             (-> {u-p-matrix                p
                  u-mv-matrix               mv
                  u-n-matrix                normal-matrix
                  u-spot-position           spot-location
                  u-shadow-map              shadow-map
                  a-position                vertices
                  a-texture-coord           texture-coords
                  u-sampler                 color-texture
                  u-spot-outer-angle        spot-inner-angle
                  u-spot-inner-angle        spot-outer-angle
                  u-spot-direction          spot-direction
                  u-spot-color              spot-color
                  u-spot-radius             spot-radius
                  a-normal                  normals
                  u-light-view-matrix       light-view-matrix
                  u-light-projection-matrix light-projection-matrix}
                 (select-keys (:inputs program))
                 (assoc {:tag :element-index} indices))))
  (draw driver program {:draw-mode draw-mode
                        :first     fst
                        :count     draw-count}))
