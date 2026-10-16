//! Bounded native receiver advertisements, separate from policy and runtime state.

use std::{collections::HashSet, fmt, io};

use serde::{Deserialize, Deserializer, Serialize, de};
use serde_json::{Map, Value};

const MAX_DESCRIPTOR_BYTES: usize = 64 * 1024;
const ENGINE_SETS: &[&str] = &[
    "transport",
    "keys",
    "pointer",
    "imageTransforms",
    "text",
    "settings",
    "tracks",
    "queue",
    "browser",
    "reporting",
];

/// Validated v1 JSON. Keeping the supplied shape preserves empty sets and open IDs exactly.
/// Hosts must implement operations before publishing them; this model makes no support guesses.
#[derive(Clone, Serialize, PartialEq, Eq)]
#[serde(transparent)]
pub struct ReceiverCapabilities(Value);

impl ReceiverCapabilities {
    pub fn from_json(value: Value) -> Result<Self, &'static str> {
        // Version selection precedes interpreting any version-specific shape.
        if !is_v1(&value) {
            return Err("unsupported receiver capabilities version");
        }
        serde_json::to_writer(&mut SizeBound(0), &value)
            .map_err(|_| "receiver capabilities exceed 64 KiB")?;
        validate_v1(&value)?;
        Ok(Self(value))
    }

    pub fn as_json(&self) -> &Value {
        &self.0
    }

    /// True for the sender-side marker of a recognized v1 descriptor that failed validation.
    /// Senders must fail closed for native controls rather than treating it as absent.
    pub fn is_malformed(&self) -> bool {
        // from_json never produces Null: every accepted value is an object with version 1.
        self.0.is_null()
    }

    fn malformed_v1() -> Self {
        Self(Value::Null)
    }
}

impl fmt::Debug for ReceiverCapabilities {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        // ReceiverFrame's derived Debug must never start logging full descriptors.
        formatter
            .debug_struct("ReceiverCapabilities")
            .field("version", &1)
            .finish_non_exhaustive()
    }
}

impl<'de> Deserialize<'de> for ReceiverCapabilities {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        Self::from_json(Value::deserialize(deserializer)?).map_err(de::Error::custom)
    }
}

/// Sender-only adapter: future layouts are opaque; optional metadata never breaks legacy auth.
/// Absent and unknown versions stay `None` (legacy). A recognized v1 that fails validation
/// becomes the malformed marker, so senders can fail closed instead of falling back.
pub(crate) fn deserialize_optional<'de, D: Deserializer<'de>>(
    deserializer: D,
) -> Result<Option<ReceiverCapabilities>, D::Error> {
    let raw = Option::<Value>::deserialize(deserializer)?;
    Ok(raw.filter(is_v1).map(|value| {
        ReceiverCapabilities::from_json(value)
            .unwrap_or_else(|_| ReceiverCapabilities::malformed_v1())
    }))
}

fn is_v1(value: &Value) -> bool {
    value.get("version").and_then(Value::as_f64) == Some(1.0)
}

struct SizeBound(usize);

impl io::Write for SizeBound {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        if bytes.len() > MAX_DESCRIPTOR_BYTES - self.0 {
            return Err(io::Error::other("descriptor size limit"));
        }
        self.0 += bytes.len();
        Ok(bytes.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

fn object<'a>(value: &'a Value, fields: &[&str]) -> Result<&'a Map<String, Value>, &'static str> {
    let object = value
        .as_object()
        .ok_or("invalid receiver capabilities object")?;
    if object.keys().any(|key| !fields.contains(&key.as_str())) {
        return Err("invalid receiver capabilities field");
    }
    Ok(object)
}

fn identifier(value: &str) -> Result<(), &'static str> {
    if value.is_empty() || value.chars().count() > 64 {
        return Err("invalid receiver capabilities identifier");
    }
    Ok(())
}

fn identifier_value(value: &Value) -> Result<(), &'static str> {
    identifier(
        value
            .as_str()
            .ok_or("invalid receiver capabilities identifier type")?,
    )
}

