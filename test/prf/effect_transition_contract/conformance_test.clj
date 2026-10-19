(ns prf.effect-transition-contract.conformance-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [prf.canonical.codec :as codec]
            [prf.canonical.hash :as canonical]
            [prf.effect-transition-contract.core :as transition-contract])
  (:import [java.security MessageDigest]))

(def release-domain "PRF_EFFECT_TRANSITION_CONTRACT_RELEASE_V1")
(def contract-resource "prf/effect_transition_contract/v1/contract.edn")
(def vector-resource "prf/effect_transition_contract/v1/vectors/canonical-transition-001.edn")
(def negative-vector-resource "prf/effect_transition_contract/v1/vectors/negative-transition-001.edn")
(def contract (edn/read-string (slurp (io/resource contract-resource))))
(def transition-vector
  (edn/read-string (slurp (io/resource vector-resource))))

(defn hex [bytes]
  (apply str (map #(format "%02x" (bit-and 0xff %)) bytes)))

(defn sha256-file [path]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [input (io/input-stream path)]
      (let [buffer (byte-array 8192)]
        (loop []
          (let [count (.read input buffer)]
            (when (pos? count)
              (.update digest buffer 0 count)
              (recur))))))
    (hex (.digest digest))))

(defn normalize-effects [effects]
  (->> effects
       (reduce (fn [totals effect]
                 (is (= "canonical-delta-effect.v1" (:schema-version effect)))
                 (update totals (:quantity/root effect) (fnil +' 0N) (:delta effect)))
               {})
       (remove (comp zero? val))
       (sort-by key)
       (mapv (fn [[root delta]]
               {:schema-version "canonical-delta-effect.v1"
                :quantity/root root
                :delta delta}))))

(defn state-projection [state]
  {:schema-version "canonical-effect-state.v1"
   :quantities (into (sorted-map) state)})

(defn state-root [state]
  (canonical/hash-with-domain "CANONICAL_EFFECT_STATE_V1" (state-projection state)))

(defn apply-effects [state effects]
  (reduce (fn [state {:keys [quantity/root delta]}]
            (let [amount (+' (get state root 0N) delta)]
              (when (neg? amount)
                (throw (ex-info "transition underflow" {:reason :underflow})))
              (assoc state root amount)))
          state effects))

(defn calculate-transition [{:keys [state-before raw-effects]}]
  (let [normalized (normalize-effects raw-effects)
        semantics-root (canonical/hash-with-domain
                        "CANONICAL_EFFECTS_V1" (:effect-semantics contract))
        before-root (state-root state-before)
        effects-projection {:schema-version "canonical-delta-effect.v1"
                            :effect-semantics/root semantics-root
                            :effects normalized}
        effects-root (canonical/hash-with-domain "CANONICAL_EFFECT_SET_V1"
                                                 effects-projection)
        state-after (apply-effects state-before normalized)
        after-root (state-root state-after)
        projection {:schema-version "canonical-effect-transition.v1"
                    :effect-semantics/root semantics-root
                    :state-before/root before-root
                    :effects/root effects-root
                    :state-after/root after-root}
        transition-root (canonical/hash-with-domain
                         "CANONICAL_EFFECT_TRANSITION_V1" projection)]
    {:normalized-effects normalized
     :state-after state-after
     :roots {:state-before/root before-root
             :effects/root effects-root
             :state-after/root after-root
             :transition/root transition-root}
     :canonical-values {:state-before (state-projection state-before)
                        :effects effects-projection
                        :state-after (state-projection state-after)
                        :transition projection}}))

(deftest release-manifest-is-rooted-and-closed
  (let [manifest (edn/read-string (slurp "RELEASE.edn"))]
    (is (= (:release/root manifest)
           (canonical/hash-with-domain release-domain (dissoc manifest :release/root))))
    (doseq [{:keys [path sha256]} (:files manifest)]
      (is (= sha256 (sha256-file (io/file path))) path))
    (is (= :exploratory (:release/status manifest)))))

(deftest historical-source-bundle-remains-intact
  (let [root (io/file "provenance/portable-pro-rata-contract.v1")
        manifest (edn/read-string (slurp (io/file root "manifest.edn")))]
    (is (= (:portable-contract/release-root manifest)
           (canonical/hash-with-domain
            "PORTABLE_PRO_RATA_CONTRACT_RELEASE_V1"
            (dissoc manifest :portable-contract/release-root))))
    (doseq [{:keys [path sha256]} (:files manifest)]
      (is (= sha256 (sha256-file (io/file root path))) path))))

(deftest canonical-implementation-resolves-once-from-the-pinned-revision
  (let [resources (vec (enumeration-seq
                        (.getResources (clojure.lang.RT/baseLoader)
                                       "prf/canonical/hash.clj")))]
    (is (= 1 (count resources)))
    (is (re-find #"/prf/canonical/5e2b520feca812b049ba397b7b3681963c66a48c/"
                 (str (first resources))))))

(deftest transition-vector-reconstructs-frozen-roots-and-bytes
  (let [expected (:expected transition-vector)
        actual (calculate-transition transition-vector)
        implemented (transition-contract/transition (:state-before transition-vector)
                                                     (:raw-effects transition-vector))]
    (is (= "prf-effect-transition-contract.v1" (:contract/version contract)))
    (is (= "portable-pro-rata-contract.v1" (:contract/version transition-vector)))
    (is (= (:normalized-effects expected) (:normalized-effects actual)))
    (is (= (:state-after expected) (:state-after actual)))
    (is (= (:roots expected) (:roots actual)))
    (is (= (:canonical-values expected) (:canonical-values actual)))
    (is (= (:roots expected)
           {:state-before/root (:state-before/root implemented)
            :effects/root (:effects/root implemented)
            :state-after/root (:state-after/root implemented)
            :transition/root (:canonical-effect-transition/root implemented)}))
    (is (= (:normalized-effects expected) (:effects implemented)))
    (is (= (:state-after expected) (:state-after implemented)))
    (doseq [[name value] (:canonical-values expected)]
      (let [path (str "prf/effect_transition_contract/v1/canonical-bytes/"
                      (case name
                        :state-before "state-before.hex"
                        :effects "effects.hex"
                        :state-after "state-after.hex"
                        :transition "transition.hex"))]
        (is (= (str/trim (slurp (io/resource path)))
               (codec/canonical-bytes-hex value))
            (str "frozen bytes: " name))))))

(deftest negative-vector-rejects-underflow
  (let [negative (edn/read-string (slurp (io/resource negative-vector-resource)))
        state (get-in negative [:mutations 5 :state-before])
        effects (get-in negative [:mutations 5 :effects])]
    (is (= :portable-contract/underflow
           (get-in negative [:mutations 5 :expected/reason])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"underflow"
                          (apply-effects state (normalize-effects effects))))
    (let [failure (try
                    (transition-contract/transition state effects)
                    nil
                    (catch clojure.lang.ExceptionInfo error error))]
      (is (= :portable-contract/underflow (:reason (ex-data failure)))))))

(deftest negative-vector-input-mutations-change-transition-commitments
  (let [negative (edn/read-string (slurp (io/resource negative-vector-resource)))
        expected (get-in transition-vector [:expected :roots])
        input-mutations (take 4 (:mutations negative))]
    (doseq [{:keys [mutation/id operation]} input-mutations]
      (let [mutated
            (case id
              :effect-omission
              (update transition-vector :raw-effects #(vec (concat (subvec % 0 2) (subvec % 3))))

              :effect-addition
              (update transition-vector :raw-effects conj (:append-effect operation))

              :effect-value
              (assoc-in transition-vector [:raw-effects 0 :delta]
                        (second (:replace-delta-at-index operation)))

              :post-state-substitution
              (assoc-in transition-vector
                        [:state-before (first (:replace-state-after-value operation))]
                        (second (:replace-state-after-value operation))))
            actual (:roots (calculate-transition mutated))]
        (is (not= expected actual) (str "mutation changes commitments: " id))))))

(defn apply-result-mutation [result {:keys [operation]}]
  (cond
    (:remove-raw-effect-index operation)
    (update result :effects #(vec (concat (subvec % 0 1) (subvec % 2))))

    (:append-effect operation)
    (update result :effects conj (:append-effect operation))

    (:replace-delta-at-index operation)
    (assoc-in result [:effects (first (:replace-delta-at-index operation)) :delta]
              (second (:replace-delta-at-index operation)))

    (:replace-state-after-value operation)
    (assoc-in result [:state-after (first (:replace-state-after-value operation))]
              (second (:replace-state-after-value operation)))

    (:replace-transition-root operation)
    (assoc result :canonical-effect-transition/root (:replace-transition-root operation))))

(deftest verifier-rejects-frozen-transition-mutations
  (let [negative (edn/read-string (slurp (io/resource negative-vector-resource)))
        valid (transition-contract/transition (:state-before transition-vector)
                                              (:raw-effects transition-vector))]
    (is (true? (transition-contract/verify-transition (:state-before transition-vector)
                                                       (:raw-effects transition-vector)
                                                       valid)))
    (doseq [{:keys [mutation/id expected/reason] :as mutation} (:mutations negative)
            :when (not= :underflow id)]
      (let [invalid (apply-result-mutation valid mutation)
            failure (try
                      (transition-contract/verify-transition
                       (:state-before transition-vector) (:raw-effects transition-vector) invalid)
                      nil
                      (catch clojure.lang.ExceptionInfo error error))]
        (is (= reason (:reason (ex-data failure))) (str "mutation rejection: " id))))))
