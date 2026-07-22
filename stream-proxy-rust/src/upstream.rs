use crate::avio::get_avio_client;
use axum::body::Body;
use axum::http::{HeaderMap, HeaderName, HeaderValue, StatusCode};
use bytes::Bytes;
use reqwest::Client;
use std::collections::HashMap;
use std::str::FromStr;
use tokio_stream::wrappers::ReceiverStream;
use tracing::warn;

pub struct UpstreamResponse {
    pub status: StatusCode,
    pub headers: HeaderMap,
    pub body: Body,
}

pub struct ConnectionEngine {
    client: Client,
    ffmpeg_path: Option<String>,
}

impl ConnectionEngine {
    pub fn new(ffmpeg_path: Option<String>) -> Self {
        let client = Client::builder()
            .timeout(std::time::Duration::from_secs(6))
            .danger_accept_invalid_certs(true)
            .build()
            .unwrap_or_else(|_| Client::new());

        Self {
            client,
            ffmpeg_path,
        }
    }

    pub async fn connect_upstream(
        &self,
        url: &str,
        headers: &HashMap<String, String>,
    ) -> Result<UpstreamResponse, String> {
        // 1. Try standard reqwest Client first
        let mut req = self.client.get(url);
        for (k, v) in headers {
            if let (Ok(h_name), Ok(h_val)) = (HeaderName::from_str(k), HeaderValue::from_str(v)) {
                req = req.header(h_name, h_val);
            }
        }

        match req.send().await {
            Ok(resp) if resp.status().is_success() => {
                let status = resp.status();
                let mut out_headers = HeaderMap::new();

                for (k, v) in resp.headers() {
                    let lower = k.as_str().to_lowercase();
                    if lower == "content-range"
                        || lower == "accept-ranges"
                        || lower == "content-type"
                    {
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

                return Ok(UpstreamResponse {
                    status,
                    headers: out_headers,
                    body,
                });
            }
            Ok(resp) => {
                warn!("[stream-proxy] reqwest returned HTTP {}, attempting FFmpeg AVIO fallback for {}", resp.status(), url);
            }
            Err(e) => {
                warn!(
                    "[stream-proxy] reqwest error: {}, attempting FFmpeg AVIO fallback for {}",
                    e, url
                );
            }
        }

        // 2. Fall back to FFmpeg AvioClient FFI
        let avio_client = get_avio_client(self.ffmpeg_path.as_deref());
        if let Some(rx) = avio_client.spawn_stream(url.to_string(), headers.clone(), 15) {
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

        Err("Failed to connect upstream via HTTP or FFmpeg AVIO".to_string())
    }

    pub async fn fetch_url_bytes(
        &self,
        url: &str,
        headers: &HashMap<String, String>,
    ) -> Result<Bytes, String> {
        let resp = self.connect_upstream(url, headers).await?;
        let bytes = axum::body::to_bytes(resp.body, usize::MAX)
            .await
            .map_err(|e| format!("Failed reading bytes: {}", e))?;
        Ok(bytes)
    }
}

pub fn filter_upstream_headers(
    session_headers: &HashMap<String, String>,
    incoming_headers: &HeaderMap,
    target_url: &str,
    session_id: &str,
) -> HashMap<String, String> {
    let mut out = HashMap::new();

    for (k, v) in session_headers {
        let lower = k.to_lowercase();
        if !should_skip_header(&lower) {
            out.insert(k.clone(), v.clone());
        }
    }

    let lower_url = target_url.to_lowercase();
    let is_hls_segment = lower_url.contains(".ts") || lower_url.contains(".m4s");
    let should_forward_range = session_id == "play" || !is_hls_segment;

    if should_forward_range {
        if let Some(range_val) = incoming_headers.get("range").and_then(|v| v.to_str().ok()) {
            out.insert("range".to_string(), range_val.to_string());
        }
    }

    out
}

fn should_skip_header(lower_key: &str) -> bool {
    const SKIP: &[&str] = &[
        "host",
        "connection",
        "content-length",
        "accept-encoding",
        "range",
    ];
    SKIP.contains(&lower_key) || lower_key.starts_with(':')
}
