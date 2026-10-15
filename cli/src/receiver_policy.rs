//! Decides which native PlayBridge controls the CLI may send, from the receiver descriptor.
//!
//! Rules (protocol/docs/RECEIVER_CAPABILITIES.md):
//! - No descriptor, or an unknown version: `Legacy`, which keeps the CLI's existing behaviour.
//! - Valid v1: declared operation sets are authoritative. Empty or missing sets mean unsupported,
//!   and nothing is filled in from the OS, the host, or another surface or engine.
//! - Recognized v1 that failed validation: `Malformed`, which fails closed for native controls.
//!
//! The CLI does not parse the active engine, so the player surface uses the operations that
//! every player engine declares. Unknown identifiers are ignored, and unknown volume scopes
//! do not form a route.

use std::collections::{BTreeMap, BTreeSet};

use playbridge_cast_core::playbridge::{
    CapabilitiesFrame, LiveDescriptor, RECEIVER_CAPABILITIES_FEATURE, ReceiverCapabilities,
    SenderFrame, receiver_capabilities_subscribe,
};
use serde_json::{Map, Value, json};

type Operations = BTreeSet<String>;
type Families = BTreeMap<String, Operations>;

/// A native control the CLI wants to send, named by its contract family and operation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum Control {
    Transport(String),
    Key(String),
    Volume(String),
    Setting(String),
    Queue(String),
    Browser(String),
}

impl Control {
    fn volume(operation: &str) -> Self {
        Self::Volume(operation.to_owned())
    }

