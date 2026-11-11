#![cfg(feature = "upstream-reqwest")]
//! Long signed media URLs (YouTube-style frontends) must stay under mpv's 4095-byte
//! request limit once proxied, without widening what a proxied URL can reach.
use axum::{
    extract::RawQuery,
    http::{header, StatusCode},
    routing::get,
    Router,
};
use std::collections::HashMap;
use stream_proxy_rust::{ProxyServer, ProxyServerConfig, RegisteredMedia};
use tokio::net::TcpListener;
use url::Url;

/// mpv's FFmpeg HTTP layer truncates request URLs longer than this.
const MPV_URL_LIMIT: usize = 4095;

fn long_signature() -> String {
    // About the size of a real signed videoplayback query (2,000+ characters).
    "s".repeat(2_400)
}

async fn serve_long_dash() -> (std::net::SocketAddr, tokio::task::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let sig = long_signature();
    let manifest = format!(
        r#"<MPD><Period>
<AdaptationSet mimeType="video/mp4"><Representation id="137" bandwidth="4000000" codecs="avc1.640028"><BaseURL>/companion/videoplayback?itag=137&amp;sig={sig}</BaseURL></Representation></AdaptationSet>
<AdaptationSet mimeType="audio/mp4"><Representation id="140" bandwidth="128000" codecs="mp4a.40.2"><BaseURL>/companion/videoplayback?itag=140&amp;sig={sig}</BaseURL></Representation></AdaptationSet>
<AdaptationSet mimeType="audio/mp4"><Representation id="short" bandwidth="64000" codecs="mp4a.40.2"><BaseURL>/short.m4a</BaseURL></Representation></AdaptationSet>
</Period></MPD>"#
    );
    let task = tokio::spawn(async move {
        let router = Router::new()
            .route(
                "/manifest.mpd",
                get(move || {
                    let manifest = manifest.clone();
                    async move { ([(header::CONTENT_TYPE, "application/dash+xml")], manifest) }
                }),
            )
            .route(
                "/companion/videoplayback",
                get(move |RawQuery(query): RawQuery| {
                    let expected = long_signature();
                    async move {
                        let query = query.unwrap_or_default();
                        // The upstream must receive the full signed URL, not a truncated one.
                        if !query.ends_with(&format!("sig={expected}")) {
                            return (StatusCode::FORBIDDEN, "bad signature".to_string());
                        }
                        let itag = query.split('&').next().unwrap_or_default().to_string();
                        (StatusCode::OK, itag)
                    }
                }),
            )
            .route("/short.m4a", get(|| async { "short" }))
            .route("/private", get(|| async { "private" }));
        axum::serve(listener, router).await.unwrap();
    });
    (address, task)
}

fn edl_url(media: &RegisteredMedia) -> String {
    let mut url = Url::parse(&media.url).unwrap();
    let mut segments: Vec<String> = url.path_segments().unwrap().map(String::from).collect();
    *segments.last_mut().unwrap() = "manifest.edl".into();
    url.set_path(&segments.join("/"));
    url.to_string()
}

/// The stream URLs in an mpv EDL (`%<len>%<url>` lines).
fn edl_streams(edl: &str) -> Vec<String> {
    edl.lines()
        .filter_map(|line| line.strip_prefix('%'))
        .map(|rest| rest.split_once('%').unwrap().1.to_string())
        .collect()
}

fn with_query(url: &str, query: &str) -> String {
    let mut parsed = Url::parse(url).unwrap();
    parsed.set_query(Some(query));
    parsed.to_string()
}

fn query_value(url: &str, name: &str) -> Option<String> {
    Url::parse(url)
        .unwrap()
        .query_pairs()
        .find(|(key, _)| key == name)
        .map(|(_, value)| value.into_owned())
}

