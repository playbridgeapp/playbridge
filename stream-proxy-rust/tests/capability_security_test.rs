#![cfg(feature = "upstream-reqwest")]
use axum::{
    http::{header, HeaderMap, StatusCode},
    response::IntoResponse,
    routing::get,
    Router,
};
use std::{
    collections::HashMap,
    sync::{
        atomic::{AtomicUsize, Ordering},
        Arc,
    },
};
use stream_proxy_rust::{ProxyServer, ProxyServerConfig};
use tokio::net::TcpListener;
use url::Url;

fn xml_url(value: &str) -> String {
    value.replace("&amp;", "&")
}

#[tokio::test]
async fn root_and_child_capabilities_cannot_be_retargeted_and_preserve_playback() {
    let reads = Arc::new(AtomicUsize::new(0));
    let counter = reads.clone();
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let router = Router::new()
        .route("/master.m3u8", get(|| async { ([(header::CONTENT_TYPE, "application/vnd.apple.mpegurl")],
            "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:10,\nsegment.ts\n") }))
        .route("/segment.ts", get(|headers: HeaderMap| async move {
            assert_eq!(headers["authorization"], "fixture-secret");
            assert_eq!(headers["range"], "bytes=2-5");
            (StatusCode::PARTIAL_CONTENT, [(header::CONTENT_RANGE, "bytes 2-5/10")], "2345")
        }))
        .route("/key", get(|| async { "key" }))
        .route("/private", get(move || { let counter = counter.clone(); async move {
            counter.fetch_add(1, Ordering::SeqCst); "private"
        }}));
    let task = tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });
    let proxy = ProxyServer::start(ProxyServerConfig::default())
        .await
        .unwrap();
    let registration = proxy
        .register_remote(
            "127.0.0.1",
            format!("http://{address}/master.m3u8"),
            HashMap::from([("Authorization".into(), "fixture-secret".into())]),
        )
        .unwrap();
    let client = reqwest::Client::new();
    let forbidden = format!("http://{address}/private");
    for root in [
        &registration.url,
        registration.encrypted_url.as_ref().unwrap(),
    ] {
        for suffix in [
            format!("uri={}", urlencoding::encode(&forbidden)),
            format!("uri={0}&uri={0}", urlencoding::encode(&forbidden)),
            "uri=".into(),
        ] {
            let separator = if root.contains('?') { '&' } else { '?' };
            let rejected = client
                .get(format!("{root}{separator}{suffix}"))
                .send()
                .await
                .unwrap();
            assert_eq!(
                rejected.status(),
                StatusCode::FORBIDDEN,
                "unauthorized upstream reads: {}",
                reads.load(Ordering::SeqCst)
            );
            assert_eq!(rejected.text().await.unwrap(), "Invalid proxy capability");
        }
        let body = client
            .get(root)
            .send()
            .await
            .unwrap()
            .error_for_status()
            .unwrap()
            .text()
            .await
            .unwrap();
        let child = body
            .lines()
            .find(|line| !line.starts_with('#') && !line.is_empty())
            .unwrap();
        let child = if child.starts_with('/') {
            format!("{}{child}", proxy.base_url("127.0.0.1"))
        } else {
            child.into()
        };
        let mut changed = Url::parse(&child).unwrap();
        let mut pairs: Vec<(String, String)> = changed
            .query_pairs()
            .map(|(a, b)| (a.into(), b.into()))
            .collect();
        if pairs.iter().any(|(name, _)| name == "uri") {
            for (name, value) in &mut pairs {
                if name == "uri" {
                    *value = forbidden.clone();
                }
            }
        } else {
            pairs.push(("uri".into(), forbidden.clone()));
        }
        changed.set_query(Some(&serde_urlencoded::to_string(&pairs).unwrap()));
        assert_eq!(
            client.get(changed).send().await.unwrap().status(),
            StatusCode::FORBIDDEN
        );
        let range = client
            .get(&child)
            .header("Range", "bytes=2-5")
            .send()
            .await
            .unwrap();
        assert_eq!(range.status(), StatusCode::PARTIAL_CONTENT);
        assert_eq!(range.headers()[header::CONTENT_RANGE], "bytes 2-5/10");
        assert_eq!(range.text().await.unwrap(), "2345");
        let key = body
            .split("URI=\"")
            .nth(1)
            .unwrap()
            .split('"')
            .next()
            .unwrap();
        let key = if key.starts_with('/') {
            format!("{}{key}", proxy.base_url("127.0.0.1"))
        } else {
            key.into()
        };
        assert_eq!(
            client.get(key).send().await.unwrap().text().await.unwrap(),
            "key"
        );
    }
    // Arbitrary relative path traversal is not a session capability either.
    assert_eq!(
        client
            .get(format!("{}/extra/private", registration.url))
            .send()
            .await
            .unwrap()
            .status(),
        StatusCode::FORBIDDEN
    );
    assert_eq!(reads.load(Ordering::SeqCst), 0);
    proxy.shutdown().await.unwrap();
    task.abort();
}