    fn queue(operation: &str) -> Self {
        Self::Queue(operation.to_owned())
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
struct Surface {
    /// Operations that every engine on the surface declares. Used when the active engine is unknown.
    common: Families,
    /// Operations that any engine declares. Used only for explicit browser launch, which may pick
    /// an advertised engine while idle.
    any: Families,
    engines: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct VolumeRoute {
    scope: String,
    operations: Operations,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(crate) struct Declared {
    surfaces: BTreeMap<String, Surface>,
    volume: Option<VolumeRoute>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum ReceiverPolicy {
    Legacy,
    Declared(Declared),
    Malformed,
}

impl ReceiverPolicy {
    pub(crate) fn from_descriptor(descriptor: Option<&ReceiverCapabilities>) -> Self {
        match descriptor {
            None => Self::Legacy,
            Some(descriptor) if descriptor.is_malformed() => Self::Malformed,
            Some(descriptor) => Self::Declared(Declared::from_json(descriptor.as_json())),
        }
    }

    pub(crate) fn allows(&self, control: &Control) -> bool {
        match self {
            Self::Legacy => true,
            Self::Malformed => false,
            Self::Declared(declared) => declared.allows(control),
        }
    }

    /// Returns the user-facing error for an unsupported control, naming the receiver rule that
    /// rejected it.
    pub(crate) fn require(&self, control: &Control, what: &str) -> Result<(), String> {
        if self.allows(control) {
            return Ok(());
        }
        match self {
            Self::Malformed => Err(format!(
                "{what} is not supported by this receiver: its capabilities are invalid"
            )),
            _ => Err(format!("{what} is not supported by this receiver")),
        }
    }

    /// Label for volume controls. The app route is named as app volume, never as TV volume.
    pub(crate) fn volume_label(&self) -> &'static str {
        match self.volume_scope().as_deref() {
            Some("app") => "App volume",
            _ => "Volume",
        }
    }

    /// The scope of the relative-step volume route, when the receiver declares one.
    pub(crate) fn volume_scope(&self) -> Option<String> {
        match self {
            Self::Declared(declared) => declared
                .volume
                .as_ref()
                .filter(|route| route.operations.contains("relative_step"))
                .map(|route| route.scope.clone()),
            _ => None,
        }
    }

    /// Requirement for a receiver command forwarded by name. Unknown actions fail closed once a
    /// descriptor is present, because the receiver has not declared them.
    pub(crate) fn require_receiver_action(
        &self,
        action: &str,
        payload: Option<&Value>,
    ) -> Result<(), String> {
        if matches!(self, Self::Legacy) {
            return Ok(());
        }
        // A read of queue state changes nothing, so it is not gated.
        if action == "queue_query" {
            return Ok(());
        }
        let key = payload
            .and_then(|value| value.get("key"))
            .and_then(Value::as_str);
        let (control, what) = match action {
            "queue_add" => (Control::queue("append"), "Adding to the queue".to_owned()),
            "playlist_jump" => {
                let by_item = payload.is_some_and(|value| value.get("itemId").is_some());
                let operation = if by_item { "jump_item" } else { "jump_index" };
                (Control::queue(operation), "Jumping in the queue".to_owned())
            }
            "queue_remove" => (
                Control::queue("remove"),
                "Removing from the queue".to_owned(),
            ),
            "queue_move" => (Control::queue("move"), "Moving in the queue".to_owned()),
            "queue_clear" => (Control::queue("clear"), "Clearing the queue".to_owned()),
            "remote" => match key {
                Some("volume_up" | "volume_down") => (
                    Control::volume("relative_step"),
                    self.volume_label().to_owned(),
                ),
                Some("mute") => (Control::volume("mute_toggle"), "Mute".to_owned()),
                Some(key) => (Control::Key(key.to_owned()), format!("The {key} key")),
                None => return Err("remote command requires a key".into()),
            },
            "browser" => (
                Control::Browser("launch".into()),
                "Opening a browser".to_owned(),
            ),
            "browser_control"
                if payload
                    .and_then(|value| value.get("action"))
                    .and_then(Value::as_str)
                    == Some("refresh") =>
            {
                (
                    Control::Browser("reload".into()),
                    "Refreshing the browser".to_owned(),
                )
            }
            _ => return Err(format!("{action} is not supported by this receiver")),
        };
        self.require(&control, &what)
    }

    /// Compact diagnostic summary of surfaces, engines and volume scope. Legacy has no summary,
    /// so its JSON output does not change.
    pub(crate) fn summary(&self) -> Option<Value> {
        match self {
            Self::Legacy => None,
            Self::Malformed => Some(json!({ "policy": "malformed" })),
            Self::Declared(declared) => {
                let surfaces: Map<String, Value> = declared
                    .surfaces
                    .iter()
                    .map(|(id, surface)| (id.clone(), json!(surface.engines)))
                    .collect();
                Some(json!({
                    "policy": "declared",
                    "surfaces": surfaces,
                    "volumeScope": declared.volume.as_ref().map(|route| route.scope.as_str()),
                }))
            }
        }
    }

    /// Text form of the summary for the dashboard.
    pub(crate) fn describe(&self) -> String {
        match self {
            Self::Legacy => "no capability descriptor; using legacy controls".into(),
            Self::Malformed => "capability descriptor is invalid; native controls disabled".into(),
            Self::Declared(declared) => {
                let mut parts: Vec<String> = declared
                    .surfaces
                    .iter()
                    .map(|(id, surface)| {
                        if surface.engines.is_empty() {
                            id.clone()
                        } else {
                            format!("{id} {}", surface.engines.join(", "))
                        }
                    })
                    .collect();
                if let Some(route) = &declared.volume {
                    parts.push(format!("volume {}", route.scope));
                }
                if parts.is_empty() {
                    "declares no native controls".into()
                } else {
                    parts.join(" · ")
                }
            }
        }
    }
}

/// Live descriptor updates for one authenticated socket (step 9b).
///
/// The subscription is sent once, only when the authenticated metadata offers the feature. A
/// fresh value is created for each authenticated socket, so the watermark starts at the auth
/// baseline (0) and no update from an earlier session can apply.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(crate) struct LiveUpdates {
    subscribed: bool,
    watermark: u64,
}

/// What an incoming live frame did to the session.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum LiveOutcome {
    /// A valid or malformed update replaced the policy.
    Replaced,
    /// An unknown-version update kept the policy and advanced the ordering watermark.
    Advanced,
    /// Pre-subscription, invalid, duplicate, or out-of-order frames change nothing.
    Dropped,
}

impl LiveUpdates {
    /// The explicit opt-in for this socket, or `None` when it was already sent or the
    /// authenticated metadata does not offer the feature.
    pub(crate) fn subscribe_frame(&mut self, features: &[String]) -> Option<SenderFrame> {
        if self.subscribed
            || !features
                .iter()
                .any(|feature| feature == RECEIVER_CAPABILITIES_FEATURE)
        {
            return None;
        }
        self.subscribed = true;
        Some(receiver_capabilities_subscribe())
    }

    /// Applies one decoded frame. A replacement is complete: the new descriptor replaces the old
    /// one, and nothing from the previous descriptor survives.
    pub(crate) fn accept(
        &mut self,
        policy: &mut ReceiverPolicy,
        frame: &CapabilitiesFrame,
    ) -> LiveOutcome {
        let CapabilitiesFrame::Update(update) = frame else {
            return LiveOutcome::Dropped;
        };
        if !self.subscribed || update.revision <= self.watermark {
            return LiveOutcome::Dropped;
        }
        self.watermark = update.revision;
        match &update.descriptor {
            LiveDescriptor::Valid(descriptor) => {
                *policy = ReceiverPolicy::from_descriptor(Some(descriptor));
                LiveOutcome::Replaced
            }
            LiveDescriptor::Malformed => {
                *policy = ReceiverPolicy::Malformed;
                LiveOutcome::Replaced
            }
            LiveDescriptor::UnknownVersion => LiveOutcome::Advanced,
        }
    }
}

impl Declared {
    fn from_json(root: &Value) -> Self {
        let surfaces = root
            .get("surfaces")
            .and_then(Value::as_object)
            .map(|surfaces| {
                surfaces
                    .iter()
                    .map(|(id, value)| (id.clone(), Surface::from_json(value)))
                    .collect()
            })
            .unwrap_or_default();
        let volume = root
            .get("volume")
            .and_then(Value::as_object)
            .and_then(volume_route);
        Self { surfaces, volume }
    }

    fn allows(&self, control: &Control) -> bool {
        match control {
            Control::Transport(operation) => self.common_has("player", "transport", operation),
            Control::Setting(operation) => self.common_has("player", "settings", operation),
            Control::Queue(operation) => self.common_has("player", "queue", operation),
            // Key input goes to whichever surface is active. Every present surface must declare
            // the key, because the active surface is unknown to the CLI.
            Control::Key(operation) => {
                !self.surfaces.is_empty()
                    && self
                        .surfaces
                        .values()
                        .all(|surface| family_has(&surface.common, "keys", operation))
            }
            Control::Volume(operation) => self
                .volume
                .as_ref()
                .is_some_and(|route| route.operations.contains(operation)),
            Control::Browser(operation) => self.surfaces.get("browser").is_some_and(|surface| {
                // Only an idle launch may pick an engine. Everything else needs every engine.
                let families = if operation == "launch" {
                    &surface.any
                } else {
                    &surface.common
                };
                family_has(families, "browser", operation)
            }),
        }
    }

    fn common_has(&self, surface: &str, family: &str, operation: &str) -> bool {
        self.surfaces
            .get(surface)
            .is_some_and(|surface| family_has(&surface.common, family, operation))
    }
}

impl Surface {
    fn from_json(value: &Value) -> Self {
        let engines = value
            .get("engines")
            .and_then(Value::as_object)
            .map(|engines| {
                engines
                    .iter()
                    .map(|(id, engine)| (id.clone(), families(engine.as_object())))
                    .collect::<Vec<(String, Families)>>()
            })
            .unwrap_or_default();
        let sets: Vec<&Families> = engines.iter().map(|(_, families)| families).collect();
        Self {
            common: intersect(&sets),
            any: union(&sets),
            engines: engines.iter().map(|(id, _)| id.clone()).collect(),
        }
    }
}

fn volume_route(volume: &Map<String, Value>) -> Option<VolumeRoute> {
    let scope = volume.get("scope").and_then(Value::as_str)?;
    // Unknown scopes are ignored, so they never form a route.
    if !matches!(scope, "system" | "app") {
        return None;
    }
    Some(VolumeRoute {
        scope: scope.to_owned(),
        operations: operations(volume.get("operations")),
    })
}

fn families(engine: Option<&Map<String, Value>>) -> Families {
    engine
        .map(|engine| {
            engine
                .iter()
                .map(|(family, value)| (family.clone(), operations(Some(value))))
                .collect()
        })
        .unwrap_or_default()
}

fn operations(value: Option<&Value>) -> Operations {
    value
        .and_then(Value::as_array)
        .map(|items| {
            items
                .iter()
                .filter_map(Value::as_str)
                .map(str::to_owned)
                .collect()
        })
        .unwrap_or_default()
}

fn family_has(families: &Families, family: &str, operation: &str) -> bool {
    families
        .get(family)
        .is_some_and(|operations| operations.contains(operation))
}

/// Operations every engine declares. An engine missing a family contributes nothing to it.
fn intersect(engines: &[&Families]) -> Families {
    let names: BTreeSet<&String> = engines
        .iter()
        .flat_map(|families| families.keys())
        .collect();
    names
        .into_iter()
        .map(|name| {
            let common = engines
                .iter()
                .map(|families| families.get(name).cloned().unwrap_or_default())
                .reduce(|left, right| left.intersection(&right).cloned().collect())
                .unwrap_or_default();
            (name.clone(), common)
        })
        .collect()
}

/// Operations any engine declares.
fn union(engines: &[&Families]) -> Families {
    let mut merged: Families = BTreeMap::new();
    for families in engines {
        for (name, operations) in families.iter() {
            merged
                .entry(name.clone())
                .or_default()
                .extend(operations.iter().cloned());
        }
    }
    merged
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    fn descriptor(value: Value) -> ReceiverCapabilities {
        ReceiverCapabilities::from_json(value).expect("valid descriptor")
    }

    pub(crate) fn policy(value: Value) -> ReceiverPolicy {
        ReceiverPolicy::from_descriptor(Some(&descriptor(value)))
    }

    fn transport(op: &str) -> Control {
        Control::Transport(op.to_owned())
    }

    fn volume(op: &str) -> Control {
        Control::volume(op)
    }

    fn setting(op: &str) -> Control {
        Control::Setting(op.to_owned())
    }

    fn queue(op: &str) -> Control {
        Control::queue(op)
    }

    fn key(op: &str) -> Control {
        Control::Key(op.to_owned())
    }

    fn browser(op: &str) -> Control {
        Control::Browser(op.to_owned())
    }

    /// The real Android TV descriptor from tv/android/player `TvCapabilities.kt` (both engines,
    /// webview and gecko browsers, system volume).
    pub(crate) fn android_tv() -> ReceiverPolicy {
        let player = json!({
            "transport": ["play","pause","toggle","stop","seek_absolute","seek_relative"],
            "keys": ["dpad_up","dpad_down","dpad_left","dpad_right","dpad_center","back","home"],
            "settings": ["loop","engine","speed","scaling","quality","audio_boost","subtitle_offset"],
            "imageTransforms": ["pan","scroll","zoom","rotate","anchor","reset"],
            "tracks": ["audio_select","subtitle_select","subtitle_url_add","subtitle_resource_add"],
            "queue": ["replace","append","jump_index","jump_item","remove","move","clear"],
            "reporting": ["status","context","tracks","playlist_status","player_settings"]
        });
        let browser = json!({
            "transport": ["toggle","seek_absolute","seek_relative"],
            "keys": ["dpad_up","dpad_down","dpad_left","dpad_right","dpad_center","back","home","key_enter"],
            "pointer": ["move","click","down","up","scroll","zoom"],
            "text": ["replace","enter"],
            "browser": ["launch","forward","reload","cycle_source","unmute","fullscreen","manage_user_scripts","manage_user_agent"],
            "reporting": ["context","status","fullscreen"]
        });
        policy(json!({
            "version": 1,
            "surfaces": {
                "player": {"engines": {"exo": player.clone(), "mpv": player}},
                "browser": {"engines": {"webview": browser.clone(), "gecko": browser}}
            },
            "volume": {"scope": "system", "operations": ["relative_step", "mute_toggle"]},
            "host": {"osFamily": "android", "deviceClass": "tv"},
            "companionHints": ["android_tv_remote_v2"]
        }))
    }

    /// The real Apple TV descriptor from `TvCapabilities.swift` (app volume, Loop and Engine only).
    pub(crate) fn apple_tv() -> ReceiverPolicy {
        let player = json!({
            "transport": ["play","pause","toggle","stop","seek_absolute","seek_relative"],
            "keys": ["dpad_up","dpad_down","dpad_left","dpad_right","dpad_center","back","home"],
            "settings": ["loop","engine"],
            "tracks": ["audio_select","subtitle_select","subtitle_url_add","subtitle_resource_add"],
            "queue": ["replace","append","jump_index","jump_item","remove","move","clear"],
            "reporting": ["status","context","tracks","playlist_status","player_settings"]
        });
        policy(json!({
            "version": 1,
            "surfaces": {"player": {"engines": {"avplayer": player.clone(), "mpv": player}}},
            "volume": {
                "scope": "app",
                "operations": ["relative_step","absolute","mute_toggle","level_report","mute_report"],
                "step": 0.05
            },
            "host": {"osFamily": "tvos", "deviceClass": "tv"}
        }))
    }

    /// The real Desktop descriptor from `desktop/lib/receiver_capabilities.dart` (macOS: system
    /// relative steps with app fallback; no keys, mute, loop or speed).
    pub(crate) fn desktop() -> ReceiverPolicy {
        policy(json!({
            "version": 1,
            "surfaces": {"player": {"engines": {"internal_mpv": {
                "transport": ["play","pause","toggle","stop","seek_absolute","seek_relative"],
                "imageTransforms": ["pan","scroll","zoom","rotate","anchor","reset"],
                "tracks": ["audio_select","subtitle_select","subtitle_url_add","subtitle_resource_add"],
                "queue": ["replace","append","jump_index","jump_item","remove","move","clear"],
                "reporting": ["status","context","tracks","playlist_status"]
            }}}},
            "volume": {"scope": "system", "operations": ["relative_step"], "fallbackScope": "app"},
            "host": {"osFamily": "macos", "deviceClass": "desktop"}
        }))
    }

    /// The real CLI-as-receiver descriptor from `cli/src/receive.rs` `receiver_capabilities()`.
    pub(crate) fn cli_receiver() -> ReceiverPolicy {
        policy(json!({
            "version": 1,
            "surfaces": {"player": {"engines": {"internal_mpv": {
                "transport": ["play","pause","toggle","stop","seek_absolute","seek_relative"],
                "queue": ["replace","append","jump_index"],
                "reporting": ["status","context","playlist_status"]
            }}}},
            "host": {"osFamily": "macos", "deviceClass": "cli"}
        }))
    }

    #[test]
    fn absent_descriptor_is_legacy_and_allows_everything() {
        let legacy = ReceiverPolicy::from_descriptor(None);
        assert_eq!(legacy, ReceiverPolicy::Legacy);
        assert!(legacy.allows(&transport("seek_relative")));
        assert!(legacy.allows(&setting("audio_boost")));
        assert!(legacy.allows(&key("home")));
        assert!(legacy.allows(&browser("launch")));
        assert_eq!(legacy.volume_scope(), None);
        assert_eq!(legacy.summary(), None);
        assert!(legacy.require_receiver_action("browser", None).is_ok());
    }

    fn auth_capabilities(capabilities: Value) -> Option<ReceiverCapabilities> {
        let frame: playbridge_cast_core::playbridge::ReceiverFrame = serde_json::from_value(
            json!({"type": "auth_response", "success": true, "capabilities": capabilities}),
        )
        .expect("auth frame decodes");
        let playbridge_cast_core::playbridge::ReceiverFrame::AuthResponse { capabilities, .. } =
            frame
        else {
            panic!("expected auth response");
        };
        capabilities
    }

    #[test]
    fn unknown_version_decodes_to_legacy_through_the_real_sender_decoder() {
        for capabilities in [
            json!({"version": 2, "surfaces": []}),
            json!({"version": "1"}),
        ] {
            assert_eq!(
                ReceiverPolicy::from_descriptor(auth_capabilities(capabilities).as_ref()),
                ReceiverPolicy::Legacy
            );
        }
    }

    #[test]
    fn recognized_but_broken_v1_decodes_to_malformed_and_fails_closed() {
        // A bad operation shape is a recognized v1 that fails validation.
        let capabilities = auth_capabilities(
            json!({"version": 1, "surfaces": {"player": {"engines": {"exo": {"transport": "pause"}}}}}),
        );
        let policy = ReceiverPolicy::from_descriptor(capabilities.as_ref());
        assert_eq!(policy, ReceiverPolicy::Malformed);
        assert!(!policy.allows(&transport("pause")));
        assert!(!policy.allows(&volume("relative_step")));
        assert!(!policy.allows(&setting("loop")));
        assert!(!policy.allows(&key("home")));
        assert!(policy.require_receiver_action("queue_add", None).is_err());
        assert_eq!(policy.summary(), Some(json!({"policy": "malformed"})));
        assert_eq!(policy.volume_scope(), None);
        let error = policy.require(&transport("pause"), "Pause").unwrap_err();
        assert!(error.contains("not supported by this receiver"), "{error}");
        assert!(error.contains("invalid"), "{error}");
    }

    #[test]
    fn empty_v1_exposes_no_native_controls_and_is_authoritative() {
        let empty = policy(json!({"version": 1, "surfaces": {}}));
        assert!(!empty.allows(&transport("play")));
        assert!(!empty.allows(&volume("relative_step")));
        assert!(!empty.allows(&key("home")));
        assert!(!empty.allows(&browser("launch")));
        assert_eq!(empty.volume_scope(), None);
        assert_eq!(empty.describe(), "declares no native controls");
    }

    #[test]
    fn empty_engine_sets_are_authoritative_even_when_others_declare_them() {
        let mixed = policy(json!({
            "version": 1,
            "surfaces": {"player": {"engines": {
                "exo": {"transport": ["pause", "play"], "settings": ["loop"]},
                "mpv": {"transport": ["pause", "play"], "settings": []}
            }}}
        }));
        // Unknown active engine: only operations every engine declares are allowed.
        assert!(mixed.allows(&transport("pause")));
        assert!(!mixed.allows(&setting("loop")));
    }

    #[test]
    fn partial_sets_restrict_without_os_or_profile_fallback() {
        let partial = policy(json!({
            "version": 1,
            "surfaces": {"player": {"engines": {"exo": {"transport": ["play"]}}}},
            "host": {"osFamily": "android", "deviceClass": "tv"}
        }));
        assert!(partial.allows(&transport("play")));
        assert!(!partial.allows(&transport("pause")));
        assert!(!partial.allows(&key("dpad_up")));
        assert!(!partial.allows(&setting("loop")));
    }

    #[test]
    fn unknown_identifiers_are_ignored_and_unknown_scopes_form_no_route() {
        let unknown = policy(json!({
            "version": 1,
            "surfaces": {"Player": {"engines": {"EXO": {"transport": ["PAUSE", "future_op"]}}}},
            "volume": {"scope": "hdmi", "operations": ["relative_step"]}
        }));
        assert!(
            !unknown.allows(&transport("pause")),
            "identifiers are case-sensitive"
        );
        assert!(!unknown.allows(&volume("relative_step")));
        assert_eq!(unknown.volume_scope(), None);
    }

    #[test]
    fn a_surface_with_no_engines_declares_nothing() {
        let no_engines = policy(json!({"version": 1, "surfaces": {"player": {"engines": {}}}}));
        assert!(!no_engines.allows(&transport("play")));
        assert!(!no_engines.allows(&queue("append")));
    }

    #[test]
    fn android_tv_real_descriptor() {
        let tv = android_tv();
        for op in [
            "play",
            "pause",
            "toggle",
            "stop",
            "seek_absolute",
            "seek_relative",
        ] {
            assert!(tv.allows(&transport(op)), "{op}");
        }
        assert!(tv.allows(&setting("loop")));
        assert!(tv.allows(&setting("speed")));
        assert!(tv.allows(&setting("audio_boost")));
        assert!(tv.allows(&queue("append")));
        assert!(tv.allows(&queue("jump_item")));
        assert!(tv.allows(&queue("clear")));
        // System volume: steps and mute, but no absolute route.
        assert!(tv.allows(&volume("relative_step")));
        assert!(tv.allows(&volume("mute_toggle")));
        assert!(!tv.allows(&volume("absolute")));
        assert_eq!(tv.volume_scope().as_deref(), Some("system"));
        assert_eq!(tv.volume_label(), "Volume");
        // Keys: dpad and Home are on both surfaces; key_enter is only on the browser.
        assert!(tv.allows(&key("dpad_up")));
        assert!(tv.allows(&key("home")));
        assert!(!tv.allows(&key("key_enter")));
        assert!(tv.allows(&browser("launch")));
        assert!(tv.allows(&browser("reload")));
        assert!(!tv.allows(&browser("manage_remote")));
        assert!(tv.describe().contains("player exo, mpv"));
        assert!(tv.describe().contains("browser gecko, webview"));
    }

    #[test]
    fn apple_tv_real_descriptor_is_app_volume_with_loop_and_engine_only() {
        let apple = apple_tv();
        assert!(apple.allows(&transport("seek_absolute")));
        assert!(apple.allows(&key("dpad_up")));
        assert!(apple.allows(&key("back")));
        assert!(apple.allows(&key("home")));
        assert!(apple.allows(&setting("loop")));
        assert!(!apple.allows(&setting("speed")));
        assert!(!apple.allows(&setting("audio_boost")));
        assert!(apple.allows(&volume("relative_step")));
        assert!(apple.allows(&volume("mute_toggle")));
        assert_eq!(apple.volume_scope().as_deref(), Some("app"));
        assert_eq!(apple.volume_label(), "App volume");
        assert!(!apple.allows(&browser("launch")));
        assert!(
            apple
                .require(&volume("relative_step"), "App volume")
                .is_ok()
        );
    }

    #[test]
    fn desktop_real_descriptor_has_system_steps_and_no_keys_mute_or_loop() {
        let desktop = desktop();
        assert!(desktop.allows(&transport("pause")));
        assert!(desktop.allows(&transport("seek_relative")));
        assert!(desktop.allows(&queue("jump_index")));
        assert!(desktop.allows(&volume("relative_step")));
        assert!(!desktop.allows(&volume("mute_toggle")));
        assert!(!desktop.allows(&volume("absolute")));
        assert!(!desktop.allows(&key("dpad_up")));
        assert!(!desktop.allows(&setting("loop")));
        assert!(!desktop.allows(&setting("speed")));
        assert!(!desktop.allows(&browser("launch")));
        assert_eq!(desktop.volume_scope().as_deref(), Some("system"));
        assert_eq!(desktop.volume_label(), "Volume");
    }

    #[test]
    fn cli_receiver_real_descriptor_has_transport_and_appending_queue_only() {
        let cli = cli_receiver();
        assert!(cli.allows(&transport("pause")));
        assert!(cli.allows(&queue("append")));
        assert!(cli.allows(&queue("jump_index")));
        assert!(!cli.allows(&queue("remove")));
        assert!(!cli.allows(&queue("clear")));
        assert!(!cli.allows(&volume("relative_step")));
        assert!(!cli.allows(&setting("loop")));
        assert_eq!(cli.volume_scope(), None);
    }

    #[test]
    fn receiver_actions_map_to_the_declared_operation() {
        let tv = android_tv();
        assert!(
            tv.require_receiver_action("remote", Some(&json!({"key":"dpad_up"})))
                .is_ok()
        );
        assert!(
            tv.require_receiver_action("remote", Some(&json!({"key":"key_enter"})))
                .is_err()
        );
        assert!(
            tv.require_receiver_action("remote", Some(&json!({"key":"volume_up"})))
                .is_ok()
        );
        assert!(
            tv.require_receiver_action("playlist_jump", Some(&json!({"index":2})))
                .is_ok()
        );
        assert!(
            tv.require_receiver_action("browser_control", Some(&json!({"action":"refresh"})))
                .is_ok()
        );
        assert!(tv.require_receiver_action("queue_query", None).is_ok());

        let cli = cli_receiver();
        assert!(cli.require_receiver_action("queue_add", None).is_ok());
        assert!(cli.require_receiver_action("queue_remove", None).is_err());
        assert!(cli.require_receiver_action("queue_clear", None).is_err());
        let error = cli
            .require_receiver_action("queue_remove", None)
            .unwrap_err();
        assert_eq!(
            error,
            "Removing from the queue is not supported by this receiver"
        );

        let desktop = desktop();
        let error = desktop
            .require_receiver_action("remote", Some(&json!({"key":"mute"})))
            .unwrap_err();
        assert!(error.starts_with("Mute is not supported"), "{error}");
        assert!(desktop.require_receiver_action("browser", None).is_err());
        assert!(
            desktop
                .require_receiver_action("made_up_action", None)
                .is_err()
        );
    }

    #[test]
    fn summary_names_surfaces_engines_and_volume_scope() {
        assert_eq!(
            apple_tv().summary(),
            Some(json!({
                "policy": "declared",
                "surfaces": {"player": ["avplayer", "mpv"]},
                "volumeScope": "app"
            }))
        );
        assert_eq!(desktop().describe(), "player internal_mpv · volume system");
    }

    /// A live frame exactly as it arrives on the wire, decoded through the frame type.
    fn live_frame(text: &str) -> CapabilitiesFrame {
        let frame: playbridge_cast_core::playbridge::ReceiverFrame =
            serde_json::from_str(text).expect("live frames decode through the frame type");
        match frame {
            playbridge_cast_core::playbridge::ReceiverFrame::LiveCapabilities(frame) => frame,
            other => panic!("expected a live capabilities frame, got {other:?}"),
        }
    }

    fn fixture_text(name: &str) -> &'static str {
        match name {
            "live-subscribe.json" => include_str!(
                "../../protocol/tests/fixtures/receiver-capabilities/live-subscribe.json"
            ),
            "live-update-v1.json" => include_str!(
                "../../protocol/tests/fixtures/receiver-capabilities/live-update-v1.json"
            ),
            "live-update-future.json" => include_str!(
                "../../protocol/tests/fixtures/receiver-capabilities/live-update-future.json"
            ),
            "invalid-live-malformed-v1.json" => include_str!(
                "../../protocol/tests/fixtures/receiver-capabilities/invalid-live-malformed-v1.json"
            ),
            "invalid-live-fractional-revision.json" => include_str!(
                "../../protocol/tests/fixtures/receiver-capabilities/invalid-live-fractional-revision.json"
            ),
            "invalid-live-large-revision.json" => include_str!(
                "../../protocol/tests/fixtures/receiver-capabilities/invalid-live-large-revision.json"
            ),
            "invalid-live-string-revision.json" => include_str!(
                "../../protocol/tests/fixtures/receiver-capabilities/invalid-live-string-revision.json"
            ),
            "invalid-live-zero-revision.json" => include_str!(
                "../../protocol/tests/fixtures/receiver-capabilities/invalid-live-zero-revision.json"
            ),
            other => panic!("unregistered live fixture {other}"),
        }
    }

    fn offered() -> Vec<String> {
        vec![RECEIVER_CAPABILITIES_FEATURE.to_owned()]
    }

    /// A live frame with an inline descriptor, for cases the fixtures do not spell out.
    fn update(revision: u64, descriptor: Value) -> CapabilitiesFrame {
        live_frame(
            &json!({
                "type": "receiver_capabilities",
                "revision": revision,
                "capabilities": descriptor,
            })
            .to_string(),
        )
    }

    fn subscribed_live(features: &[String]) -> LiveUpdates {
        let mut live = LiveUpdates::default();
        assert!(live.subscribe_frame(features).is_some());
        live
    }

    fn player_with(operations: Value) -> Value {
        json!({
            "version": 1,
            "surfaces": {"player": {"engines": {"exo": operations}}},
            "volume": {"scope": "system", "operations": ["relative_step"]}
        })
    }

    #[test]
    fn subscription_is_sent_once_and_only_when_the_metadata_offers_it() {
        let mut without = LiveUpdates::default();
        assert!(without.subscribe_frame(&["queue_crud_v1".into()]).is_none());
        // Without the offer, the socket never subscribes, so no live frame applies.
        let mut policy = tests::android_tv();
        assert_eq!(
            without.accept(
                &mut policy,
                &live_frame(fixture_text("live-update-v1.json"))
            ),
            LiveOutcome::Dropped
        );

        let mut live = LiveUpdates::default();
        let frame = live.subscribe_frame(&offered()).expect("offered");
        assert_eq!(
            serde_json::to_value(frame).unwrap(),
            serde_json::from_str::<Value>(fixture_text("live-subscribe.json")).unwrap()
        );
        // A second call on the same socket sends nothing.
        assert!(live.subscribe_frame(&offered()).is_none());
    }

    #[test]
    fn frames_before_subscription_are_dropped_and_change_nothing() {
        let mut live = LiveUpdates::default();
        let mut policy = tests::android_tv();
        let before = policy.clone();
        assert_eq!(
            live.accept(
                &mut policy,
                &live_frame(fixture_text("live-update-v1.json"))
            ),
            LiveOutcome::Dropped
        );
        assert_eq!(policy, before);
    }

    #[test]
    fn a_valid_empty_replacement_removes_every_native_control() {
        let mut live = subscribed_live(&offered());
        let mut policy = tests::android_tv();
        assert_eq!(
            live.accept(
                &mut policy,
                &live_frame(fixture_text("live-update-v1.json"))
            ),
            LiveOutcome::Replaced
        );
        assert!(!policy.allows(&Control::Transport("pause".into())));
        assert!(!policy.allows(&Control::Volume("relative_step".into())));
        assert!(!policy.allows(&Control::Key("dpad_up".into())));
        assert_eq!(policy.volume_scope(), None);
        assert_eq!(
            policy.summary(),
            Some(json!({"policy": "declared", "surfaces": {}, "volumeScope": null}))
        );
    }

    #[test]
    fn each_replacement_is_complete_and_restores_nothing_removed() {
        let mut live = subscribed_live(&offered());
        let mut policy = tests::android_tv();
        assert!(policy.allows(&Control::Setting("loop".into())));

        // Desktop-like replacement: the loop control and the dpad keys are gone.
        let narrow = player_with(json!({"transport": ["pause", "play"]}));
        assert_eq!(
            live.accept(&mut policy, &update(1, narrow)),
            LiveOutcome::Replaced
        );
        assert!(!policy.allows(&Control::Setting("loop".into())));
        assert!(!policy.allows(&Control::Key("dpad_up".into())));
        assert!(policy.allows(&Control::Transport("pause".into())));

        // Removing everything, then adding back only queue operations: nothing from before returns.
        let empty = json!({"version": 1, "surfaces": {}});
        assert_eq!(
            live.accept(&mut policy, &update(2, empty)),
            LiveOutcome::Replaced
        );
        assert!(!policy.allows(&Control::Transport("pause".into())));
        let queue_only = player_with(json!({"queue": ["append"]}));
        assert_eq!(
            live.accept(&mut policy, &update(3, queue_only)),
            LiveOutcome::Replaced
        );
        assert!(policy.allows(&Control::Queue("append".into())));
        assert!(!policy.allows(&Control::Transport("pause".into())));
        assert!(!policy.allows(&Control::Setting("loop".into())));
    }

    #[test]
    fn duplicate_and_out_of_order_revisions_are_dropped() {
        let mut live = subscribed_live(&offered());
        let mut policy = tests::android_tv();
        assert_eq!(
            live.accept(
                &mut policy,
                &update(2, player_with(json!({"transport": ["pause"]})))
            ),
            LiveOutcome::Replaced
        );
        let after_two = policy.clone();
        // An exact repeat and a lower revision both change nothing.
        assert_eq!(
            live.accept(
                &mut policy,
                &update(2, player_with(json!({"transport": ["play"]})))
            ),
            LiveOutcome::Dropped
        );
        assert_eq!(
            live.accept(
                &mut policy,
                &live_frame(fixture_text("live-update-v1.json"))
            ),
            LiveOutcome::Dropped
        );
        assert_eq!(policy, after_two);
        assert_eq!(
            live.accept(
                &mut policy,
                &update(3, player_with(json!({"transport": ["play"]})))
            ),
            LiveOutcome::Replaced
        );
    }

    #[test]
    fn a_newer_unknown_version_keeps_the_descriptor_and_blocks_a_later_rollback() {
        let mut live = subscribed_live(&offered());
        let mut policy = tests::android_tv();
        let before = policy.clone();
        assert_eq!(
            live.accept(
                &mut policy,
                &live_frame(fixture_text("live-update-future.json"))
            ),
            LiveOutcome::Advanced
        );
        assert_eq!(policy, before, "the current descriptor is kept");
        // A delayed older v1 at revision 1 cannot roll back past the revision 2 watermark.
        assert_eq!(
            live.accept(
                &mut policy,
                &live_frame(fixture_text("live-update-v1.json"))
            ),
            LiveOutcome::Dropped
        );
        assert_eq!(policy, before);
        assert_eq!(
            live.accept(
                &mut policy,
                &update(3, player_with(json!({"transport": ["pause"]})))
            ),
            LiveOutcome::Replaced
        );
    }

    #[test]
    fn a_malformed_v1_fails_closed_and_a_later_valid_update_recovers() {
        let mut live = subscribed_live(&offered());
        let mut policy = tests::android_tv();
        assert_eq!(
            live.accept(
                &mut policy,
                &live_frame(fixture_text("invalid-live-malformed-v1.json"))
            ),
            LiveOutcome::Replaced
        );
        assert_eq!(policy, ReceiverPolicy::Malformed);
        assert!(!policy.allows(&Control::Transport("pause".into())));
        assert!(!policy.allows(&Control::Volume("relative_step".into())));
        assert_eq!(
            live.accept(
                &mut policy,
                &update(2, player_with(json!({"transport": ["pause"]})))
            ),
            LiveOutcome::Replaced
        );
        assert!(policy.allows(&Control::Transport("pause".into())));
    }

    #[test]
    fn invalid_envelopes_and_revisions_never_advance_ordering() {
        let mut live = subscribed_live(&offered());
        let mut policy = tests::android_tv();
        let before = policy.clone();
        for name in [
            "invalid-live-fractional-revision.json",
            "invalid-live-large-revision.json",
            "invalid-live-string-revision.json",
            "invalid-live-zero-revision.json",
        ] {
            assert_eq!(
                live.accept(&mut policy, &live_frame(fixture_text(name))),
                LiveOutcome::Dropped,
                "{name}"
            );
        }
        assert_eq!(policy, before);
        // Because nothing advanced, revision 1 is still the next valid update.
        assert_eq!(
            live.accept(
                &mut policy,
                &update(1, player_with(json!({"transport": ["pause"]})))
            ),
            LiveOutcome::Replaced
        );
    }

    #[test]
    fn a_new_authenticated_socket_starts_at_the_auth_baseline() {
        let mut first = subscribed_live(&offered());
        let mut first_policy = tests::android_tv();
        assert_eq!(
            first.accept(
                &mut first_policy,
                &update(5, player_with(json!({"transport": ["pause"]})))
            ),
            LiveOutcome::Replaced
        );

        // Reconnect or switch receivers: the new socket has its own state, starting from the
        // policy its auth gave. Nothing from the old socket can restore its operations.
        let mut second = LiveUpdates::default();
        let mut second_policy = ReceiverPolicy::from_descriptor(None);
        assert_eq!(
            second.accept(
                &mut second_policy,
                &update(6, player_with(json!({"transport": ["pause"]})))
            ),
            LiveOutcome::Dropped,
            "no subscription on the new socket yet"
        );
        assert_eq!(second_policy, ReceiverPolicy::Legacy);
        let mut second = subscribed_live(&offered());
        // Revision 1 is the new socket's baseline, so a low revision from the old session is
        // only accepted here because it is the first frame on this socket.
        assert_eq!(
            second.accept(
                &mut second_policy,
                &update(1, player_with(json!({"transport": ["play"]})))
            ),
            LiveOutcome::Replaced
        );
    }
}
