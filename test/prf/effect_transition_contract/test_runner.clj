(ns prf.effect-transition-contract.test-runner
  (:require [clojure.test :as test]
            [prf.effect-transition-contract.conformance-test]))

(defn -main [& _]
  (let [result (test/run-tests 'prf.effect-transition-contract.conformance-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
