(ns gampg.test-runner
  (:require
   [cljs.test :refer-macros [run-tests]]
   [gampg.core-test]))

(enable-console-print!)

(defn runner []
  (if (cljs.test/successful?
       (run-tests
        'gampg.core-test))
    0
    1))
