#![cfg(feature = "upstream-reqwest")]
use axum::{http::header, routing::get, Router};
use std::collections::HashMap;
use stream_proxy_rust::{ProxyServer, ProxyServerConfig};
use tokio::net::TcpListener;

const URL_LIST: &str = "http://radio.example/a\nhttp://radio.example/b\n";
const BIG: usize = 6 * 1024 * 1024;

#[tokio::test]
async fn hls_hinted_urls_are_inspected_not_assumed() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let router = Router::new()
        .route(
            "/radio.m3u",
            get(|| async { ([(header::CONTENT_TYPE, "audio/x-mpegurl")], URL_LIST) }),
        )
        .route(
            "/bom.m3u",
            get(|| async {
                (
                    [(header::CONTENT_TYPE, "text/plain")],
                    "\u{feff}#EXTM3U\n#EXTINF:1,\nsegment.ts\n",
                )
            }),
        )
        .route(
            "/get.php",
            get(|| async {
                (
                    [(header::CONTENT_TYPE, "text/plain")],
                    "#EXTM3U\n#EXTINF:1,\nsegment.ts\n",
                )
            }),
        )
        .route(
            "/big.m3u8",
            get(|| async {
                (
                    [(header::CONTENT_TYPE, "application/octet-stream")],
                    vec![b'v'; BIG],
                )
            }),
        );
    let task = tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });
    let proxy = ProxyServer::start(ProxyServerConfig::default())
        .await
        .unwrap();
    let roots = |path: &str| {
        let media = proxy
            .register_remote("127.0.0.1", format!("http://{addr}/{path}"), HashMap::new())
            .unwrap();
        vec![media.url.clone(), media.encrypted_url.clone().unwrap()]
    };

    // Plain URL-list .m3u passes through byte-identical.
    for root in roots("radio.m3u") {
        let resp = reqwest::get(&root).await.unwrap();
        assert_eq!(resp.status(), 200, "{root}");
        assert_eq!(resp.text().await.unwrap(), URL_LIST);
    }
    // BOM-prefixed playlist is rewritten (BOM stripped, segment proxied).
    for root in roots("bom.m3u") {
        let resp = reqwest::get(&root).await.unwrap();
        assert_eq!(resp.status(), 200, "{root}");
        let text = resp.text().await.unwrap();
        assert!(text.starts_with("#EXTM3U"), "{text}");
        assert!(!text.lines().any(|l| l == "segment.ts"), "{text}");
    }
    // type=m3u8 served as text/plain is rewritten.
    for root in roots("get.php?type=m3u8") {
        let text = reqwest::get(&root).await.unwrap().text().await.unwrap();
        assert!(text.contains("#EXTM3U"));
        assert!(!text.lines().any(|l| l == "segment.ts"), "{text}");
    }
    // Large non-HLS body on a hinted URL streams intact.
    for root in roots("big.m3u8") {
        let resp = reqwest::get(&root).await.unwrap();
        assert_eq!(resp.status(), 200, "{root}");
        let bytes = resp.bytes().await.unwrap();
        assert_eq!(bytes.len(), BIG);
        assert!(bytes.iter().all(|b| *b == b'v'));
    }
    proxy.shutdown().await.unwrap();
    task.abort();
}
