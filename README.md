# PRF effect-transition contract

**Status: exploratory source extraction.** This repository is an unpublished
candidate for owning the canonical quantity-state, canonical-effect, and
canonical-transition contract. It is not yet a qualified release and PRF does
not depend on it.

The candidate preserves the existing schema versions, domain tags, fixture
bytes, and roots. It does not redefine canonical encoding, allocation, or
application-specific allocation-to-effect mappings. Canonical encoding is
provided by the pinned `prf-canonical` dependency.

## Provenance

The original combined `portable-pro-rata-contract.v1` source bundle is retained
byte-for-byte under `provenance/portable-pro-rata-contract.v1/`. Its manifest
and release root remain intact. The proposed effect-transition files at the
repository root are an extracted view of the transition sections and fixtures;
they become authoritative only after independent review and consumer
qualification.

Candidate EDN and frozen fixtures are exposed as classpath resources under
`prf/effect_transition_contract/v1/` through the `resources` path in `deps.edn`.

The exploratory Clojure API is `prf.effect-transition-contract.core`: it
provides `delta`, `normalize-effects`, `state-root`, `effects-root`,
`apply-effects`, `transition`, and `verify-transition`. The verifier takes
`state-before`, `raw-effects`, and a supplied transition result, independently
recomputes the result, and rejects mismatched effects, derived state, or roots.
The Rust crate under `rust/` exposes the same transition operations and pins the
same `prf-canonical` Git revision as the Clojure dependency. Cargo version
`0.1.0-exploratory.1` is recorded in `RELEASE.edn` as the package identity.

The original bundle was present in PRF revision
`9f03721b966429764b385ed2250697d087b25126`. Its recorded release root is
`b863e04a61d0dded845dc3351626ebf57c4d5abea7e18ea19a89cacf4b1ad12a`.

## Qualification limits

This draft has no remote, published artifact, consumer pin, or release claim.
The Clojure and Rust conformance suites check the frozen transition vector and
mutation rejection vectors using the shared canonical library. Independent
ownership review and consumer qualification remain outstanding. This does not
qualify EVM, Solidity, SP1 proofs, or a production deployment.

The Clojure suite reads the EDN vectors directly. Rust currently mirrors the
vector inputs in its test module and compares against the frozen roots and
canonical-byte resources; automatic detection of drift between the EDN inputs
and those Rust literals remains to be added.
