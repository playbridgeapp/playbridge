//! Reqwest (+ optional FFmpeg AVIO) origin fetch — Docker / Desktop / CLI default.

use super::{
    validate_http_destination, NetworkPolicy, UpstreamConnectFuture, UpstreamFetcher,
    UpstreamResponse,
};
use axum::body::Body;
use axum::http::{HeaderMap, HeaderName, HeaderValue, StatusCode};
use reqwest::Client;
use std::collections::HashMap;
use std::io;
use std::str::FromStr;
use std::sync::Arc;
use tokio_stream::wrappers::ReceiverStream;
use tracing::warn;

pub struct ReqwestUpstreamFetcher {
    client: Client,
    public_only_client: Option<Client>,
    local_network_client: Option<Client>,
    ffmpeg_path: Option<String>,
}

#[derive(Debug)]
struct PolicyDns {
    allow_private_network: bool,
}

impl reqwest::dns::Resolve for PolicyDns {
    fn resolve(&self, name: reqwest::dns::Name) -> reqwest::dns::Resolving {
        let host = name.as_str().to_owned();
        let allow_private_network = self.allow_private_network;
        Box::pin(async move {
            let lower = host.to_ascii_lowercase();
            if lower == "localhost"
                || lower.ends_with(".localhost")
                || (!allow_private_network && lower.ends_with(".local"))
            {
                return Err(dns_policy_error());
            }
            let addresses: Vec<_> = tokio::net::lookup_host((host.as_str(), 0))
                .await
                .map_err(|error| Box::new(error) as Box<dyn std::error::Error + Send + Sync>)?
                .collect();
            if addresses.is_empty()
                || addresses
                    .iter()
                    .any(|address| !super::is_allowed_address(address.ip(), allow_private_network))
            {
                return Err(dns_policy_error());
            }
            Ok(Box::new(addresses.into_iter()) as reqwest::dns::Addrs)
        })
    }
}

fn dns_policy_error() -> Box<dyn std::error::Error + Send + Sync> {
    Box::new(io::Error::new(
        io::ErrorKind::PermissionDenied,
        "local-network media permission is required",
    ))
}

impl ReqwestUpstreamFetcher {
    pub fn new(ffmpeg_path: Option<String>) -> Self {
        // Live HLS masters and multi-hop CDNs routinely exceed a few seconds.
        // The previous 6s budget caused flaky 500s on Via phone (no AVIO fallback).
        let client = Self::client_builder()
            .build()
            .unwrap_or_else(|_| Client::new());
        let public_only_client = Self::client_builder()
            .dns_resolver(Arc::new(PolicyDns {
                allow_private_network: false,
            }))
            .build()
            .ok();
        let local_network_client = Self::client_builder()
            .dns_resolver(Arc::new(PolicyDns {
                allow_private_network: true,
            }))
            .build()
            .ok();

        Self {
            client,
            public_only_client,
            local_network_client,
            ffmpeg_path,
        }
    }

    fn client_builder() -> reqwest::ClientBuilder {
        Client::builder()
            .timeout(std::time::Duration::from_secs(30))
            .connect_timeout(std::time::Duration::from_secs(15))
            .redirect(reqwest::redirect::Policy::none())
    }