#[tokio::test]
async fn encrypted_failures_are_uniform_and_no_unsigned_fallback_exists() {
    let proxy = ProxyServer::start(ProxyServerConfig::default())
        .await
        .unwrap();
    let registration = proxy
        .register_remote("127.0.0.1", "https://cdn.example/video.mp4", HashMap::new())
        .unwrap();
    let original = Url::parse(registration.encrypted_url.as_ref().unwrap()).unwrap();
    let token = original
        .query_pairs()
        .find(|(key, _)| key == "token")
        .unwrap()
        .1
        .into_owned();
    let mut bad_tag = token.clone().into_bytes();
    let index = bad_tag.len() - 4;
    bad_tag[index] ^= 1;
    let client = reqwest::Client::new();
    for invalid in [
        "".into(),
        "%%%".into(),
        "AAAA".into(),
        String::from_utf8(bad_tag).unwrap(),
        "pb2.not-base64.not-tag".into(),
    ] {
        let mut request = original.clone();
        request.set_query(Some(
            &serde_urlencoded::to_string([("token", invalid)]).unwrap(),
        ));
        let rejected = client.get(request).send().await.unwrap();
        assert_eq!(rejected.status(), StatusCode::FORBIDDEN);
        assert_eq!(rejected.text().await.unwrap(), "Invalid proxy capability");
    }
    let mut duplicated = original.clone();
    duplicated.query_pairs_mut().append_pair("token", &token);
    assert_eq!(
        client.get(duplicated).send().await.unwrap().status(),
        StatusCode::FORBIDDEN
    );
    proxy.shutdown().await.unwrap();
}

