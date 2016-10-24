(defproject gampg "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :source-paths ["src/clj"]
  :test-paths ["test/clj"]
  :dependencies [[cljsjs/react "0.13.3-0"]
                 [com.stuartsierra/component "0.2.3"]
                 [compojure "1.3.1"]
                 [enlive "1.1.5"]
                 [environ "1.0.0"]
                 [fipp "0.6.2"]
                 [instaparse "1.4.0"]
                 [kovasb/gamma-driver "0.0-49"]
                 [kovasb/gamma "0.0-135"]
                 [markdown-clj "0.9.66"]
                 [org.omcljs/om "0.8.8"]
                 [org.clojure/clojure "1.7.0-beta3"]
                 [org.clojure/clojurescript "0.0-3297"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/google-closure-library "0.0-20150505-021ed5b3"]
                 [org.clojure/google-closure-library-third-party "0.0-20150505-021ed5b3"]
                 [ring "1.3.2"]
                 [ring/ring-defaults "0.1.3"]
                 [thi.ng/geom "0.0.815"  :exclusions [com.google.guava/guava
                                                      com.google.javascript/closure-compiler
                                                      org.clojure/clojure
                                                      org.clojure/clojurescript]]]
  :plugins [[lein-cljsbuild "1.0.5"]
            [lein-environ "1.0.0"]]
  :min-lein-version "2.5.0"
  :clean-targets ^{:protect false} [:target-path :compile-path "resources/public/js"]
  :profiles {:dev {:source-paths ["env/dev/clj"]
                   :dependencies [[figwheel-sidecar "0.3.3"]
                                  [http-kit "2.1.18"] ;override figwheel's version to fix #152
                                  ]
                   :cljsbuild {:test-commands { "test" ["phantomjs" "env/test/js/unit-test.js" "env/test/unit-test.html"] }
                               :builds {:test {:source-paths ["src/cljs" "test/cljs"]
                                               :compiler {:output-to     "resources/public/js/app_test.js"
                                                          :output-dir    "resources/public/js/test"
                                                          :source-map    "resources/public/js/test.js.map"
                                                          :optimizations :whitespace
                                                          :pretty-print  false}}}}}
             :uberjar {:hooks [leiningen.cljsbuild]
                       :omit-source true
                       :uberjar-name "gampg.jar"
                       :main "gampg.main"
                       :aot :all
                       :cljsbuild {:builds {:app
                                            {:source-paths ["src/cljs" "env/prod/cljs"]
                                             :compiler
                                             {:output-to            "resources/public/js/app.js"
                                              :output-dir           "resources/public/js/prod"
                                              :optimizations :advanced
                                              :pretty-print false}}}}}})
