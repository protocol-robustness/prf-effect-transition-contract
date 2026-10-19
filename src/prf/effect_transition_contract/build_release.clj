(ns prf.effect-transition-contract.build-release
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [prf.canonical.hash :as canonical])
  (:import [java.security MessageDigest]))

(def release-domain "PRF_EFFECT_TRANSITION_CONTRACT_RELEASE_V1")
(def release-files
  [".gitignore" "README.md" "SPEC.md" "deps.edn"
   "src/prf/effect_transition_contract/build_release.clj"
   "test/prf/effect_transition_contract/test_runner.clj"
   "test/prf/effect_transition_contract/conformance_test.clj"
   "resources/prf/effect_transition_contract/v1/contract.edn"
   "resources/prf/effect_transition_contract/v1/vectors/canonical-transition-001.edn"
   "resources/prf/effect_transition_contract/v1/vectors/negative-transition-001.edn"
   "resources/prf/effect_transition_contract/v1/canonical-bytes/state-before.hex"
   "resources/prf/effect_transition_contract/v1/canonical-bytes/effects.hex"
   "resources/prf/effect_transition_contract/v1/canonical-bytes/state-after.hex"
   "resources/prf/effect_transition_contract/v1/canonical-bytes/transition.hex"])

(defn sha256-file [path]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [input (io/input-stream path)]
      (let [buffer (byte-array 8192)]
        (loop []
          (let [count (.read input buffer)]
            (when (pos? count)
              (.update digest buffer 0 count)
              (recur))))))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

(defn build-manifest []
  (let [contract (edn/read-string
                  (slurp "resources/prf/effect_transition_contract/v1/contract.edn"))
        manifest {:artifact/schema "prf-effect-transition-release.v1"
                  :contract/id (:contract/id contract)
                  :contract/version (:contract/version contract)
                  :release/status :exploratory
                  :release/source {:repository "prf-effect-transition-contract"
                                   :source-repository "protocol-robustness-framework"
                                   :source-revision (:source/prf-revision contract)
                                   :source-bundle-release-root
                                   (:source/bundle-release-root contract)}
                  :files (mapv (fn [path]
                                 {:path path :sha256 (sha256-file path)})
                               release-files)}]
    (assoc manifest :release/root (canonical/hash-with-domain release-domain manifest))))

(defn -main [& _]
  (spit "RELEASE.edn" (str (pr-str (build-manifest)) "\n")))