fn identifiers(value: &Value) -> Result<(), &'static str> {
    let items = value
        .as_array()
        .ok_or("invalid receiver capabilities set")?;
    if items.len() > 64 {
        return Err("receiver capabilities set exceeds 64 identifiers");
    }
    let mut unique = HashSet::new();
    for item in items {
        identifier_value(item)?;
        if !unique.insert(item.as_str().expect("identifier validated")) {
            return Err("duplicate receiver capabilities identifier");
        }
    }
    Ok(())
}

fn identifier_map(value: &Value) -> Result<&Map<String, Value>, &'static str> {
    let map = value
        .as_object()
        .ok_or("invalid receiver capabilities map")?;
    if map.len() > 16 {
        return Err("receiver capabilities map exceeds 16 identifiers");
    }
    for key in map.keys() {
        identifier(key)?;
    }
    Ok(map)
}

fn validate_v1(value: &Value) -> Result<(), &'static str> {
    let root = object(
        value,
        &["version", "surfaces", "volume", "host", "companionHints"],
    )?;
    let surfaces = identifier_map(
        root.get("surfaces")
            .ok_or("missing receiver capabilities surfaces")?,
    )?;
    for surface in surfaces.values() {
        let surface = object(surface, &["engines"])?;
        let engines = identifier_map(
            surface
                .get("engines")
                .ok_or("missing receiver capabilities engines")?,
        )?;
        for engine in engines.values() {
            for set in object(engine, ENGINE_SETS)?.values() {
                identifiers(set)?;
            }
        }
    }
    if let Some(volume) = root.get("volume") {
        let volume = object(volume, &["scope", "operations", "step", "fallbackScope"])?;
        identifier_value(volume.get("scope").ok_or("missing receiver volume scope")?)?;
        if let Some(operations) = volume.get("operations") {
            identifiers(operations)?;
        }
        if let Some(scope) = volume.get("fallbackScope") {
            identifier_value(scope)?;
        }
        if let Some(step) = volume.get("step") {
            let step = step.as_f64().ok_or("invalid receiver volume step")?;
            if !step.is_finite() || step <= 0.0 || step > 1.0 {
                return Err("invalid receiver volume step");
            }
        }
    }
    if let Some(host) = root.get("host") {
        for value in object(host, &["osFamily", "deviceClass"])?.values() {
            identifier_value(value)?;
        }
    }
    if let Some(hints) = root.get("companionHints") {
        identifiers(hints)?;
    }
    Ok(())
}

/// Authenticated metadata advertises this feature when a receiver can push live descriptor
/// updates. The offer alone does not enable pushes; senders subscribe explicitly.
pub const RECEIVER_CAPABILITIES_FEATURE: &str = "receiver_capabilities_v1";

/// Largest revision a live update may carry (2^53 - 1), the largest integer JSON numbers
/// represent exactly.
pub const MAX_LIVE_REVISION: u64 = (1 << 53) - 1;

/// What a live `receiver_capabilities` update says about the descriptor. It replaces the whole
/// descriptor, so only the three outcomes below exist.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LiveDescriptor {
    /// A valid v1 descriptor that replaces the current one.
    Valid(ReceiverCapabilities),
    /// A recognized v1 descriptor that failed validation. It advances ordering and fails closed.
    Malformed,
    /// A newer or unrecognized version. Its shape is opaque, so the current descriptor is kept.
    UnknownVersion,
}

/// A structurally valid live update with a valid revision.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CapabilitiesUpdate {
    pub revision: u64,
    pub descriptor: LiveDescriptor,
}

/// A decoded `receiver_capabilities` frame. Invalid envelopes and revisions are `Invalid`. They
/// never move ordering, so a sender drops them without changing its state.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CapabilitiesFrame {
    Update(CapabilitiesUpdate),
    Invalid,
}