#[tokio::test]
async fn long_signed_dash_streams_fit_mpv_and_still_play() {
    let (address, task) = serve_long_dash().await;
    let proxy = ProxyServer::start(ProxyServerConfig::default())
        .await
        .unwrap();
    let media = proxy
        .register_remote(
            "127.0.0.1",
            format!("http://{address}/manifest.mpd"),
            HashMap::new(),
        )
        .unwrap();
    let client = reqwest::Client::new();
    let edl = client
        .get(edl_url(&media))
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .text()
        .await
        .unwrap();
    let streams = edl_streams(&edl);
    assert_eq!(streams.len(), 2, "{edl}");
    for (stream, itag) in streams.iter().zip(["itag=137", "itag=140"]) {
        assert!(
            stream.len() < MPV_URL_LIMIT,
            "{} bytes is past mpv's limit",
            stream.len()
        );
        assert!(query_value(stream, "ref").is_some(), "{stream}");
        assert!(query_value(stream, "uri").is_none() && query_value(stream, "cap").is_none());
        let body = client
            .get(stream)
            .header(header::RANGE, "bytes=0-")
            .send()
            .await
            .unwrap()
            .error_for_status()
            .unwrap()
            .text()
            .await
            .unwrap();
        assert_eq!(body, itag);
    }

    // The same target keeps the same reference, so repeated rewrites don't grow the table.
    let again = client
        .get(edl_url(&media))
        .send()
        .await
        .unwrap()
        .text()
        .await
        .unwrap();
    assert_eq!(edl_streams(&again), streams);

    proxy.shutdown().await.unwrap();
    task.abort();
}

#[tokio::test]
async fn short_targets_keep_signed_capabilities() {
    let (address, task) = serve_long_dash().await;
    let proxy = ProxyServer::start(ProxyServerConfig::default())
        .await
        .unwrap();
    let media = proxy
        .register_remote(
            "127.0.0.1",
            format!("http://{address}/manifest.mpd"),
            HashMap::new(),
        )
        .unwrap();
    let client = reqwest::Client::new();
    let manifest = client
        .get(&media.url)
        .send()
        .await
        .unwrap()
        .text()
        .await
        .unwrap();
    let short = manifest
        .split("<BaseURL>")
        .skip(1)
        .map(|part| {
            part.split("</BaseURL>")
                .next()
                .unwrap()
                .replace("&amp;", "&")
        })
        .find(|url| url.contains("short.m4a"))
        .unwrap();
    assert!(query_value(&short, "uri").is_some() && query_value(&short, "cap").is_some());
    assert!(query_value(&short, "ref").is_none());
    proxy.shutdown().await.unwrap();
    task.abort();
}

#[tokio::test]
async fn references_cannot_be_forged_mixed_or_moved_between_sessions() {
    let (address, task) = serve_long_dash().await;
    let proxy = ProxyServer::start(ProxyServerConfig::default())
        .await
        .unwrap();
    let register = || {
        proxy
            .register_remote(
                "127.0.0.1",
                format!("http://{address}/manifest.mpd"),
                HashMap::new(),
            )
            .unwrap()
    };
    let first = register();
    let second = register();
    let client = reqwest::Client::new();
    let edl = client
        .get(edl_url(&first))
        .send()
        .await
        .unwrap()
        .text()
        .await
        .unwrap();
    let stream = edl_streams(&edl).remove(0);
    let reference = query_value(&stream, "ref").unwrap();
    let forbidden = urlencoding::encode(&format!("http://{address}/private")).into_owned();

    let mut rejected = vec![
        with_query(&stream, "ref=not-a-reference"),
        with_query(&stream, &format!("ref={reference}&ref={reference}")),
        with_query(&stream, &format!("ref={reference}&uri={forbidden}")),
        with_query(&stream, &format!("ref={reference}&cap=x")),
    ];
    // A reference only exists inside the session whose rewrite issued it.
    let other_session = Url::parse(&second.url).unwrap();
    let mut moved = Url::parse(&stream).unwrap();
    moved.set_path(other_session.path());
    rejected.push(moved.to_string());

    for url in rejected {
        let response = client.get(&url).send().await.unwrap();
        assert_eq!(response.status(), StatusCode::FORBIDDEN, "{url}");
    }

    // Revoking the session drops its references.
    assert!(proxy.service().revoke(&first.id));
    assert_eq!(
        client.get(&stream).send().await.unwrap().status(),
        StatusCode::FORBIDDEN
    );
    proxy.shutdown().await.unwrap();
    task.abort();
}
