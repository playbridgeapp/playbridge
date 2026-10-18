// SPDX-License-Identifier: MPL-2.0
//! PlayBridge-written scriptlet resources for `adblock-rust`.
//!
//! Every scriptlet in this module is original PlayBridge code licensed under MPL-2.0.
//! No uBlock Origin `scriptlets.js` or GPL resource code is copied, ported, paraphrased,
//! or bundled. Scriptlet names and aliases match standard filter-list conventions so
//! existing `##+js(...)` rules in enabled filter lists resolve cleanly.

use adblock::resources::{MimeType, PermissionMask, Resource, ResourceType};
use base64::{Engine as _, prelude::BASE64_STANDARD};

struct ScriptletSpec {
    name: &'static str,
    aliases: &'static [&'static str],
    source: &'static str,
}

const SCRIPTLETS: &[ScriptletSpec] = &[
    ScriptletSpec {
        name: "abort-on-property-read.js",
        aliases: &["aopr.js", "abort-on-property-read", "aopr"],
        source: include_str!("abort-on-property-read.js"),
    },
    ScriptletSpec {
        name: "abort-on-property-write.js",
        aliases: &["aopw.js", "abort-on-property-write", "aopw"],
        source: include_str!("abort-on-property-write.js"),
    },
    ScriptletSpec {
        name: "abort-current-script.js",
        aliases: &[
            "acs.js",
            "abort-current-inline-script.js",
            "acis.js",
            "abort-current-script",
            "acs",
            "abort-current-inline-script",
            "acis",
        ],
        source: include_str!("abort-current-script.js"),
    },
    ScriptletSpec {
        name: "set-constant.js",
        aliases: &["set.js", "set-constant", "set"],
        source: include_str!("set-constant.js"),
    },
    ScriptletSpec {
        name: "addEventListener-defuser.js",
        aliases: &[
            "aeld.js",
            "prevent-addEventListener.js",
            "addEventListener-defuser",
            "aeld",
            "prevent-addEventListener",
        ],
        source: include_str!("addEventListener-defuser.js"),
    },
    ScriptletSpec {
        name: "no-setTimeout-if.js",
        aliases: &[
            "nostif.js",
            "setTimeout-defuser.js",
            "prevent-setTimeout.js",
            "no-setTimeout-if",
            "nostif",
            "setTimeout-defuser",
            "prevent-setTimeout",
        ],
        source: include_str!("no-setTimeout-if.js"),
    },
    ScriptletSpec {
        name: "no-setInterval-if.js",
        aliases: &[
            "nosiif.js",
            "setInterval-defuser.js",
            "prevent-setInterval.js",
            "no-setInterval-if",
            "nosiif",
            "setInterval-defuser",
            "prevent-setInterval",
        ],
        source: include_str!("no-setInterval-if.js"),
    },
    ScriptletSpec {
        name: "no-window-open-if.js",
        aliases: &[
            "nowoif.js",
            "window.open-defuser.js",
            "prevent-window-open.js",
            "no-window-open-if",
            "nowoif",
            "window.open-defuser",
            "prevent-window-open",
        ],
        source: include_str!("no-window-open-if.js"),
    },
    ScriptletSpec {
        name: "json-prune.js",
        aliases: &["json-prune"],
        source: include_str!("json-prune.js"),
    },
    ScriptletSpec {
        name: "remove-attr.js",
        aliases: &["ra.js", "remove-attr", "ra"],
        source: include_str!("remove-attr.js"),
    },
    ScriptletSpec {
        name: "remove-class.js",
        aliases: &["rc.js", "remove-class", "rc"],
        source: include_str!("remove-class.js"),
    },
    ScriptletSpec {
        name: "prevent-fetch.js",
        aliases: &["no-fetch-if.js", "prevent-fetch", "no-fetch-if"],
        source: include_str!("prevent-fetch.js"),
    },
    ScriptletSpec {
        name: "prevent-xhr.js",
        aliases: &["no-xhr-if.js", "prevent-xhr", "no-xhr-if"],
        source: include_str!("prevent-xhr.js"),
    },
];

pub fn resources() -> Vec<Resource> {
    SCRIPTLETS
        .iter()
        .map(|spec| {
            // Strip leading line comments so `extract_function_name` in adblock-rust
            // matches `^function\s+([^\(\)\{\}\s]+)\s*\(` at the start of the content.
            let body: String = spec
                .source
                .lines()
                .skip_while(|line| {
                    let trimmed = line.trim();
                    trimmed.is_empty() || trimmed.starts_with("//")
                })
                .collect::<Vec<_>>()
                .join("\n");
            Resource {
                name: spec.name.to_string(),
                aliases: spec.aliases.iter().map(|s| (*s).to_string()).collect(),
                kind: ResourceType::Mime(MimeType::ApplicationJavascript),
                content: BASE64_STANDARD.encode(body.as_bytes()),
                dependencies: Vec::new(),
                permission: PermissionMask::default(),
            }
        })
        .collect()
}