impl CapabilitiesFrame {
    /// Decodes a live frame without failing: every outcome is representable. The revision must be
    /// a JSON integer from 1 through 2^53 - 1. Fractions, strings, zero and larger values are
    /// invalid.
    pub fn from_value(value: Value) -> Self {
        let Some(object) = value.as_object() else {
            return Self::Invalid;
        };
        let Some(revision) = object.get("revision").and_then(revision_number) else {
            return Self::Invalid;
        };
        let Some(capabilities) = object.get("capabilities").filter(|value| value.is_object())
        else {
            return Self::Invalid;
        };
        let descriptor = if is_v1(capabilities) {
            match ReceiverCapabilities::from_json(capabilities.clone()) {
                Ok(descriptor) => LiveDescriptor::Valid(descriptor),
                Err(_) => LiveDescriptor::Malformed,
            }
        } else {
            // Opaque shapes are bounded before anything reads them. An oversized unknown version
            // is an invalid envelope, not a version to advance ordering for.
            if serde_json::to_writer(&mut SizeBound(0), capabilities).is_err() {
                return Self::Invalid;
            }
            LiveDescriptor::UnknownVersion
        };
        Self::Update(CapabilitiesUpdate {
            revision,
            descriptor,
        })
    }
}

/// Integers are exact. A float is accepted when it is finite, integral, and in range, so `1.0` and
/// `1e0` are valid. Senders may decode through binary floating point, so a value such as
/// `9007199254740991.1` rounds to an accepted integer. Strings, zero, negatives, fractions, and
/// values at or above 2^53 are rejected.
fn revision_number(value: &Value) -> Option<u64> {
    let number = match value.as_u64() {
        Some(number) => number,
        None => {
            let float = value.as_f64()?;
            if !float.is_finite()
                || float.fract() != 0.0
                || float < 1.0
                || float > MAX_LIVE_REVISION as f64
            {
                return None;
            }
            float as u64
        }
    };
    (1..=MAX_LIVE_REVISION).contains(&number).then_some(number)
}

impl<'de> Deserialize<'de> for CapabilitiesFrame {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        Ok(Self::from_value(Value::deserialize(deserializer)?))
    }
}

