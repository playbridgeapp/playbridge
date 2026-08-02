use axum::body::Body;
use axum::http::{header, Request, StatusCode};
#[cfg(feature = "upstream-reqwest")]
use axum::{http::HeaderMap, response::IntoResponse, routing::get, Router};
use serde_json::{json, Value};
#[cfg(feature = "upstream-reqwest")]
use std::collections::HashMap;
use std::{fs, time::Duration};
use stream_proxy_rust::{create_router, Config, ProxyServer, ProxyServerConfig};
#[cfg(feature = "upstream-reqwest")]
use tokio::net::TcpListener;
use tower::ServiceExt;

fn test_config() -> Config {
    Config {
        port: 8888,
        address: "127.0.0.1".to_string(),
        password: Some("testpassword123".to_string()),
        ffmpeg_path: None,
    }
}

#[tokio::test]
async fn test_health_check() {
    let (app, _, _) = create_router(test_config()).unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .uri("/health")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
}

#[tokio::test]
async fn test_auth_unauthorized() {
    let (app, _, _) = create_router(test_config()).unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .uri("/epg?uri=http://example.com")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn test_register_stateful_and_encrypted_mediaflow_urls() {
    let (app, _, _) = create_router(test_config()).unwrap();

    let req_body = json!({
        "url": "http://stream.example.com/live",
        "content_type": "application/vnd.apple.mpegurl",
        "headers": {
            "User-Agent": "PlayBridge"
        }
    });

    let response = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/register?token=testpassword123")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(serde_json::to_vec(&req_body).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    let bytes = axum::body::to_bytes(response.into_body(), usize::MAX)
        .await
        .unwrap();
    let json_resp: Value = serde_json::from_slice(&bytes).unwrap();

    let proxy_url = json_resp["proxy_url"].as_str().unwrap();
    let encrypted_url = json_resp["encrypted_url"].as_str().unwrap();

    assert!(proxy_url.contains("/s/"));
    assert!(proxy_url.ends_with("/playlist.m3u8"));
    assert!(encrypted_url.contains("/proxy/hls/"));
    assert!(!encrypted_url.contains("PlayBridge")); // Headers must be encrypted in URL!
}

#[tokio::test]
async fn test_old_plaintext_base64_route_removed() {
    let (app, _, _) = create_router(test_config()).unwrap();

    let uri = "/s/play/aHR0cHM6Ly9leGFtcGxlLmNvbS9saXZlL21hc3Rlci5tM3U4/eyJVc2VyLUFnZW50IjoiTW96aWxsYS81LjAgKCkifQ/master.m3u8?token=testpassword123";

    let response = app
        .oneshot(Request::builder().uri(uri).body(Body::empty()).unwrap())
        .await
        .unwrap();

    // Old unencrypted base64 route must be rejected / forbidden
    assert_eq!(response.status(), StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn embedded_server_serves_scoped_local_file_ranges() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("video.mp4");
    fs::write(&path, b"0123456789").unwrap();
    let server = ProxyServer::start(ProxyServerConfig::default())
        .await
        .unwrap();
    let media = server
        .register_file(
            "127.0.0.1",
            &path,
            Some("video/mp4".into()),
            Duration::from_secs(60),
        )
        .unwrap();

    let client = reqwest::Client::new();
    let response = client
        .get(&media.url)
        .header(header::RANGE.as_str(), "bytes=2-5")
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::PARTIAL_CONTENT);
    assert_eq!(
        response
            .headers()
            .get(header::CONTENT_RANGE)
            .unwrap()
            .to_str()
            .unwrap(),
        "bytes 2-5/10"
    );
    assert_eq!(response.bytes().await.unwrap().as_ref(), b"2345");

    assert!(server.service().revoke(&media.id));
    assert_eq!(
        client.get(&media.url).send().await.unwrap().status(),
        StatusCode::NOT_FOUND
    );
    server.shutdown().await.unwrap();
}

#[tokio::test]
async fn embedded_server_uses_an_ephemeral_port() {
    let server = ProxyServer::start(ProxyServerConfig::default())
        .await
        .unwrap();
    assert_ne!(server.local_addr().port(), 0);
    let health = format!("{}/health", server.base_url("127.0.0.1"));
    assert_eq!(reqwest::get(health).await.unwrap().status(), StatusCode::OK);
    server.shutdown().await.unwrap();
}

#[cfg(feature = "upstream-reqwest")]
#[tokio::test]
async fn extensionless_hls_children_stay_on_the_header_preserving_proxy() {
    const ORIGIN_HEADER: &str = "x-playbridge-origin-test";

    async fn master(headers: HeaderMap) -> impl IntoResponse {
        if headers
            .get(ORIGIN_HEADER)
            .and_then(|value| value.to_str().ok())
            != Some("allowed")
        {
            return StatusCode::FORBIDDEN.into_response();
        }
        (
            [(header::CONTENT_TYPE, "application/vnd.apple.mpegurl")],
            "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"stereo\",NAME=\"Japanese\",DEFAULT=YES,URI=\"audio/child?session=test\"\n#EXT-X-STREAM-INF:BANDWIDTH=4000000,AUDIO=\"stereo\"\nvideo/child?session=test\n",
        )
            .into_response()
    }

    async fn video_child(headers: HeaderMap) -> impl IntoResponse {
        if headers
            .get(ORIGIN_HEADER)
            .and_then(|value| value.to_str().ok())
            != Some("allowed")
        {
            return StatusCode::FORBIDDEN.into_response();
        }
        (
            [(header::CONTENT_TYPE, "application/x-mpegURL")],
            "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-PART:DURATION=0.333,URI=\"part.jpg?session=test\"\n#EXTINF:2,\n000.jpg?session=test\n",
        )
            .into_response()
    }

    async fn audio_child(headers: HeaderMap) -> impl IntoResponse {
        if headers
            .get(ORIGIN_HEADER)
            .and_then(|value| value.to_str().ok())
            != Some("allowed")
        {
            return StatusCode::FORBIDDEN.into_response();
        }
        (
            [(header::CONTENT_TYPE, "application/x-mpegURL")],
            "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\n000.aac?session=test\n",
        )
            .into_response()
    }

    async fn segment(headers: HeaderMap) -> impl IntoResponse {
        if headers
            .get(ORIGIN_HEADER)
            .and_then(|value| value.to_str().ok())
            != Some("allowed")
        {
            return StatusCode::FORBIDDEN.into_response();
        }
        (
            // Some anime CDNs disguise MPEG-TS HLS segments as JPEGs. Cast
            // rejects image/jpeg even though the body is transport-stream data.
            [(header::CONTENT_TYPE, "image/jpeg")],
            "G-transport-stream-segment",
        )
            .into_response()
    }

    async fn audio_segment(headers: HeaderMap) -> impl IntoResponse {
        if headers
            .get(ORIGIN_HEADER)
            .and_then(|value| value.to_str().ok())
            != Some("allowed")
        {
            return StatusCode::FORBIDDEN.into_response();
        }
        ([(header::CONTENT_TYPE, "audio/aac")], "packed-aac-segment").into_response()
    }

    let origin_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let origin_addr = origin_listener.local_addr().unwrap();
    let origin_task = tokio::spawn(async move {
        axum::serve(
            origin_listener,
            Router::new()
                .route("/live", get(master))
                .route("/audio/child", get(audio_child))
                .route("/video/child", get(video_child))
                .route("/audio/000.aac", get(audio_segment))
                .route("/video/000.jpg", get(segment))
                .route("/video/part.jpg", get(segment)),
        )
        .await
        .unwrap();
    });

    let proxy = ProxyServer::start(ProxyServerConfig::default())
        .await
        .unwrap();
    let hinted = proxy
        .register_remote_with_content_type(
            "127.0.0.1",
            format!("http://{origin_addr}/live"),
            HashMap::from([(ORIGIN_HEADER.to_string(), "allowed".to_string())]),
            Some("application/vnd.apple.mpegurl"),
        )
        .unwrap();
    assert!(hinted.url.ends_with("/playlist.m3u8"));
    assert!(hinted
        .encrypted_url
        .as_deref()
        .is_some_and(|url| url.contains("/proxy/hls/")));

    let registered = proxy
        .register_remote(
            "127.0.0.1",
            format!("http://{origin_addr}/live"),
            HashMap::from([(ORIGIN_HEADER.to_string(), "allowed".to_string())]),
        )
        .unwrap();
    assert!(
        !registered.url.ends_with(".m3u8"),
        "test must exercise content-type detection, not filename detection"
    );

    let client = reqwest::Client::new();
    let master_body = client
        .get(&registered.url)
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .text()
        .await
        .unwrap();
    assert!(master_body.contains("AUTOSELECT=YES"));
    let child_url = master_body
        .lines()
        .find(|line| !line.trim().is_empty() && !line.starts_with('#'))
        .unwrap();
    assert!(child_url.starts_with(&proxy.base_url("127.0.0.1")));
    assert!(!child_url.starts_with(&format!("http://{origin_addr}")));
    let child_uri = reqwest::Url::parse(child_url).unwrap();
    assert_eq!(child_uri.path().rsplit('/').next(), Some("video-child"));
    assert!(!child_uri.query_pairs().any(|(key, _)| key == "pb_hls"));
    let audio_url = master_body
        .lines()
        .find_map(|line| {
            line.split_once("URI=\"")?
                .1
                .split_once('"')
                .map(|item| item.0)
        })
        .unwrap();
    let audio_uri = reqwest::Url::parse(audio_url).unwrap();
    assert_eq!(audio_uri.path().rsplit('/').next(), Some("audio-child"));
    assert_ne!(audio_uri.path(), child_uri.path());

    let audio_body = client
        .get(audio_url)
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .text()
        .await
        .unwrap();
    let audio_segment_url = audio_body
        .lines()
        .find(|line| !line.trim().is_empty() && !line.starts_with('#'))
        .unwrap();
    let audio_segment_uri = reqwest::Url::parse(audio_segment_url).unwrap();
    assert_eq!(
        audio_segment_uri.path().rsplit('/').next(),
        Some("audio-000.aac")
    );
    assert!(
        !audio_segment_uri
            .query_pairs()
            .any(|(key, _)| key == "pb_hls"),
        "packed audio must not be relabelled as MPEG-TS"
    );
    let audio_response = client
        .get(audio_segment_url)
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap();
    assert_eq!(
        audio_response
            .headers()
            .get(header::CONTENT_TYPE)
            .and_then(|value| value.to_str().ok()),
        Some("audio/aac")
    );

    let child_body = client
        .get(child_url)
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .text()
        .await
        .unwrap();
    let segment_url = child_body
        .lines()
        .find(|line| !line.trim().is_empty() && !line.starts_with('#'))
        .unwrap();
    assert!(segment_url.starts_with(&proxy.base_url("127.0.0.1")));
    let segment_uri = reqwest::Url::parse(segment_url).unwrap();
    assert_eq!(segment_uri.path().rsplit('/').next(), Some("video-000.ts"));
    assert!(segment_uri
        .query_pairs()
        .any(|(key, value)| key == "pb_hls" && value == "ts"));
    assert!(segment_uri
        .query_pairs()
        .any(|(key, value)| key == "uri" && value.ends_with("/000.jpg?session=test")));
    let segment_response = client
        .get(segment_url)
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap();
    assert_eq!(
        segment_response
            .headers()
            .get(header::CONTENT_TYPE)
            .and_then(|value| value.to_str().ok()),
        Some("video/mp2t")
    );
    assert_eq!(
        segment_response.bytes().await.unwrap().as_ref(),
        b"G-transport-stream-segment"
    );

    let part_url = child_body
        .lines()
        .find(|line| line.starts_with("#EXT-X-PART:"))
        .and_then(|line| line.split_once("URI=\"")?.1.split_once('"'))
        .map(|item| item.0)
        .unwrap();
    let part_uri = reqwest::Url::parse(part_url).unwrap();
    assert_eq!(part_uri.path().rsplit('/').next(), Some("video-part.ts"));
    assert!(part_uri
        .query_pairs()
        .any(|(key, value)| key == "pb_hls" && value == "ts"));
    let part_response = client
        .get(part_url)
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap();
    assert_eq!(
        part_response
            .headers()
            .get(header::CONTENT_TYPE)
            .and_then(|value| value.to_str().ok()),
        Some("video/mp2t")
    );

    let hinted = proxy
        .register_remote_with_content_type(
            "127.0.0.1",
            format!("http://{origin_addr}/live"),
            HashMap::new(),
            Some("application/vnd.apple.mpegurl"),
        )
        .unwrap();
    assert!(hinted.url.ends_with("/playlist.m3u8"));

    proxy.shutdown().await.unwrap();
    origin_task.abort();
}

/// Needs a real origin HTTP client (reqwest). Skipped when the library is built
/// with only `upstream-jni` (Android host-fetch feature set).
#[cfg(feature = "upstream-reqwest")]
#[tokio::test]
async fn dash_manifest_and_segments_stay_on_the_header_preserving_proxy() {
    async fn manifest(headers: HeaderMap) -> impl IntoResponse {
        if headers
            .get(header::ORIGIN)
            .and_then(|value| value.to_str().ok())
            != Some("https://page.example")
        {
            return (StatusCode::FORBIDDEN, "blockorigin").into_response();
        }
        (
            [(header::CONTENT_TYPE, "application/dash+xml")],
            r#"<?xml version="1.0"?><MPD><Period><AdaptationSet mimeType="video/mp4"><Representation bandwidth="1000000" codecs="avc1.4d401e"><BaseURL>/companion/videoplayback?id=video&amp;source=test</BaseURL><SegmentBase indexRange="4-7"><Initialization range="0-3"/></SegmentBase></Representation></AdaptationSet></Period></MPD>"#,
        )
            .into_response()
    }

    async fn segment(headers: HeaderMap) -> impl IntoResponse {
        let origin = headers
            .get(header::ORIGIN)
            .and_then(|value| value.to_str().ok());
        let range = headers
            .get(header::RANGE)
            .and_then(|value| value.to_str().ok());
        if origin != Some("https://page.example") || range != Some("bytes=0-3") {
            return (StatusCode::FORBIDDEN, "blockorigin").into_response();
        }
        (
            StatusCode::PARTIAL_CONTENT,
            [
                (header::CONTENT_TYPE, "video/mp4"),
                (header::CONTENT_RANGE, "bytes 0-3/8"),
            ],
            "init",
        )
            .into_response()
    }

    let upstream_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let upstream_addr = upstream_listener.local_addr().unwrap();
    let upstream = tokio::spawn(async move {
        axum::serve(
            upstream_listener,
            Router::new()
                .route("/manifest/dash/id/video", get(manifest))
                .route("/companion/videoplayback", get(segment)),
        )
        .await
        .unwrap();
    });

    let proxy = ProxyServer::start(ProxyServerConfig::default())
        .await
        .unwrap();
    let media = proxy
        .register_remote(
            "127.0.0.1",
            format!("http://{upstream_addr}/manifest/dash/id/video"),
            HashMap::from([("Origin".to_string(), "https://page.example".to_string())]),
        )
        .unwrap();
    assert!(media.url.ends_with("/manifest.mpd"));
    let client = reqwest::Client::new();
    let manifest_body = client
        .get(&media.url)
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .text()
        .await
        .unwrap();
    let rewritten_base = manifest_body
        .split("<BaseURL>")
        .nth(1)
        .and_then(|value| value.split("</BaseURL>").next())
        .unwrap();
    // Stateful DASH rewrites use absolute proxy URLs (Host-based) so browser
    // players on a different port never resolve children against the wrong origin.
    assert!(
        rewritten_base.contains("/s/"),
        "expected proxied BaseURL, got {rewritten_base}"
    );
    assert!(!rewritten_base.contains("blockorigin"));
    assert!(!rewritten_base.contains("_root_"));

    let edl_url = media.url.replace("/manifest.mpd", "/manifest.edl");
    let edl = client
        .get(edl_url)
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .text()
        .await
        .unwrap();
    assert!(edl.starts_with("# mpv EDL v0\n!new_stream\n"));
    assert!(edl.contains(&format!("{}/s/", proxy.base_url("127.0.0.1"))));
    assert!(!edl.contains("blockorigin"));

    let segment_url =
        if rewritten_base.starts_with("http://") || rewritten_base.starts_with("https://") {
            rewritten_base.to_string()
        } else {
            format!("{}{}", proxy.base_url("127.0.0.1"), rewritten_base)
        };
    let segment_response = client
        .get(segment_url)
        .header(header::RANGE, "bytes=0-3")
        .send()
        .await
        .unwrap();
    assert_eq!(segment_response.status(), StatusCode::PARTIAL_CONTENT);
    assert_eq!(segment_response.bytes().await.unwrap().as_ref(), b"init");

    proxy.shutdown().await.unwrap();
    upstream.abort();
}
