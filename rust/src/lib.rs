//! Exploratory Rust realization of PRF effect-transition contract v1.

use num_bigint::BigInt;
use prf_canonical_codec::{domain_hash, CanonValue};
use std::collections::BTreeMap;

pub type State = BTreeMap<String, BigInt>;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Effect {
    pub schema_version: String,
    pub quantity_root: String,
    pub delta: BigInt,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Transition {
    pub schema_version: String,
    pub effect_semantics_root: String,
    pub state_before_root: String,
    pub effects_root: String,
    pub state_after_root: String,
    pub effects: Vec<Effect>,
    pub state_after: State,
    pub transition_root: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TransitionError {
    InvalidState,
    InvalidEffect,
    UnknownVersion,
    Underflow,
    ProjectionMismatch,
    PostStateMismatch,
    RootMismatch,
}

const EFFECT_SEMANTICS_DOMAIN: &str = "CANONICAL_EFFECTS_V1";
const STATE_DOMAIN: &str = "CANONICAL_EFFECT_STATE_V1";
const EFFECTS_DOMAIN: &str = "CANONICAL_EFFECT_SET_V1";
const TRANSITION_DOMAIN: &str = "CANONICAL_EFFECT_TRANSITION_V1";
const EFFECT_SCHEMA: &str = "canonical-delta-effect.v1";
const STATE_SCHEMA: &str = "canonical-effect-state.v1";
const TRANSITION_SCHEMA: &str = "canonical-effect-transition.v1";

fn keyword(value: &str) -> CanonValue {
    CanonValue::keyword(value)
}

fn string(value: &str) -> CanonValue {
    CanonValue::str(value)
}

fn integer(value: &BigInt) -> CanonValue {
    CanonValue::int(value.clone())
}

fn map(entries: Vec<(&str, CanonValue)>) -> CanonValue {
    CanonValue::map(
        entries
            .into_iter()
            .map(|(key, value)| (keyword(key), value))
            .collect(),
    )
}

fn semantics_value() -> CanonValue {
    map(vec![
        ("schema-version", string("canonical-effects-v1")),
        ("integer-domain", keyword("signed-arbitrary-precision")),
        ("quantity-domain", keyword("non-negative-integer")),
        ("underflow", keyword("forbidden")),
        ("overflow", keyword("impossible")),
        ("ordering", keyword("canonical-target-order")),
        ("emission-order", keyword("irrelevant")),
        ("duplicate-targets", keyword("compose")),
        ("composition", keyword("atomic-net-delta")),
        ("failure", keyword("reject-entire-transition")),
    ])
}

fn state_value(state: &State) -> CanonValue {
    let quantities = CanonValue::map(
        state
            .iter()
            .map(|(root, quantity)| (string(root), integer(quantity)))
            .collect(),
    );
    map(vec![
        ("schema-version", string(STATE_SCHEMA)),
        ("quantities", quantities),
    ])
}

pub fn effect_semantics_root() -> String {
    domain_hash(EFFECT_SEMANTICS_DOMAIN, &semantics_value())
}

pub fn state_root(state: &State) -> Result<String, TransitionError> {
    if state
        .values()
        .any(|value| value.sign() == num_bigint::Sign::Minus)
    {
        return Err(TransitionError::InvalidState);
    }
    Ok(domain_hash(STATE_DOMAIN, &state_value(state)))
}

pub fn normalize_effects(effects: &[Effect]) -> Result<Vec<Effect>, TransitionError> {
    let mut totals: BTreeMap<String, BigInt> = BTreeMap::new();
    for effect in effects {
        if effect.schema_version != EFFECT_SCHEMA {
            return Err(TransitionError::UnknownVersion);
        }
        *totals.entry(effect.quantity_root.clone()).or_default() += &effect.delta;
    }
    Ok(totals
        .into_iter()
        .filter(|(_, delta)| delta != &BigInt::from(0))
        .map(|(quantity_root, delta)| Effect {
            schema_version: EFFECT_SCHEMA.to_string(),
            quantity_root,
            delta,
        })
        .collect())
}

fn effect_value(effect: &Effect) -> CanonValue {
    map(vec![
        ("schema-version", string(&effect.schema_version)),
        ("quantity/root", string(&effect.quantity_root)),
        ("delta", integer(&effect.delta)),
    ])
}

pub fn effects_root(effects: &[Effect]) -> Result<String, TransitionError> {
    let normalized = normalize_effects(effects)?;
    let projection = map(vec![
        ("schema-version", string(EFFECT_SCHEMA)),
        ("effect-semantics/root", string(&effect_semantics_root())),
        (
            "effects",
            CanonValue::array(normalized.iter().map(effect_value).collect()),
        ),
    ]);
    Ok(domain_hash(EFFECTS_DOMAIN, &projection))
}

pub fn apply_effects(state: &State, effects: &[Effect]) -> Result<State, TransitionError> {
    let normalized = normalize_effects(effects)?;
    let mut result = state.clone();
    for effect in normalized {
        let next = result
            .get(&effect.quantity_root)
            .cloned()
            .unwrap_or_else(|| BigInt::from(0))
            + effect.delta;
        if next.sign() == num_bigint::Sign::Minus {
            return Err(TransitionError::Underflow);
        }
        result.insert(effect.quantity_root, next);
    }
    Ok(result)
}

fn transition_projection(transition: &Transition) -> CanonValue {
    map(vec![
        ("schema-version", string(&transition.schema_version)),
        (
            "effect-semantics/root",
            string(&transition.effect_semantics_root),
        ),
        ("state-before/root", string(&transition.state_before_root)),
        ("effects/root", string(&transition.effects_root)),
        ("state-after/root", string(&transition.state_after_root)),
    ])
}

pub fn transition_root(transition: &Transition) -> String {
    domain_hash(TRANSITION_DOMAIN, &transition_projection(transition))
}

pub fn transition(
    state_before: &State,
    raw_effects: &[Effect],
) -> Result<Transition, TransitionError> {
    let effects = normalize_effects(raw_effects)?;
    let state_after = apply_effects(state_before, &effects)?;
    let mut result = Transition {
        schema_version: TRANSITION_SCHEMA.to_string(),
        effect_semantics_root: effect_semantics_root(),
        state_before_root: state_root(state_before)?,
        effects_root: effects_root(&effects)?,
        state_after_root: state_root(&state_after)?,
        effects,
        state_after,
        transition_root: String::new(),
    };
    result.transition_root = transition_root(&result);
    Ok(result)
}

pub fn verify_transition(
    state_before: &State,
    raw_effects: &[Effect],
    supplied: &Transition,
) -> Result<(), TransitionError> {
    let expected = transition(state_before, raw_effects)?;
    if supplied.schema_version != expected.schema_version {
        return Err(TransitionError::UnknownVersion);
    }
    if supplied.effect_semantics_root != expected.effect_semantics_root
        || supplied.state_before_root != expected.state_before_root
        || supplied.effects_root != expected.effects_root
        || supplied.effects != expected.effects
    {
        return Err(TransitionError::ProjectionMismatch);
    }
    if supplied.state_after != expected.state_after
        || supplied.state_after_root != expected.state_after_root
    {
        return Err(TransitionError::PostStateMismatch);
    }
    if supplied.transition_root != expected.transition_root {
        return Err(TransitionError::RootMismatch);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const ROOT_A: &str = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const ROOT_B: &str = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    fn report_rejection(vector_id: &str, reason: &str) {
        if std::env::var_os("PRF_VECTOR_DRIFT").is_some() {
            println!("PRF_VECTOR_DRIFT_V1|transition-rejection|{vector_id}|{reason}");
        }
    }

    fn big(value: i64) -> BigInt {
        BigInt::from(value)
    }

    fn effect(root: &str, delta: i64) -> Effect {
        Effect {
            schema_version: EFFECT_SCHEMA.to_string(),
            quantity_root: root.to_string(),
            delta: big(delta),
        }
    }

    fn vector() -> (State, Vec<Effect>) {
        let state = BTreeMap::from([(ROOT_A.to_string(), big(10)), (ROOT_B.to_string(), big(2))]);
        let effects = vec![
            effect(ROOT_A, 5),
            effect(ROOT_A, -2),
            effect(ROOT_B, -2),
            effect(ROOT_A, 0),
        ];
        (state, effects)
    }

    #[test]
    fn canonical_vector_reconstructs_frozen_roots_and_bytes() {
        let (state_before, raw_effects) = vector();
        let result = transition(&state_before, &raw_effects).unwrap();
        assert_eq!(result.effects, vec![effect(ROOT_A, 3), effect(ROOT_B, -2)]);
        assert_eq!(result.state_after[ROOT_A], big(13));
        assert_eq!(result.state_after[ROOT_B], big(0));
        assert_eq!(
            result.state_before_root,
            "08293a36ff057a01de22c8bb69d2ab2ec2a104cbf9c2007f337526e2f0e12640"
        );
        assert_eq!(
            result.effects_root,
            "70a5130f16f1c812e83bbb3e7ec6fc0dc9e447ade75e84abc14f9c8f18b28358"
        );
        assert_eq!(
            result.state_after_root,
            "4a9be347a478788e7beab4bc9e1c50ddd41b00a55e239c3a415c3f32f766d9f8"
        );
        assert_eq!(
            result.transition_root,
            "852a8a2a93dbf8339d35b04a00217bc0d2cc0f5ead1dae781f5197988aec6bf6"
        );
        if std::env::var_os("PRF_VECTOR_DRIFT").is_some() {
            println!(
                "PRF_VECTOR_DRIFT_V1|transition|canonical-transition-001|{}|{}|{}|{}",
                result.state_before_root,
                result.effects_root,
                result.state_after_root,
                result.transition_root
            );
        }
        assert_eq!(hex::encode(state_value(&state_before).encode()), include_str!("../../resources/prf/effect_transition_contract/v1/canonical-bytes/state-before.hex").trim());
        let effects_projection = CanonValue::map(vec![
            (keyword("schema-version"), string(EFFECT_SCHEMA)),
            (
                keyword("effect-semantics/root"),
                string(&effect_semantics_root()),
            ),
            (
                keyword("effects"),
                CanonValue::array(result.effects.iter().map(effect_value).collect()),
            ),
        ]);
        assert_eq!(
            hex::encode(effects_projection.encode()),
            include_str!(
                "../../resources/prf/effect_transition_contract/v1/canonical-bytes/effects.hex"
            )
            .trim()
        );
        assert_eq!(
            hex::encode(state_value(&result.state_after).encode()),
            include_str!(
                "../../resources/prf/effect_transition_contract/v1/canonical-bytes/state-after.hex"
            )
            .trim()
        );
        assert_eq!(
            hex::encode(transition_projection(&result).encode()),
            include_str!(
                "../../resources/prf/effect_transition_contract/v1/canonical-bytes/transition.hex"
            )
            .trim()
        );
        assert_eq!(
            verify_transition(&state_before, &raw_effects, &result),
            Ok(())
        );
    }

    #[test]
    fn frozen_negative_vectors_are_rejected() {
        let (state_before, raw_effects) = vector();
        let valid = transition(&state_before, &raw_effects).unwrap();

        let mut omitted = valid.clone();
        omitted.effects.remove(1);
        assert_eq!(
            verify_transition(&state_before, &raw_effects, &omitted),
            Err(TransitionError::ProjectionMismatch)
        );
        report_rejection("effect-omission", "projection-mismatch");

        let mut added = valid.clone();
        added.effects.push(effect(
            "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            1,
        ));
        assert_eq!(
            verify_transition(&state_before, &raw_effects, &added),
            Err(TransitionError::ProjectionMismatch)
        );
        report_rejection("effect-addition", "projection-mismatch");

        let mut changed = valid.clone();
        changed.effects[0].delta = big(6);
        assert_eq!(
            verify_transition(&state_before, &raw_effects, &changed),
            Err(TransitionError::ProjectionMismatch)
        );
        report_rejection("effect-value", "projection-mismatch");

        let mut substituted = valid.clone();
        substituted.state_after.insert(ROOT_A.to_string(), big(999));
        assert_eq!(
            verify_transition(&state_before, &raw_effects, &substituted),
            Err(TransitionError::PostStateMismatch)
        );
        report_rejection("post-state-substitution", "post-state-mismatch");

        let mut wrong_root = valid;
        wrong_root.transition_root =
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff".to_string();
        assert_eq!(
            verify_transition(&state_before, &raw_effects, &wrong_root),
            Err(TransitionError::RootMismatch)
        );
        report_rejection("root-substitution", "root-mismatch");
    }

    #[test]
    fn underflow_rejects_without_emitting_a_transition() {
        let state = BTreeMap::from([(ROOT_A.to_string(), big(1))]);
        let effects = vec![effect(ROOT_A, -2)];
        assert_eq!(
            transition(&state, &effects),
            Err(TransitionError::Underflow)
        );
        report_rejection("underflow", "underflow");
    }
}
