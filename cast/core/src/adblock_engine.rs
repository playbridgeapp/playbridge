// SPDX-License-Identifier: MPL-2.0
//! Versioned, opt-in ad-blocking engine. No network I/O, resources or diagnostics.
use std::collections::{BTreeMap, HashSet};

use adblock::{
    Engine, FilterSet,
    content_blocking::{CbRule, CbRuleEquivalent, CbType},
    lists::{ParseOptions, ParsedLine, parse_filter},
    request::Request,
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

/// Stable machine-readable error codes. Never contains input or library error text.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum AdblockError {
    InvalidInput,
    InvalidConfig,
    InvalidCommand,
    ListLimit,
    EngineLimit,
    UnknownEngine,
    RegistryUnavailable,
    EngineUnavailable,
    EnginePanic,
    InvalidRequest,
    RequestTooLarge,
    UnsupportedRequestType,
    UnsupportedRequestMethod,
    InvalidCache,
    InvalidCacheChecksum,
    InvalidSerializedEngine,
    CacheManifestMismatch,
    InvalidChunkLimit,
    ExceptionsExceedChunkCapacity,
    ConversionFailed,
    EncodingFailed,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Config {
    pub lists: Vec<String>,
}

/// Increment when serialization, preprocessing or engine semantics change.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct CacheManifest {
    pub format: String,
    pub profile: Vec<String>,
    pub list_hashes: Vec<String>,
}
impl CacheManifest {
    pub fn for_lists(lists: &[String]) -> Self {
        Self {
            format: "adblock-0.13.3/pb-1".into(),
            profile: ["ublock", "safari", "mobile"].map(String::from).to_vec(),
            list_hashes: lists
                .iter()
                .map(|s| format!("{:x}", Sha256::digest(s.as_bytes())))
                .collect(),
        }
    }
}
#[derive(Serialize, Deserialize)]
struct CachedEngine {
    manifest: CacheManifest,
    checksum: String,
}
pub struct AdblockEngine(Engine, CacheManifest);
impl AdblockEngine {
    pub fn new(config: Config) -> Self {
        let manifest = CacheManifest::for_lists(&config.lists);
        let mut filters = FilterSet::new(false);
        for list in config.lists {
            filters.add_filter_list(list, ParseOptions::default());
        }
        Self(Engine::new_with_filter_set(filters), manifest)
    }
    pub fn deserialize(bytes: &[u8], expected: CacheManifest) -> Result<Self, AdblockError> {
        if bytes.len() < 8 || &bytes[..4] != b"PBA1" {
            return Err(AdblockError::InvalidCache);
        }
        let header_len = u32::from_le_bytes(
            bytes[4..8]
                .try_into()
                .map_err(|_| AdblockError::InvalidCache)?,
        ) as usize;
        if header_len > 16384 || header_len > bytes.len() - 8 {
            return Err(AdblockError::InvalidCache);
        }
        let cached: CachedEngine = serde_json::from_slice(&bytes[8..8 + header_len])
            .map_err(|_| AdblockError::InvalidCache)?;
        let payload = &bytes[8 + header_len..];
        if cached.manifest != expected {
            return Err(AdblockError::CacheManifestMismatch);
        }
        if cached.checksum != format!("{:x}", Sha256::digest(payload)) {
            return Err(AdblockError::InvalidCacheChecksum);
        }
        let mut engine = Engine::new_with_filter_set(FilterSet::new(false));
        engine
            .deserialize(payload)
            .map_err(|_| AdblockError::InvalidSerializedEngine)?;
        Ok(Self(engine, expected))
    }
    pub fn serialize(&self) -> Vec<u8> {
        let payload = self.0.serialize();
        let header = serde_json::to_vec(&CachedEngine {
            manifest: self.1.clone(),
            checksum: format!("{:x}", Sha256::digest(&payload)),
        })
        .expect("cache envelope is serializable");
        let mut output = b"PBA1".to_vec();
        output.extend((header.len() as u32).to_le_bytes());
        output.extend(header);
        output.extend(payload);
        output
    }
    pub fn check(
        &self,
        url: &str,
        source: &str,
        typ: &str,
        method: &str,
    ) -> Result<Value, AdblockError> {
        // Upstream currently has no Popup request type; do not silently map it to Other.
        if ![
            "beacon",
            "csp_report",
            "document",
            "main_frame",
            "font",
            "image",
            "imageset",
            "media",
            "object",
            "object_subrequest",
            "ping",
            "script",
            "stylesheet",
            "sub_frame",
            "subdocument",
            "websocket",
            "xmlhttprequest",
            "fetch",
            "other",
        ]
        .contains(&typ)
        {
            return Err(AdblockError::UnsupportedRequestType);
        }
        if method.parse::<adblock::request::RequestMethod>().is_err() {
            return Err(AdblockError::UnsupportedRequestMethod);
        }
        if url.len() > 16384 || source.len() > 16384 {
            return Err(AdblockError::RequestTooLarge);
        }
        if method.len() > 32 || !method.bytes().all(|b| b.is_ascii_alphabetic() || b == b'-') {
            return Err(AdblockError::UnsupportedRequestMethod);
        }
        let request =
            Request::new(url, source, typ, method).map_err(|_| AdblockError::InvalidRequest)?;
        let result = self.0.check_network_request(&request);
        Ok(
            json!({"matched":result.should_block(), "exception":result.exception.is_some(),
            "important":result.important, "redirect":result.redirect}),
        )
    }
    pub fn cosmetic(&self, url: &str) -> Value {
        let result = self.0.url_cosmetic_resources(url);
        json!({"hide_selectors":result.hide_selectors, "procedural_actions":result.procedural_actions,
            "exceptions":result.exceptions, "injected_script":result.injected_script, "generichide":result.generichide})
    }
    pub fn hidden(
        &self,
        classes: Vec<String>,
        ids: Vec<String>,
        exceptions: HashSet<String>,
    ) -> Vec<String> {
        self.0.hidden_class_id_selectors(classes, ids, &exceptions)
    }
}