impl Serialize for CapabilitiesFrame {
    fn serialize<S: serde::Serializer>(&self, _serializer: S) -> Result<S::Ok, S::Error> {
        // Live updates are receive-only. Their opaque shapes are not retained for re-encoding.
        Err(serde::ser::Error::custom(
            "receiver capability updates are receive-only",
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::playbridge::{CredentialBundle, ReceiverFrame};
    use serde_json::json;

    fn fixture(name: &str) -> Value {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../protocol/tests/fixtures/receiver-capabilities")
            .join(name);
        serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap()
    }

    #[test]
    fn sender_auth_and_bundle_decode_the_contract_corpus_without_changing_legacy_fields() {
        let manifest: Vec<Value> = serde_json::from_str(include_str!(
            "../../../protocol/tests/fixtures/receiver-capabilities/manifest.json"
        ))
        .unwrap();
        for case in manifest {
            let schema = case["schema"].as_str().unwrap();
            if case["valid"] != true || !matches!(schema, "AuthResponse" | "CredentialBundle") {
                continue;
            }
            let mut value = fixture(case["file"].as_str().unwrap());
            let expected = value
                .get("capabilities")
                .filter(|value| is_v1(value))
                .cloned();
            value["futureTopLevel"] = json!({"anything": [false, "future"]});
            let capabilities = if schema == "AuthResponse" {
                let ReceiverFrame::AuthResponse {
                    success,
                    capabilities,
                    players,
                    ..
                } = serde_json::from_value(value.clone()).unwrap()
                else {
                    panic!("expected auth");
                };
                assert!(success);
                assert_eq!(
                    serde_json::to_value(players).unwrap(),
                    value.get("players").cloned().unwrap_or(json!([]))
                );
                capabilities
            } else {
                let credentials: CredentialBundle = serde_json::from_value(value.clone()).unwrap();
                assert_eq!(credentials.token, value["token"]);
                credentials.capabilities
            };
            assert_eq!(
                capabilities.as_ref().map(ReceiverCapabilities::as_json),
                expected.as_ref()
            );
            if let Some(capabilities) = capabilities {
                assert_eq!(
                    serde_json::to_value(capabilities).unwrap(),
                    expected.unwrap()
                );
            }
        }
    }

    #[test]
    fn unknown_ids_and_explicit_empty_sets_are_preserved_exactly() {
        for name in [
            "unknown-identifiers-auth-v1.json",
            "empty-complete-descriptor.json",
            "empty-complete-engine.json",
        ] {
            let raw = fixture(name)["capabilities"].clone();
            let descriptor = ReceiverCapabilities::from_json(raw.clone()).unwrap();
            assert_eq!(descriptor.as_json(), &raw);
            assert_eq!(serde_json::to_value(descriptor).unwrap(), raw);
        }
        let raw =
            json!({"version":1,"surfaces":{"Player":{"engines":{"EXO":{"keys":["home","HOME"]}}}}});
        assert_eq!(
            ReceiverCapabilities::from_json(raw.clone())
                .unwrap()
                .as_json(),
            &raw
        );
    }

    #[test]
    fn absent_null_unknown_version_and_future_shapes_are_legacy_for_senders() {
        for raw in [
            Value::Null,
            json!({"version":2,"surfaces":[]}),
            json!({"version":4294967295_u64,"future":[1]}),
            json!({"version":"1","surfaces":{}}),
        ] {
            let frame: ReceiverFrame = serde_json::from_value(
                json!({"type":"auth_response","success":true,"capabilities":raw}),
            )
            .unwrap();
            assert!(matches!(
                frame,
                ReceiverFrame::AuthResponse {
                    success: true,
                    capabilities: None,
                    ..
                }
            ));
            let credentials: CredentialBundle =
                serde_json::from_value(json!({"token":"TEST_ONLY","capabilities":raw})).unwrap();
            assert!(credentials.capabilities.is_none());
        }
        let frame: ReceiverFrame =
            serde_json::from_value(json!({"type":"auth_response","success":true})).unwrap();
        assert!(
            !serde_json::to_value(frame)
                .unwrap()
                .as_object()
                .unwrap()
                .contains_key("capabilities")
        );
        let bundle: CredentialBundle =
            serde_json::from_value(json!({"token":"TEST_ONLY"})).unwrap();
        assert!(
            !serde_json::to_value(bundle)
                .unwrap()
                .as_object()
                .unwrap()
                .contains_key("capabilities")
        );
    }

    #[test]
    fn malformed_v1_is_rejected_for_hosts_but_does_not_break_sender_auth() {
        for name in [
            "missing-version",
            "string-version",
            "zero-version",
            "oversized-version",
            "missing-surfaces",
            "contradictory-surface-list",
            "duplicate-operations",
            "nonstring-operation",
            "transient-active-engine",
            "transient-volume",
            "transient-loop",
            "missing-engine-map",
            "empty-identifier",
            "invalid-volume-step",
            "wrong-companion-hint-type",
        ] {
            let raw = fixture(&format!("invalid-{name}.json"))["capabilities"].clone();
            assert!(
                ReceiverCapabilities::from_json(raw.clone()).is_err(),
                "{name}"
            );
            let auth: ReceiverFrame = serde_json::from_value(json!({"type":"auth_response","success":true,"players":["legacy"],"capabilities":raw})).unwrap();
            // Unrecognized versions are legacy (None). Recognized v1 failures are the malformed marker.
            let unrecognized = matches!(
                name,
                "missing-version" | "string-version" | "zero-version" | "oversized-version"
            );
            assert!(
                matches!(
                    &auth,
                    ReceiverFrame::AuthResponse { success: true, capabilities: None, players, .. }
                        if unrecognized && players == &["legacy"]
                ) || matches!(
                    &auth,
                    ReceiverFrame::AuthResponse { success: true, capabilities: Some(descriptor), players, .. }
                        if !unrecognized && descriptor.is_malformed() && players == &["legacy"]
                ),
                "{name}"
            );
        }
    }

    #[test]
    fn identifier_map_set_and_utf8_byte_bounds_are_enforced() {
        for count in [16, 17] {
            let surfaces: Map<String, Value> = (0..count)
                .map(|id| (format!("s{id}"), json!({"engines":{}})))
                .collect();
            assert_eq!(
                ReceiverCapabilities::from_json(json!({"version":1,"surfaces":surfaces})).is_ok(),
                count == 16
            );
            let engines: Map<String, Value> =
                (0..count).map(|id| (format!("e{id}"), json!({}))).collect();
            assert_eq!(
                ReceiverCapabilities::from_json(
                    json!({"version":1,"surfaces":{"p":{"engines":engines}}})
                )
                .is_ok(),
                count == 16
            );
        }
        for count in [64, 65] {
            let ids: Vec<String> = (0..count).map(|id| format!("op{id}")).collect();
            assert_eq!(
                ReceiverCapabilities::from_json(
                    json!({"version":1,"surfaces":{"p":{"engines":{"e":{"text":ids}}}}})
                )
                .is_ok(),
                count == 64
            );
            assert_eq!(ReceiverCapabilities::from_json(json!({"version":1,"surfaces":{ "p":{"engines":{ "e":{"text":["界".repeat(count)]}}}}})).is_ok(), count == 64);
        }
        let ids: Vec<String> = (0..64)
            .map(|id| format!("{}{id}", "界".repeat(30)))
            .collect();
        let engines: Map<String, Value> = (0..16)
            .map(|id| (format!("e{id}"), json!({"text":ids})))
            .collect();
        let large = json!({"version":1,"surfaces":{"p":{"engines":engines}}});
        assert!(serde_json::to_vec(&large).unwrap().len() > MAX_DESCRIPTOR_BYTES);
        assert!(ReceiverCapabilities::from_json(large.clone()).is_err());
        let auth: ReceiverFrame = serde_json::from_value(
            json!({"type":"auth_response","success":true,"capabilities":large}),
        )
        .unwrap();
        assert!(matches!(
            &auth,
            ReceiverFrame::AuthResponse {
                capabilities: Some(descriptor),
                ..
            } if descriptor.is_malformed()
        ));
    }

    #[test]
    fn descriptor_debug_and_errors_never_expose_supplied_values() {
        let descriptor = ReceiverCapabilities::from_json(
            json!({"version":1,"surfaces":{"PRIVATE_ID":{"engines":{}}}}),
        )
        .unwrap();
        assert!(!format!("{descriptor:?}").contains("PRIVATE_ID"));
        let error = ReceiverCapabilities::from_json(
            json!({"version":1,"surfaces":{"PRIVATE_ID":{"secret":"PRIVATE_CONTENT"}}}),
        )
        .unwrap_err();
        assert!(!error.contains("PRIVATE"));
    }

    fn decode_live(name: &str) -> CapabilitiesFrame {
        CapabilitiesFrame::from_value(fixture(name))
    }

    #[test]
    fn live_fixtures_decode_to_the_documented_outcomes() {
        assert_eq!(
            decode_live("live-update-v1.json"),
            CapabilitiesFrame::Update(CapabilitiesUpdate {
                revision: 1,
                descriptor: LiveDescriptor::Valid(
                    ReceiverCapabilities::from_json(json!({"version":1,"surfaces":{}})).unwrap()
                ),
            })
        );
        assert_eq!(
            decode_live("live-update-future.json"),
            CapabilitiesFrame::Update(CapabilitiesUpdate {
                revision: 2,
                descriptor: LiveDescriptor::UnknownVersion,
            })
        );
        // A recognized v1 that fails validation still advances ordering, and fails closed.
        assert_eq!(
            decode_live("invalid-live-malformed-v1.json"),
            CapabilitiesFrame::Update(CapabilitiesUpdate {
                revision: 1,
                descriptor: LiveDescriptor::Malformed,
            })
        );
    }

    #[test]
    fn invalid_live_envelopes_and_revisions_are_dropped_not_advanced() {
        for name in [
            "invalid-live-fractional-revision.json",
            "invalid-live-large-revision.json",
            "invalid-live-string-revision.json",
            "invalid-live-zero-revision.json",
        ] {
            assert_eq!(decode_live(name), CapabilitiesFrame::Invalid, "{name}");
        }
    }

    #[test]
    fn revision_bounds_are_exactly_one_through_two_to_the_fifty_three_minus_one() {
        let at = |revision: Value| {
            CapabilitiesFrame::from_value(json!({
                "type": "receiver_capabilities",
                "revision": revision,
                "capabilities": {"version": 1, "surfaces": {}}
            }))
        };
        assert!(matches!(at(json!(1)), CapabilitiesFrame::Update(_)));
        assert!(matches!(
            at(json!(MAX_LIVE_REVISION)),
            CapabilitiesFrame::Update(_)
        ));
        assert_eq!(at(json!(MAX_LIVE_REVISION + 1)), CapabilitiesFrame::Invalid);
        assert_eq!(at(json!(0)), CapabilitiesFrame::Invalid);
        assert_eq!(at(json!(-1)), CapabilitiesFrame::Invalid);
        assert!(
            matches!(at(json!(1.0)), CapabilitiesFrame::Update(_)),
            "integral float"
        );
        assert_eq!(
            at(json!(1.5)),
            CapabilitiesFrame::Invalid,
            "fractional float"
        );
        assert_eq!(at(json!("1")), CapabilitiesFrame::Invalid);
        assert_eq!(at(Value::Null), CapabilitiesFrame::Invalid);
    }

    #[test]
    fn missing_or_non_object_capabilities_are_invalid_envelopes() {
        assert_eq!(
            CapabilitiesFrame::from_value(json!({"revision": 1})),
            CapabilitiesFrame::Invalid
        );
        assert_eq!(
            CapabilitiesFrame::from_value(json!({"revision": 1, "capabilities": []})),
            CapabilitiesFrame::Invalid
        );
    }

    #[test]
    fn oversized_descriptors_are_bounded_by_their_version() {
        let padding = "x".repeat(MAX_DESCRIPTOR_BYTES);
        // A recognized v1 that is too large is malformed: it advances and fails closed.
        assert!(matches!(
            CapabilitiesFrame::from_value(json!({
                "revision": 3,
                "capabilities": {"version": 1, "surfaces": {}, "padding": padding}
            })),
            CapabilitiesFrame::Update(CapabilitiesUpdate {
                revision: 3,
                descriptor: LiveDescriptor::Malformed,
            })
        ));
        // An oversized opaque future shape is dropped without advancing ordering.
        assert_eq!(
            CapabilitiesFrame::from_value(json!({
                "revision": 4,
                "capabilities": {"version": 2, "padding": padding}
            })),
            CapabilitiesFrame::Invalid
        );
    }

    #[test]
    fn subscribe_builder_matches_the_live_subscribe_fixture() {
        let sent =
            serde_json::to_value(crate::playbridge::receiver_capabilities_subscribe()).unwrap();
        assert_eq!(sent, fixture("live-subscribe.json"));
        // The builder sends an empty payload only, so the fixture with extra fields is not what
        // a sender produces.
        assert_ne!(sent, fixture("invalid-live-subscribe-payload.json"));
    }

    #[test]
    fn receiver_frame_decodes_live_fixtures_through_the_frame_type() {
        let frame: crate::playbridge::ReceiverFrame =
            serde_json::from_value(fixture("live-update-v1.json")).unwrap();
        assert!(matches!(
            frame,
            crate::playbridge::ReceiverFrame::LiveCapabilities(CapabilitiesFrame::Update(_))
        ));
        let invalid: crate::playbridge::ReceiverFrame =
            serde_json::from_value(fixture("invalid-live-string-revision.json")).unwrap();
        assert!(matches!(
            invalid,
            crate::playbridge::ReceiverFrame::LiveCapabilities(CapabilitiesFrame::Invalid)
        ));
    }

    #[test]
    fn live_frames_never_render_their_descriptor_in_debug_output() {
        let frame = decode_live("live-update-v1.json");
        assert!(!format!("{frame:?}").contains("surfaces"));
        assert!(serde_json::to_value(&frame).is_err(), "receive-only");
    }

    #[test]
    fn revision_spellings_follow_the_parsed_number_rule() {
        let at = |text: &str| {
            let revision: Value = serde_json::from_str(text).unwrap();
            CapabilitiesFrame::from_value(json!({
                "type": "receiver_capabilities",
                "revision": revision,
                "capabilities": {"version": 1, "surfaces": {}}
            }))
        };
        for accepted in ["1", "1.0", "1e0", "9007199254740991", "9007199254740991.0"] {
            assert!(
                matches!(at(accepted), CapabilitiesFrame::Update(_)),
                "{accepted}"
            );
        }
        // Binary floating point rounds this to 2^53 - 1, which is accepted by design.
        assert!(matches!(
            at("9007199254740991.1"),
            CapabilitiesFrame::Update(_)
        ));
        for rejected in ["1.5", "9007199254740992", "\"1\"", "0", "-1", "0.0"] {
            assert_eq!(at(rejected), CapabilitiesFrame::Invalid, "{rejected}");
        }
    }
}