    fn client_for_policy(
        &self,
        network_policy: Option<&NetworkPolicy>,
        url: &reqwest::Url,
    ) -> Result<&Client, String> {
        match network_policy {
            None => Ok(&self.client),
            Some(policy) if policy.allows_private_url(url) => self
                .local_network_client
                .as_ref()
                .ok_or_else(|| "local-network HTTP client is unavailable".to_string()),
            Some(_) => self
                .public_only_client
                .as_ref()
                .ok_or_else(|| "public-only HTTP client is unavailable".to_string()),
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
        network_policy: Option<NetworkPolicy>,
    ) -> Result<UpstreamResponse, String> {
        let initial = reqwest::Url::parse(url).map_err(|_| "invalid upstream URL".to_string())?;
        let credential_url = initial.to_string();
        let mut current = initial;
        for redirect_count in 0..=10 {
            validate_http_destination(current.as_str(), network_policy.as_ref()).await?;
            // The constrained client validates the same DNS answer that its connector uses,
            // preventing a hostname from rebinding to a local address after this preflight.
            let mut req = self
                .client_for_policy(network_policy.as_ref(), &current)?
                .get(current.clone());
            let request_headers =
                super::redirect_headers(headers, &credential_url, current.as_str());
            for (k, v) in &request_headers {
                if let (Ok(h_name), Ok(h_val)) = (HeaderName::from_str(k), HeaderValue::from_str(v))
                {
                    req = req.header(h_name, h_val);
                }
            }

            match req.send().await {
                Ok(resp) if resp.status().is_redirection() => {
                    if redirect_count == 10 {
                        return Err("upstream redirect limit exceeded".into());
                    }
                    let location = resp
                        .headers()
                        .get(reqwest::header::LOCATION)
                        .and_then(|value| value.to_str().ok())
                        .ok_or_else(|| "upstream redirect omitted Location".to_string())?;
                    current = current
                        .join(location)
                        .map_err(|_| "invalid upstream redirect URL".to_string())?;
                    continue;
                }
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
                            "[stream-proxy] accepting HLS Content-Type despite HTTP {}",
                            status
                        );
                        if let Ok(upstream) = Self::response_to_upstream(resp).await {
                            return Ok(upstream);
                        }
                    } else {
                        warn!(
                            "[stream-proxy] reqwest returned HTTP {} (ct={})",
                            status, content_type
                        );
                        drop(resp);
                    }
                }
                Err(e) => {
                    // reqwest's Display output can include the full authenticated URL.
                    warn!(
                        "[stream-proxy] upstream request failed (timeout={}, connect={}, status={})",
                        e.is_timeout(),
                        e.is_connect(),
                        e.is_status(),
                    );
                }
            }
            break;
        }

        if avio_allowed(network_policy.as_ref()) {
            self.try_avio(
                current.as_str(),
                &super::redirect_headers(headers, &credential_url, current.as_str()),
            )
            .await
        } else {
            Err("failed to fetch policy-constrained upstream".into())
        }
    }

    async fn try_avio(
        &self,
        url: &str,
        headers: &HashMap<String, String>,
    ) -> Result<UpstreamResponse, String> {
        // AVIO supplies bytes, not the origin's HTTP status/Content-Range. Never
        // invent a 206 from the request's "bytes=..." value: AVPlayer rejects it.
        if headers.keys().any(|key| key.eq_ignore_ascii_case("range")) {
            return Err(
                "upstream HTTP byte-range request failed; AVIO cannot preserve range metadata"
                    .into(),
            );
        }
        #[cfg(feature = "upstream-avio")]
        {
            use crate::avio::get_avio_client;

            let avio_client = get_avio_client(self.ffmpeg_path.as_deref());
            if let Some(rx) = avio_client.spawn_stream(url.to_string(), headers.clone(), 30) {
                let stream = ReceiverStream::new(rx);
                let body = Body::from_stream(stream);

                return Ok(UpstreamResponse {
                    status: StatusCode::OK,
                    headers: HeaderMap::new(),
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
    fn connect_with_policy<'a>(
        &'a self,
        url: &'a str,
        headers: &'a HashMap<String, String>,
        network_policy: Option<NetworkPolicy>,
    ) -> UpstreamConnectFuture<'a> {
        Box::pin(async move { self.connect_inner(url, headers, network_policy).await })
    }
}

fn avio_allowed(network_policy: Option<&NetworkPolicy>) -> bool {
    network_policy.is_none()
}

#[cfg(test)]
mod tests {
    use super::*;
    use reqwest::dns::Resolve;

    #[test]
    fn cross_origin_redirects_preserve_browser_context_not_credentials() {
        let headers = HashMap::from([
            ("Authorization".into(), "Bearer secret".into()),
            ("Cookie".into(), "session=secret".into()),
            ("User-Agent".into(), "Page agent".into()),
            ("Range".into(), "bytes=0-10".into()),
            (
                "Referer".into(),
                "https://page.test/watch?secret=value".into(),
            ),
        ]);
        let scoped = super::super::redirect_headers(
            &headers,
            "https://media.test/start",
            "https://cdn.test/file.mp4",
        );
        assert!(!scoped.contains_key("Authorization"));
        assert!(!scoped.contains_key("Cookie"));
        assert_eq!(scoped.get("User-Agent"), Some(&"Page agent".to_string()));
        assert_eq!(
            scoped.get("Referer"),
            Some(&"https://page.test/".to_string())
        );
        assert_eq!(scoped.get("range"), Some(&"bytes=0-10".to_string()));
    }

    #[tokio::test]
    async fn avio_does_not_fabricate_partial_content() {
        let fetcher = ReqwestUpstreamFetcher::new(None);
        let result = fetcher
            .try_avio(
                "https://example.test/video.mp4",
                &HashMap::from([("Range".into(), "bytes=0-1".into())]),
            )
            .await;
        assert!(result.is_err());
    }

    #[test]
    fn avio_is_available_only_to_trusted_traffic() {
        let page_policy = NetworkPolicy::new(vec![]).unwrap();
        assert!(avio_allowed(None));
        assert!(!avio_allowed(Some(&page_policy)));
    }

    #[tokio::test]
    async fn public_only_dns_rejects_local_names() {
        let name = "localhost".parse().expect("valid DNS name");
        assert!(PolicyDns {
            allow_private_network: false
        }
        .resolve(name)
        .await
        .is_err());
    }
}
