//! Reqwest (+ optional FFmpeg AVIO) origin fetch — Docker / Desktop / CLI default.

use super::{UpstreamConnectFuture, UpstreamFetcher, UpstreamResponse};
use axum::body::Body;
use axum::http::{HeaderMap, HeaderName, HeaderValue, StatusCode};
use reqwest::Client;
use std::collections::HashMap;
use std::str::FromStr;
use tokio_stream::wrappers::ReceiverStream;
use tracing::warn;

pub struct ReqwestUpstreamFetcher {
    client: Client,
    ffmpeg_path: Option<String>,
}

impl ReqwestUpstreamFetcher {
    pub fn new(ffmpeg_path: Option<String>) -> Self {
        // Live HLS masters and multi-hop CDNs routinely exceed a few seconds.
        // The previous 6s budget caused flaky 500s on Via phone (no AVIO fallback).
        let client = Client::builder()
            .timeout(std::time::Duration::from_secs(30))
            .connect_timeout(std::time::Duration::from_secs(15))
            .redirect(reqwest::redirect::Policy::limited(10))
            .danger_accept_invalid_certs(true)
            .build()
            .unwrap_or_else(|_| Client::new());

        Self {
            client,
            ffmpeg_path,
        }
    }

    async fn response_to_upstream(resp: reqwest::Response) -> Result<UpstreamResponse, String> {
        let status = resp.status();
        let mut out_headers = HeaderMap::new();

        for (k, v) in resp.headers() {
            let lower = k.as_str().to_lowercase();
            // Include cache policy headers so the segment cache can honor
            // no-store / no-cache / private / Vary / max-age.
            if matches!(
                lower.as_str(),
                "content-range"
                    | "accept-ranges"
                    | "content-type"
                    | "cache-control"
                    | "vary"
                    | "expires"
                    | "age"
                    | "date"
            ) {
                out_headers.insert(k.clone(), v.clone());
            }
        }

        if let Some(cl) = resp.content_length() {
            let is_compressed = resp
                .headers()
                .get("content-encoding")
                .and_then(|v| v.to_str().ok())
                .map(|v| v != "identity")
                .unwrap_or(false);

            if !is_compressed {
                if let Ok(hv) = HeaderValue::from_str(&cl.to_string()) {
                    out_headers.insert("content-length", hv);
                }
            }
        }

        let stream = resp.bytes_stream();
        let body = Body::from_stream(stream);

        Ok(UpstreamResponse {
            status,
            headers: out_headers,
            body,
        })
    }

    async fn connect_inner(
        &self,
        url: &str,
        headers: &HashMap<String, String>,
    ) -> Result<UpstreamResponse, String> {
        let mut req = self.client.get(url);
        for (k, v) in headers {
            if let (Ok(h_name), Ok(h_val)) = (HeaderName::from_str(k), HeaderValue::from_str(v)) {
                req = req.header(h_name, h_val);
            }
        }

        match req.send().await {
            Ok(resp) if resp.status().is_success() => {
                return Self::response_to_upstream(resp).await;
            }
            Ok(resp) => {
                let status = resp.status();
                let content_type = resp
                    .headers()
                    .get(reqwest::header::CONTENT_TYPE)
                    .and_then(|v| v.to_str().ok())
                    .unwrap_or("")
                    .to_ascii_lowercase();
                // Some origins return playable playlist bodies with odd status codes;
                // accept them when Content-Type looks like HLS before AVIO.
                if content_type.contains("mpegurl")
                    || content_type.contains("m3u8")
                    || content_type.contains("apple")
                {
                    warn!(
                        "[stream-proxy] accepting HLS Content-Type despite HTTP {} for {}",
                        status, url
                    );
                    if let Ok(upstream) = Self::response_to_upstream(resp).await {
                        return Ok(upstream);
                    }
                } else {
                    warn!(
                        "[stream-proxy] reqwest returned HTTP {} (ct={}), attempting FFmpeg AVIO fallback for {}",
                        status, content_type, url
                    );
                    drop(resp);
                }
            }
            Err(e) => {
                warn!(
                    "[stream-proxy] reqwest error: {}, attempting FFmpeg AVIO fallback for {}",
                    e, url
                );
            }
        }

        self.try_avio(url, headers).await
    }

    async fn try_avio(
        &self,
        url: &str,
        headers: &HashMap<String, String>,
    ) -> Result<UpstreamResponse, String> {
        #[cfg(feature = "upstream-avio")]
        {
            use crate::avio::get_avio_client;

            let avio_client = get_avio_client(self.ffmpeg_path.as_deref());
            if let Some(rx) = avio_client.spawn_stream(url.to_string(), headers.clone(), 30) {
                let mut out_headers = HeaderMap::new();

                let has_range = headers.keys().any(|k| k.eq_ignore_ascii_case("range"));
                if has_range {
                    if let Some(r_val) = headers.get("range").or_else(|| headers.get("Range")) {
                        if let Ok(hv) = HeaderValue::from_str(r_val) {
                            out_headers.insert("content-range", hv);
                        }
                    }
                }

                let status = if has_range {
                    StatusCode::PARTIAL_CONTENT
                } else {
                    StatusCode::OK
                };

                let stream = ReceiverStream::new(rx);
                let body = Body::from_stream(stream);

                return Ok(UpstreamResponse {
                    status,
                    headers: out_headers,
                    body,
                });
            }
        }

        #[cfg(not(feature = "upstream-avio"))]
        {
            let _ = (&self.ffmpeg_path, url, headers);
        }

        Err(
            "Failed to connect upstream via HTTP or FFmpeg AVIO (phone builds often lack FFmpeg — ensure Referer/User-Agent headers and that the origin is reachable from the phone)"
                .to_string(),
        )
    }
}

impl UpstreamFetcher for ReqwestUpstreamFetcher {
    fn connect<'a>(
        &'a self,
        url: &'a str,
        headers: &'a HashMap<String, String>,
    ) -> UpstreamConnectFuture<'a> {
        Box::pin(async move { self.connect_inner(url, headers).await })
    }
}
