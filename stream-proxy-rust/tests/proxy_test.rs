use axum::body::Body;
use axum::http::{header, Request, StatusCode};
use serde_json::{json, Value};
use stream_proxy_rust::{create_router, Config};
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
        "url": "http://stream.example.com/playlist.m3u8",
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
    assert!(encrypted_url.contains("/proxy/hls/"));
    assert!(!encrypted_url.contains("PlayBridge")); // Headers must be encrypted in URL!
}

#[tokio::test]
async fn test_old_plaintext_base64_route_removed() {
    let (app, _, _) = create_router(test_config()).unwrap();

    let uri = "/s/play/aHR0cHM6Ly9leGFtcGxlLmNvbS9saXZlL21hc3Rlci5tM3U4/eyJVc2VyLUFnZW50IjoiTW96aWxsYS81LjAgKCkifQ/master.m3u8?token=testpassword123";

    let response = app
        .oneshot(
            Request::builder()
                .uri(uri)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    // Old unencrypted base64 route must be rejected / forbidden
    assert_eq!(response.status(), StatusCode::FORBIDDEN);
}
