(ns gampg.learn-gamma.lesson-01
  (:require [cljs.repl :as repl]
            [fipp.edn :as fipp]
            [gamma.api :as g]
            [gamma.program :as p]
            [gamma-driver.api :as gd]
            [gamma-driver.drivers.basic :as driver]
            [thi.ng.geom.core :as geom]
            [thi.ng.geom.core.matrix :as mat :refer [M44]]))

(def title
  "1. A triangle and a square")

(def u-p-matrix
  (g/uniform "uPMatrix" :mat4))

(def u-mv-matrix
  (g/uniform "uMVMatrix" :mat4))

(def a-position
  (g/attribute "aVertexPosition" :vec3))

(def program-source
  (p/program
   {:vertex-shader   {(g/gl-position)   (-> u-p-matrix
                                            (g/* u-mv-matrix)
                                            (g/* (g/vec4 a-position 1)))}
    :fragment-shader {(g/gl-frag-color) (g/vec4 1 0 0 1)}}))

(defn get-perspective-matrix
  "Be sure to 
   1. pass the WIDTH and HEIGHT of the canvas *node*, not
      the GL context
   2. (set! (.-width/height canvas-node)
      width/height), respectively, or you may see no results, or strange
      results"
  [width height]
  (mat/perspective 45 (/ width height) 0.1 100))

(defn get-data [p mv vertices]
  {u-p-matrix  p
   u-mv-matrix mv
   a-position  vertices})

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

(def triangle-vertices
  {:id         :triangle-vertices
   :data       [[ 0  1  0]
                [-1 -1  0]
                [ 1 -1  0]]
   :immutable? true})

(def square-vertices
  {:id         :square-vertices
   :data       [[ 1  1  0]
                [-1  1  0]
                [ 1 -1  0]
                [-1 -1  0]]
   :immutable? true})

(defn main [_ node]
  (let [gl      (.getContext node "webgl")
        w       (.-clientWidth node)
        h       (.-clientHeight node)
        driver  (driver/basic-driver gl)
        program (gd/program driver program-source)
        p       (get-perspective-matrix w h)
        mv      (mat/matrix44)]
    (reset-gl-canvas! node)
    (.clearColor gl 0 0 0 1)
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (let [mv (geom/translate mv [-3 0 -7])]
      (gd/draw-arrays driver (gd/bind driver program (get-data p mv triangle-vertices)) {:draw-mode :triangles}))
    (let [mv (geom/translate mv [3 0 -7])]
      (gd/draw-arrays driver (gd/bind driver program (get-data p mv square-vertices)) {:draw-mode :triangle-strip}))))

(def explanation
  (str
   "
# WIP

We're going to draw a simple triangle and a square to illustrate how
to get simple shapes on the screen.

> __Be aware that there are a lot of pitfalls in WebGL programming,__
  __and you're likely to see an empty black screen _a lot_. Be ready to__
  __persevere__

## Dive in head first
Let's dive right into the lesson's `main` fn:
```clojure " (with-out-str (repl/source main)) "
 ```

We're ignoring the first argument (we'll make use of it in later
lessons), but the second is more interesting: it's the canvas node
we'll be drawing to. We pull the WebGL context out of it, and create a
`Gamma Driver`, which is going to do most of the heavy lifting for us.


We then
 1. Compile our shader (more on that further down) via `(gd/program driver program-source)`
 1. Create a perspective matrix
 1. A model-view matrix
 1. Reset the canvas - typically you'd do this once when first starting, and then one on a canvas resize.
 1. Clear the canvas so it's ready for us to draw
 1. Draw the triangle by:
   2. Translating the (_immutable_) mv-matrix slightly to the left and further back in the screen
   2. Binding *all* the data needed to draw the triangle: In this case, the vertices, the perspective-matrix, and the mv-matrix
 1. Draw the square (using the same steps as for the triangle)

## What's that `triangle-vertices` thing?

 When drawing things with WebGL, you have just a few primitives - in this lesson, we'll look at
 buffers. Buffers are nothing more than an allocated region of memory
 that lives in the GPU. You store simple bytes in these buffers, and
 it's up to you to provide \"meaning\" to them. For example, let's say
 we want to draw a triangle - a triangle is made up of three points,
 and each point is a vector of three numbers, e.g. `[x, y, z]`
 representing the position. To represent a single triangle with its
 center at the origin of the canvas, we might do the following:

```clojure " (with-out-str (fipp/pprint (vec (flatten (get-in triangle-vertices [:data]))))) " 
``` Now, usually getting this to the 
 GPU is a incredibly (really impressively so) stateful pain in the
 neck. However, `GD` (Gamma Driver) will handle most of the dirty work
 for us. To help `GD` be efficient about things (and because we know
 the points of this triangle will never change - we'll use the
 transformation matrix to move it around), we'll tag the data with two
 more pieces of information: its `:id` and `:immutable? true`. Here's
 what our final data for the vertices looks like:

```clojure " (with-out-str (repl/source triangle-vertices)) "
```
Now we'll do the same for a square:
 ```clojure " (with-out-str (repl/source square-vertices)) "
```

## Blablabla, some `Matrix` joke
 We just need two more bits of data: The perspective matrix and the 
 model-view matrix. We'll outsource the generation of each to the 
 [thi.ng.geom.core.matrix](https://github.com/thi-ng/geom/blob/master/geom-core/src/matrix.org)
 library. This bit of data *might* change (it depends on the field of
 view, and the width/height of the current canvas, which could change
 if the window is resized), so we won't bother marking it with `:id`
 or `:immutable? true`. Here's our function to generate one:
 ```clojure " (with-out-str (repl/source get-perspective-matrix)) "
 ```

For now, our `mv` (model-view matrix) will just be the identity
matrix: `(mat/matrix44)`, but we'll use the `(geom/translate ...`)
function to move our individual shapes around.

## Explain Shaders
## Explain `draw-arrays`
"))

(def summary
  {:title       title
   :enter       main
   :explanation explanation})
