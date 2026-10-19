# PRF Effect-Transition Contract v1 (Exploratory Extract)

> Source excerpts are copied verbatim from `portable-pro-rata-contract.v1` sections 5–7. This candidate inherits canonical encoding from the pinned `prf-canonical` implementation. It is not authoritative until independently reviewed and qualified.

## 5. Canonical Quantity State

A canonical state is a map from canonical quantity-root string to non-negative
arbitrary-precision integer. Native storage locations are not portable state.

The state projection is:

```clojure
{:schema-version "canonical-effect-state.v1"
 :quantities <map ordered by canonical map-key encoding>}
```

Its domain is `CANONICAL_EFFECT_STATE_V1`. State-before and state-after roots
have identical derivation rules.

## 6. Canonical Effects

A raw effect has the exact fields:

```clojure
{:schema-version "canonical-delta-effect.v1"
 :quantity/root <root string>
 :delta <signed arbitrary-precision integer>}
```

Normalization MUST sum all deltas for each target using unbounded signed
arithmetic, remove zero totals, and order by quantity-root string. Emission order
is irrelevant. Duplicate targets compose; they are not an error.

The effect-set projection is:

```clojure
{:schema-version "canonical-delta-effect.v1"
 :effect-semantics/root <fixed v1 semantics root>
 :effects <normalized vector>}
```

Its domain is `CANONICAL_EFFECT_SET_V1`.

## 7. Transition Derivation

The only portable authority direction is:

```text
validated state-before + normalized effects
  -> atomically apply every delta
  -> derived state-after
  -> state-after/root
  -> canonical-effect-transition/root
```

For each effect, missing quantities have value zero. A negative resulting value
is underflow and MUST reject the entire transition. No partial state or root may
be emitted. State-after is never caller-authoritative. If supplied for checking,
both its complete value and root MUST equal independent derivation.

The transition root projection contains exactly:

```clojure
{:schema-version "canonical-effect-transition.v1"
 :effect-semantics/root ...
 :state-before/root ...
 :effects/root ...
 :state-after/root ...}
```

Its domain is `CANONICAL_EFFECT_TRANSITION_V1`. The effects and full derived
state-after are reconstruction evidence but are not duplicated in this root
projection because their roots already commit them.
