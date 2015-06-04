(defproject gampg "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :source-paths ["src/clj"]
  :repl-options {:timeout 200000} ;; Defaults to 30000 (30 seconds)

  :test-paths ["spec/clj"]

  :dependencies [[cljsjs/react "0.13.3-0"]
                 [compojure "1.3.1"]
                 [enlive "1.1.5"]
                 [environ "1.0.0"]
                 [fipp "0.6.2"]
                 [instaparse "1.4.0"]
                 [markdown-clj "0.9.66"]
                 [org.omcljs/om "0.8.8"]
                 [org.clojure/clojure "1.7.0-beta2"]
                 [org.clojure/clojurescript "0.0-3291"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/google-closure-library "0.0-20150505-021ed5b3"]
                 [org.clojure/google-closure-library-third-party "0.0-20150505-021ed5b3"]
                 [ring "1.3.2"]
                 [ring/ring-defaults "0.1.3"]
                 [thi.ng/geom "0.0.783"  :exclusions [com.google.guava/guava
                                                      com.google.javascript/closure-compiler
                                                      org.clojure/clojure
                                                      org.clojure/clojurescript]]]

  :plugins [[lein-cljsbuild "1.0.3"]
            [lein-environ "1.0.0"]]

  :min-lein-version "2.5.0"

  :uberjar-name "gampg.jar"

  :main "gampg.server"

  :cljsbuild {:builds {:app {:source-paths ["src/cljs" "yaks/gamma/src" "yaks/gamma-driver/src"]
                             :compiler {:output-to     "resources/public/js/app.js"
                                        :output-dir    "resources/public/js/out"
                                        :source-map    "resources/public/js/out.js.map"
                                        :preamble      ["cljsjs/development/react.inc.js"]
                                        :optimizations :none
                                        :pretty-print  true}}
                       :advanced {:source-paths ["src/cljs" "yaks/gamma/src" "yaks/gamma-driver/src"]
                                  :compiler {:output-to     "resources/public/advanced/app.js"
                                             :output-dir    "resources/public/advanced/out"
                                             :source-map    "resources/public/advanced/out.js.map"
                                             :preamble      ["cljsjs/production/react.min.inc.js"]
                                             :optimizations :advanced
                                             :pseudo-names  false
                                             :elide-asserts true
                                             :pretty-print  false}}}}

  :profiles {:dev {:source-paths ["src/cljs" "env/dev/clj" "yaks/gamma/src" "yaks/gamma-driver/src"]
                   :test-paths ["test/clj"]

                   :dependencies [ ;;[figwheel "0.2.1-SNAPSHOT"]
                                  [figwheel "0.3.3"]
                                  ;;[figwheel-sidecar "0.2.1-SNAPSHOT"]
                                  [figwheel-sidecar "0.3.3"]
                                  ;;[com.cemerick/piggieback "0.1.3"]
                                  [com.cemerick/piggieback "0.2.1"]
                                  ;;[weasel "0.4.2"]
                                  [weasel "0.7.0-SNAPSHOT"]
                                  [org.clojure/tools.nrepl "0.2.10"]
                                  ]

                   :repl-options {:init-ns gampg.server
                                  :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}

                   :plugins [ ;;[lein-figwheel "0.2.1-SNAPSHOT"]
                             [lein-figwheel "0.3.3"]
                             [cider/cider-nrepl "0.9.0-SNAPSHOT"]
                             [refactor-nrepl "1.0.5"]]

                   :figwheel {:http-server-root "public"
                              :server-port      3449
                              :nrepl-port       7888
                              :css-dirs         ["resources/public/css"]}

                   :env {:is-dev true}

                   :cljsbuild {:test-commands { "test" ["phantomjs" "env/test/js/unit-test.js" "env/test/unit-test.html"] }
                               :builds {:app {:source-paths ["src/cljs" "env/dev/cljs" "yaks/gamma/src" "yaks/gamma-driver/src"]}
                                        :test {:source-paths ["src/cljs" "test/cljs"  "yaks/gamma/src" "yaks/gamma-driver/src"]
                                               :compiler {:output-to     "resources/public/js/app_test.js"
                                                          :output-dir    "resources/public/js/test"
                                                          :source-map    "resources/public/js/test.js.map"
                                                          :preamble      ["cljsjs/development/react.inc.js"]
                                                          :optimizations :whitespace
                                                          :pretty-print  false}}
                                        :prod {:id           "prod"
                                               :source-paths ["src/cljs" "env/dev/cljs" "yaks/gamma/src" "yaks/gamma-driver/src"]
                                               :compiler     {:asset-path    "/js/bin"
                                                              :main          gampg.core
                                                              :output-to     "resources/public/js/bin/main.js"
                                                              :output-dir    "resources/public/js/bin"
                                                              :optimizations :advanced
                                                              ;;:pretty-print  true
                                                              :preamble      ["cljsjs/production/react.min.inc.js"]
                                                              :externs       ["cljsjs/common/react.ext.js"
                                                                              "resources/externs/webvr.js"]}}}}}

             :uberjar {:source-paths ["env/prod/clj"]
                       :hooks [leiningen.cljsbuild]
                       :env {:production true}
                       :omit-source true
                       :aot :all
                       :cljsbuild {:builds {:app
                                            {:source-paths ["env/prod/cljs"]
                                             :compiler
                                             {:optimizations :advanced
                                              :pretty-print false}}}}}})