#[derive(Serialize)]
pub struct Conversion {
    pub chunks: Vec<Vec<CbRule>>,
    pub source_lines: usize,
    pub converted_filters: usize,
    /// Pre-replication record count; duplicate source filters are not deduplicated.
    pub unique_rules: usize,
    pub emitted_rules: usize,
    pub disabled_by_badfilter: usize,
    pub failures: BTreeMap<String, usize>,
}
fn reason(error: &impl std::fmt::Debug) -> String {
    format!("{error:?}")
        .chars()
        .take_while(|c| c.is_ascii_alphanumeric() || *c == '_')
        .collect()
}
/// Keep every ignore-previous-rules exception in every chunk: WebKit exceptions
/// do not cross WKContentRuleList boundaries. Fail instead of silently dropping them.
pub fn content_blocking(lists: &[String], limit: usize) -> Result<Conversion, AdblockError> {
    if limit == 0 || limit > 140_000 {
        return Err(AdblockError::InvalidChunkLimit);
    }
    let opts = ParseOptions::default();
    let mut set = FilterSet::new(true);
    let mut disabled = HashSet::new();
    for list in lists {
        set.add_filter_list(list.clone(), opts);
        for line in list.lines() {
            if let Ok(ParsedLine::Network(f)) = parse_filter(line, true, opts)
                && f.is_badfilter()
            {
                disabled.insert(f.get_id());
            }
        }
    }
    let mut failures = BTreeMap::new();
    let mut source_lines = 0;
    let mut disabled_by_badfilter = 0;
    for line in lists.iter().flat_map(|s| s.lines()).map(str::trim) {
        if line.is_empty() || line.starts_with('!') || line.starts_with('[') {
            continue;
        }
        source_lines += 1;
        let parsed = match parse_filter(line, true, opts) {
            Ok(v) => v,
            Err(e) => {
                let detail = match e {
                    adblock::lists::FilterParseError::Network(e) => {
                        format!("parse.Network.{}", reason(&e))
                    }
                    adblock::lists::FilterParseError::Cosmetic(e) => {
                        format!("parse.Cosmetic.{}", reason(&e))
                    }
                    e => format!("parse.{}", reason(&e)),
                };
                *failures.entry(detail).or_default() += 1;
                continue;
            }
        };
        if let ParsedLine::Network(f) = &parsed
            && (f.is_badfilter() || disabled.contains(&f.get_id()))
        {
            disabled_by_badfilter += 1;
            continue;
        }
        if let Err(e) = CbRuleEquivalent::try_from(parsed) {
            *failures
                .entry(format!("convert.{}", reason(&e)))
                .or_default() += 1;
        }
    }
    let (rules, used) = set
        .into_content_blocking()
        .map_err(|_| AdblockError::ConversionFailed)?;
    let unique_rules = rules.len();
    let (exceptions, blocks): (Vec<_>, Vec<_>) = rules
        .into_iter()
        .partition(|r| r.action.typ == CbType::IgnorePreviousRules);
    if exceptions.len() >= limit {
        return Err(AdblockError::ExceptionsExceedChunkCapacity);
    }
    let capacity = limit - exceptions.len();
    let mut chunks: Vec<Vec<CbRule>> = blocks
        .chunks(capacity)
        .map(|block| {
            let mut chunk = block.to_vec();
            chunk.extend(exceptions.clone());
            chunk
        })
        .collect();
    if chunks.is_empty() && !exceptions.is_empty() {
        chunks.push(exceptions);
    }
    let emitted_rules = chunks.iter().map(Vec::len).sum();
    Ok(Conversion {
        chunks,
        source_lines,
        converted_filters: used.len(),
        unique_rules,
        emitted_rules,
        disabled_by_badfilter,
        failures,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn engine(text: &str) -> AdblockEngine {
        AdblockEngine::new(Config {
            lists: vec![text.into()],
        })
    }
    #[test]
    fn network_options_and_serialization() {
        let e = engine(
            "||ads.test^$third-party\n||hard.test^$important\n@@||hard.test^\n||off.test^\n||off.test^$badfilter\n||doc.test^$doc\n||pop.test^$popup\n||scope.test^$domain=a.test|~b.a.test",
        );
        let matched = |u, s, t| {
            e.check(u, s, t, "GET").unwrap()["matched"]
                .as_bool()
                .unwrap()
        };
        assert!(matched("https://ads.test/a", "https://a.test", "script"));
        assert!(!matched("https://ads.test/a", "https://ads.test", "script"));
        assert!(
            e.check("https://hard.test/a", "https://a.test", "script", "GET")
                .unwrap()["important"]
                == true
        );
        assert!(!matched("https://off.test/a", "https://a.test", "script"));
        assert!(matched("https://doc.test/a", "https://a.test", "document"));
        assert_eq!(
            e.check("https://pop.test/a", "https://a.test", "popup", "GET")
                .unwrap_err(),
            AdblockError::UnsupportedRequestType
        );
        assert!(matched("https://scope.test/a", "https://a.test", "script"));
        assert!(!matched(
            "https://scope.test/a",
            "https://b.a.test",
            "script"
        ));
        let restored = AdblockEngine::deserialize(&e.serialize(), e.1.clone()).unwrap();
        assert_eq!(
            e.check("https://ads.test/a", "https://a.test", "script", "GET"),
            restored.check("https://ads.test/a", "https://a.test", "script", "GET")
        );
        assert!(AdblockEngine::deserialize(b"bad", e.1.clone()).is_err());
    }
    #[test]
    fn manifest_and_corrupt_cache_invalidation() {
        let e = engine("||ads.test^");
        let bytes = e.serialize();
        let mut changed = e.1.clone();
        changed.format.push('2');
        assert!(AdblockEngine::deserialize(&bytes, changed).is_err());
        let mut changed = e.1.clone();
        changed.profile.push("firefox".into());
        assert!(AdblockEngine::deserialize(&bytes, changed).is_err());
        assert!(
            AdblockEngine::deserialize(&bytes, CacheManifest::for_lists(&["||new.test^".into()]))
                .is_err()
        );
        let mut corrupt = bytes.clone();
        *corrupt.last_mut().unwrap() ^= 1;
        assert!(AdblockEngine::deserialize(&corrupt, e.1.clone()).is_err());
        assert!(AdblockEngine::deserialize(&bytes[..7], e.1.clone()).is_err());
    }
    #[test]
    fn detector_keeps_paths_types_exceptions_and_source() {
        let e = engine(
            "||cdn.test/ads/$script\n||cdn.test/private/$media,domain=a.test|~safe.a.test\n||cdn.test/beacon$xmlhttprequest\n@@||cdn.test/private/allowed$media\n||method.test^$method=post",
        );
        let check = |url, source, typ, method| {
            e.check(url, source, typ, method).unwrap()["matched"] == true
        };
        assert!(!check(
            "https://cdn.test/movie.mp4",
            "https://a.test",
            "media",
            "GET"
        ));
        assert!(check(
            "https://cdn.test/ads/ad.js",
            "https://a.test",
            "script",
            "GET"
        ));
        assert!(!check(
            "https://cdn.test/ads/movie.mp4",
            "https://a.test",
            "media",
            "GET"
        ));
        assert!(check(
            "https://cdn.test/private/movie.mp4",
            "https://a.test",
            "media",
            "GET"
        ));
        assert!(!check(
            "https://cdn.test/private/movie.mp4",
            "https://safe.a.test",
            "media",
            "GET"
        ));
        assert!(!check(
            "https://cdn.test/private/allowed",
            "https://a.test",
            "media",
            "GET"
        ));
        assert!(check(
            "https://cdn.test/beacon",
            "https://a.test",
            "xmlhttprequest",
            "GET"
        ));
        assert!(!check(
            "https://cdn.test/beacon",
            "https://a.test",
            "media",
            "GET"
        ));
        assert!(check(
            "https://method.test/a",
            "https://a.test",
            "script",
            "POST"
        ));
        assert!(!check(
            "https://method.test/a",
            "https://a.test",
            "script",
            "GET"
        ));
        assert_eq!(
            e.check("https://a.test", "https://b.test", "media", "GET\n")
                .unwrap_err(),
            AdblockError::UnsupportedRequestMethod
        );
    }
    #[test]
    fn cosmetic_exceptions_and_generic_selectors() {
        let e =
            engine("##.ad\nexample.test#@#.ad\nexample.test##.local\n@@||nohide.test^$generichide");
        let c = e.cosmetic("https://example.test");
        let exceptions = serde_json::from_value(c["exceptions"].clone()).unwrap();
        assert!(
            !e.hidden(vec!["ad".into()], vec![], exceptions)
                .contains(&".ad".into())
        );
        assert!(
            c["hide_selectors"]
                .as_array()
                .unwrap()
                .contains(&json!(".local"))
        );
        assert!(e.cosmetic("https://nohide.test")["generichide"] == true);
    }
    #[test]
    fn conversion_replicates_exceptions_and_counts_failures() {
        let input = vec![
            "||a.test^\n||b.test^\n||c.test^\n@@||a.test/safe\nexample.test##+js(noop)".into(),
        ];
        let converted = content_blocking(&input, 4).unwrap();
        assert!(converted.chunks.len() > 1);
        assert!(converted.chunks.iter().all(|c| c.len() <= 4));
        assert!(converted.chunks.iter().all(|c| {
            c.iter()
                .any(|r| r.action.typ == CbType::IgnorePreviousRules)
        }));
        assert!(
            converted
                .failures
                .contains_key("convert.ScriptletInjectionsNotSupported")
        );
        assert!(content_blocking(&input, 0).is_err());
    }
}
