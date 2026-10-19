(ns prf.effect-transition-contract.core
  "Exploratory executable reference for effect-transition contract v1."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [prf.canonical.hash :as canonical]))

(def contract
  (edn/read-string (slurp (io/resource "prf/effect_transition_contract/v1/contract.edn"))))

(def effect-semantics (:effect-semantics contract))
(def effect-semantics-root
  (canonical/hash-with-domain
   (get-in contract [:domain-tags :canonical-effect-semantics])
   effect-semantics))

(defn- fail! [reason data]
  (throw (ex-info "Invalid canonical effect transition" (assoc data :reason reason))))

(defn state-root [state]
  (when-not (and (map? state)
                 (every? (fn [[root quantity]]
                           (and (string? root) (integer? quantity) (not (neg? quantity))))
                         state))
    (fail! :portable-contract/arithmetic-domain-violation {:field :state}))
  (canonical/hash-with-domain
   (get-in contract [:domain-tags :canonical-effect-state])
   {:schema-version (get-in contract [:schemas :canonical-state :id])
    :quantities (into (sorted-map) state)}))

(defn delta [quantity-root amount]
  (when-not (and (string? quantity-root) (integer? amount))
    (fail! :portable-contract/arithmetic-domain-violation
           {:quantity/root quantity-root :delta amount}))
  {:schema-version (get-in contract [:schemas :canonical-effect :id])
   :quantity/root quantity-root
   :delta amount})

(defn normalize-effects [effects]
  (when-not (sequential? effects)
    (fail! :portable-contract/malformed-input {:field :effects}))
  (->> effects
       (reduce (fn [totals effect]
                 (when-not (= #{:schema-version :quantity/root :delta} (set (keys effect)))
                   (fail! :portable-contract/malformed-input {:field :effect :effect effect}))
                 (when-not (= (get-in contract [:schemas :canonical-effect :id])
                              (:schema-version effect))
                   (fail! :portable-contract/unknown-version
                          {:schema-version (:schema-version effect)}))
                 (when-not (and (string? (:quantity/root effect))
                                (integer? (:delta effect)))
                   (fail! :portable-contract/arithmetic-domain-violation {:effect effect}))
                 (update totals (:quantity/root effect) (fnil +' 0N) (:delta effect)))
               {})
       (remove (comp zero? val))
       (sort-by key)
       (mapv (fn [[root amount]] (delta root amount)))))

(defn effects-root [effects]
  (canonical/hash-with-domain
   (get-in contract [:domain-tags :canonical-effect-set])
   {:schema-version (get-in contract [:schemas :canonical-effect :id])
    :effect-semantics/root effect-semantics-root
    :effects (normalize-effects effects)}))

(defn apply-effects [state effects]
  (reduce (fn [next-state {:keys [quantity/root delta]}]
            (let [after (+' (get next-state root 0N) delta)]
              (when (neg? after)
                (fail! :portable-contract/underflow {:quantity/root root}))
              (assoc next-state root after)))
          state (normalize-effects effects)))

(defn transition-root [transition]
  (canonical/hash-with-domain
   (get-in contract [:domain-tags :canonical-effect-transition])
   (select-keys transition [:schema-version :effect-semantics/root
                            :state-before/root :effects/root :state-after/root])))

(defn transition [state-before raw-effects]
  (let [normalized (normalize-effects raw-effects)
        state-after (apply-effects state-before normalized)
        body {:schema-version (get-in contract [:schemas :canonical-transition :id])
              :effect-semantics/root effect-semantics-root
              :state-before/root (state-root state-before)
              :effects/root (effects-root normalized)
              :state-after/root (state-root state-after)
              :effects normalized
              :state-after state-after}]
    (assoc body :canonical-effect-transition/root (transition-root body))))

(defn verify-transition
  "Recompute a transition and reject changes to effects, derived state, or roots."
  [state-before raw-effects supplied]
  (let [expected (transition state-before raw-effects)
        required (conj (set (keys expected)) :canonical-effect-transition/root)]
    (when-not (and (map? supplied) (= required (set (keys supplied))))
      (fail! :portable-contract/malformed-input {:field :transition}))
    (when-not (= (:schema-version expected) (:schema-version supplied))
      (fail! :portable-contract/unknown-version
             {:schema-version (:schema-version supplied)}))
    (when-not (= (select-keys expected [:effect-semantics/root :state-before/root
                                        :effects/root :effects])
                 (select-keys supplied [:effect-semantics/root :state-before/root
                                        :effects/root :effects]))
      (fail! :portable-contract/projection-mismatch {}))
    (when-not (= (:state-after expected) (:state-after supplied))
      (fail! :portable-contract/post-state-mismatch {}))
    (when-not (= (:state-after/root expected) (:state-after/root supplied))
      (fail! :portable-contract/post-state-mismatch {}))
    (when-not (= (:canonical-effect-transition/root expected)
                 (:canonical-effect-transition/root supplied))
      (fail! :portable-contract/root-mismatch {}))
    true))
