//! Host-provided origin fetch (Android JNI → HttpURLConnection).
//!
//! Callback registration and streaming land in a follow-up PR. This stub keeps
//! `upstream-jni`-only builds compiling and fails clearly at runtime.

use super::{UpstreamConnectFuture, UpstreamFetcher};
use std::collections::HashMap;

/// Placeholder until Kotlin `HttpURLConnection` callbacks are wired.
pub struct JniUpstreamFetcher;

impl JniUpstreamFetcher {
    pub fn new() -> Self {
        Self
    }
}

impl Default for JniUpstreamFetcher {
    fn default() -> Self {
        Self::new()
    }
}

impl UpstreamFetcher for JniUpstreamFetcher {
    fn connect<'a>(
        &'a self,
        _url: &'a str,
        _headers: &'a HashMap<String, String>,
    ) -> UpstreamConnectFuture<'a> {
        Box::pin(async move {
            Err(
                "JNI upstream fetcher is not registered yet (Android host must install \
                 HttpURLConnection callbacks before ProxyServer starts)"
                    .to_string(),
            )
        })
    }
}