#[tokio::test]
async fn dash_directory_and_numeric_templates_work_without_prefix_authority() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let task = tokio::spawn(async move {
        axum::serve(listener,Router::new()
        .route("/manifest.mpd",get(|| async {( [(header::CONTENT_TYPE,"application/dash+xml")],
            r#"<MPD><BaseURL>media/</BaseURL><Period><AdaptationSet><Representation id="v1"><SegmentTemplate media="$RepresentationID$/seg-$Number%05d$.m4s" initialization="$RepresentationID$/init.mp4"/></Representation></AdaptationSet></Period></MPD>"#)}))
        .route("/media/v1/seg-00042.m4s",get(|| async {"segment"}))
        .route("/media/v1/init.mp4",get(|| async {"init"}))).await.unwrap();
    });
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
    for url in [&media.url, media.encrypted_url.as_ref().unwrap()] {
        let text = client
            .get(url)
            .send()
            .await
            .unwrap()
            .error_for_status()
            .unwrap()
            .text()
            .await
            .unwrap();
        assert!(!text.contains("<BaseURL>"));
        let template = text
            .split("media=\"")
            .nth(1)
            .unwrap()
            .split('"')
            .next()
            .unwrap();
        assert!(template.contains("$Number%05d$"));
        let resolved = xml_url(template)
            .replace("$RepresentationID$", "v1")
            .replace("$Number%05d$", "00042");
        let resolved = if resolved.starts_with('/') {
            format!("{}{resolved}", proxy.base_url("127.0.0.1"))
        } else {
            resolved
        };
        assert_eq!(
            client
                .get(&resolved)
                .send()
                .await
                .unwrap()
                .error_for_status()
                .unwrap()
                .text()
                .await
                .unwrap(),
            "segment"
        );
        let mut changed = Url::parse(&resolved).unwrap();
        let pairs: Vec<(String, String)> = changed
            .query_pairs()
            .map(|(name, value)| {
                if name == "uri" || name == "target" {
                    (name.into(), format!("http://{address}/private"))
                } else {
                    (name.into(), value.into())
                }
            })
            .collect();
        changed.set_query(Some(&serde_urlencoded::to_string(pairs).unwrap()));
        assert_eq!(
            client.get(changed).send().await.unwrap().status(),
            StatusCode::FORBIDDEN
        );
    }
    proxy.shutdown().await.unwrap();
    task.abort();
}

#[tokio::test]
async fn child_credentials_are_not_rebound_to_a_different_origin() {
    let child = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let child_address = child.local_addr().unwrap();
    let child_task = tokio::spawn(async move {
        axum::serve(
            child,
            Router::new().route(
                "/segment.ts",
                get(|headers: HeaderMap| async move {
                    assert!(!headers.contains_key("authorization"));
                    assert!(!headers.contains_key("cookie"));
                    assert!(!headers.contains_key("x-custom-secret"));
                    assert_eq!(headers["user-agent"], "Fixture");
                    "segment".into_response()
                }),
            ),
        )
        .await
        .unwrap();
    });
    let root = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let root_address = root.local_addr().unwrap();
    let root_task = tokio::spawn(async move {
        axum::serve(
            root,
            Router::new().route(
                "/master.m3u8",
                get(move || async move {
                    (
                        [(header::CONTENT_TYPE, "application/vnd.apple.mpegurl")],
                        format!("#EXTM3U\n#EXTINF:2,\nhttp://{child_address}/segment.ts\n"),
                    )
                }),
            ),
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
            format!("http://{root_address}/master.m3u8"),
            HashMap::from([
                ("Authorization".into(), "secret".into()),
                ("Cookie".into(), "secret".into()),
                ("X-Custom-Secret".into(), "secret".into()),
                ("User-Agent".into(), "Fixture".into()),
            ]),
        )
        .unwrap();
    let client = reqwest::Client::new();
    for root in [&media.url, media.encrypted_url.as_ref().unwrap()] {
        let body = client
            .get(root)
            .send()
            .await
            .unwrap()
            .error_for_status()
            .unwrap()
            .text()
            .await
            .unwrap();
        let child = body
            .lines()
            .find(|line| !line.starts_with('#') && !line.is_empty())
            .unwrap();
        let child = if child.starts_with('/') {
            format!("{}{child}", proxy.base_url("127.0.0.1"))
        } else {
            child.into()
        };
        assert_eq!(
            client
                .get(child)
                .send()
                .await
                .unwrap()
                .error_for_status()
                .unwrap()
                .text()
                .await
                .unwrap(),
            "segment"
        );
    }
    proxy.shutdown().await.unwrap();
    root_task.abort();
    child_task.abort();
}

#[tokio::test]
async fn redirected_hls_children_use_final_manifest_base_and_reserved_headers_stay_private() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let task = tokio::spawn(async move {
        axum::serve(
            listener,
            Router::new()
                .route(
                    "/master.m3u8",
                    get(|| async {
                        (
                            StatusCode::FOUND,
                            [(header::LOCATION, "/nested/master.m3u8")],
                        )
                    }),
                )
                .route(
                    "/nested/master.m3u8",
                    get(|| async {
                        (
                            [(header::CONTENT_TYPE, "application/vnd.apple.mpegurl")],
                            "#EXTM3U\n#EXTINF:2,\nsegment.ts\n",
                        )
                    }),
                )
                .route("/nested/segment.ts", get(|| async { "segment" })),
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
            format!("http://{address}/master.m3u8"),
            HashMap::new(),
        )
        .unwrap();
    let client = reqwest::Client::new();
    for root in [&media.url, media.encrypted_url.as_ref().unwrap()] {
        let body = client
            .get(root)
            .send()
            .await
            .unwrap()
            .error_for_status()
            .unwrap()
            .text()
            .await
            .unwrap();
        let child = body
            .lines()
            .find(|line| !line.starts_with('#') && !line.is_empty())
            .unwrap();
        let child = if child.starts_with('/') {
            format!("{}{child}", proxy.base_url("127.0.0.1"))
        } else {
            child.into()
        };
        let reply = client
            .get(child)
            .send()
            .await
            .unwrap()
            .error_for_status()
            .unwrap();
        assert!(!reply
            .headers()
            .contains_key("x-playbridge-internal-effective-url"));
        assert_eq!(reply.text().await.unwrap(), "segment");
    }
    proxy.shutdown().await.unwrap();
    task.abort();
}
