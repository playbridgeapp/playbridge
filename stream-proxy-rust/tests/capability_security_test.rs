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
async fn loopback_playlist_cannot_access_another_local_port() {
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
        let reply = client.get(child).send().await.unwrap();
        // Loopback grants are exact-origin. A playlist on :root must not fetch
        // another local port; public cross-CDN children remain allowed.
        assert!(
            reply.status() == StatusCode::FORBIDDEN || reply.status() == StatusCode::BAD_GATEWAY,
            "loopback cross-port child: {}",
            reply.status()
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

#[tokio::test]
async fn encrypted_roots_and_manifest_children_are_revoked_with_their_playback_owner() {
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
                            [(header::CONTENT_TYPE, "application/vnd.apple.mpegurl")],
                            "#EXTM3U\n#EXTINF:2,\nsegment.ts\n",
                        )
                    }),
                )
                .route("/segment.ts", get(|| async { "segment" })),
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
    assert!(proxy.service().renew(&media.id));
    let encrypted = media.encrypted_url.as_ref().unwrap();
    let client = reqwest::Client::new();
    let playlist = client
        .get(encrypted)
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .text()
        .await
        .unwrap();
    let child = playlist
        .lines()
        .find(|line| !line.starts_with('#') && !line.is_empty())
        .unwrap();
    let child = format!("{}{child}", proxy.base_url("127.0.0.1"));
    assert_eq!(
        client
            .get(&child)
            .send()
            .await
            .unwrap()
            .text()
            .await
            .unwrap(),
        "segment"
    );
    assert!(proxy.service().revoke(&media.id));
    assert!(!proxy.service().renew(&media.id));
    for url in [encrypted, &child] {
        let rejected = client.get(url).send().await.unwrap();
        assert_eq!(rejected.status(), StatusCode::FORBIDDEN);
        assert_eq!(rejected.text().await.unwrap(), "Invalid proxy capability");
    }
    proxy.shutdown().await.unwrap();
    task.abort();
}

#[tokio::test]
async fn embedded_listener_and_management_surface_are_not_broadcast_or_cors_enabled() {
    let proxy = ProxyServer::start(ProxyServerConfig::default())
        .await
        .unwrap();
    assert!(proxy.local_addr().ip().is_loopback());
    let client = reqwest::Client::new();
    for path in ["/", "/demo.html"] {
        assert_eq!(
            client
                .get(format!("{}{path}", proxy.base_url("127.0.0.1")))
                .send()
                .await
                .unwrap()
                .status(),
            StatusCode::NOT_FOUND
        );
    }
    let result = client
        .request(
            reqwest::Method::OPTIONS,
            format!("{}/register", proxy.base_url("127.0.0.1")),
        )
        .header("Origin", "https://unrelated.example")
        .header("Access-Control-Request-Method", "POST")
        .send()
        .await
        .unwrap();
    assert!(!result.headers().contains_key("access-control-allow-origin"));
    assert!(proxy.expose_interface("0.0.0.0").await.is_err());
    assert!(proxy.expose_interface("224.0.0.1").await.is_err());
    assert!(proxy
        .expose_interface("not-a-local-interface.invalid")
        .await
        .is_err());
    proxy.shutdown().await.unwrap();
}

#[tokio::test]
async fn extensionless_redirected_hls_uses_the_final_manifest_base() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let task = tokio::spawn(async move {
        axum::serve(
            listener,
            Router::new()
                .route(
                    "/start",
                    get(|| async { (StatusCode::FOUND, [(header::LOCATION, "/final/list")]) }),
                )
                .route(
                    "/final/list",
                    get(|| async {
                        (
                            [(header::CONTENT_TYPE, "application/vnd.apple.mpegurl")],
                            "#EXTM3U\n#EXTINF:1,\nsegment.ts\n",
                        )
                    }),
                )
                .route("/final/segment.ts", get(|| async { "segment" })),
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
            format!("http://{address}/start"),
            HashMap::new(),
        )
        .unwrap();
    let body = reqwest::get(&media.url)
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
    let child = Url::parse(&media.url).unwrap().join(child).unwrap();
    assert_eq!(
        reqwest::get(child)
            .await
            .unwrap()
            .error_for_status()
            .unwrap()
            .text()
            .await
            .unwrap(),
        "segment"
    );
    proxy.shutdown().await.unwrap();
    task.abort();
}

#[tokio::test]
async fn extensionless_and_query_hls_are_rewritten_without_mpegurl_content_type() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let task = tokio::spawn(async move {
        axum::serve(
            listener,
            Router::new().route(
                "/get.php",
                get(|| async {
                    (
                        [(header::CONTENT_TYPE, "text/plain")],
                        "#EXTM3U\n#EXTINF:1,\nsegment.ts\n",
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
    for suffix in ["get.php?type=m3u8", "get.php"] {
        let media = proxy
            .register_remote(
                "127.0.0.1",
                format!("http://{address}/{suffix}"),
                HashMap::new(),
            )
            .unwrap();
        for root in [&media.url, media.encrypted_url.as_ref().unwrap()] {
            let text = reqwest::get(root)
                .await
                .unwrap()
                .error_for_status()
                .unwrap()
                .text()
                .await
                .unwrap();
            assert!(
                text.contains("#EXTM3U"),
                "expected rewritten playlist, got {text}"
            );
            assert!(
                !text.lines().any(|line| line == "segment.ts"),
                "unrewritten body: {text}"
            );
        }
    }
    proxy.shutdown().await.unwrap();
    task.abort();
}

#[tokio::test]
async fn encrypted_root_with_literal_dollar_can_play() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let task = tokio::spawn(async move {
        axum::serve(
            listener,
            Router::new().route("/price$1.mp4", get(|| async { "media" })),
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
            format!("http://{address}/price$1.mp4"),
            HashMap::new(),
        )
        .unwrap();
    let client = reqwest::Client::new();
    assert_eq!(
        client
            .get(&media.url)
            .send()
            .await
            .unwrap()
            .error_for_status()
            .unwrap()
            .text()
            .await
            .unwrap(),
        "media"
    );
    assert_eq!(
        client
            .get(media.encrypted_url.as_ref().unwrap())
            .send()
            .await
            .unwrap()
            .error_for_status()
            .unwrap()
            .text()
            .await
            .unwrap(),
        "media"
    );
    proxy.shutdown().await.unwrap();
    task.abort();
}

#[tokio::test]
async fn dash_representation_ids_may_contain_equals() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let task = tokio::spawn(async move {
        axum::serve(
            listener,
            Router::new()
                .route(
                    "/manifest.mpd",
                    get(|| async {
                        (
                            [(header::CONTENT_TYPE, "application/dash+xml")],
                            r#"<MPD><Period><AdaptationSet><Representation id="audio_eng=64008"><SegmentTemplate media="$RepresentationID$/seg-$Number$.m4s" initialization="$RepresentationID$/init.mp4"/></Representation></AdaptationSet></Period></MPD>"#,
                        )
                    }),
                )
                .route(
                    "/audio_eng=64008/seg-1.m4s",
                    get(|| async { "segment" }),
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
            format!("http://{address}/manifest.mpd"),
            HashMap::new(),
        )
        .unwrap();
    for root in [&media.url, media.encrypted_url.as_ref().unwrap()] {
        let text = reqwest::get(root)
            .await
            .unwrap()
            .error_for_status()
            .unwrap()
            .text()
            .await
            .unwrap();
        let template = text
            .split("media=\"")
            .nth(1)
            .unwrap()
            .split('"')
            .next()
            .unwrap();
        let resolved = xml_url(template)
            .replace("$RepresentationID$", "audio_eng=64008")
            .replace("$Number$", "1");
        let resolved = if resolved.starts_with('/') {
            format!("{}{resolved}", proxy.base_url("127.0.0.1"))
        } else {
            resolved
        };
        assert_eq!(
            reqwest::get(&resolved)
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
    task.abort();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn concurrent_interface_exposure_is_idempotent() {
    let mut failures = 0;
    for _ in 0..20 {
        let proxy = Arc::new(
            ProxyServer::start(ProxyServerConfig::default())
                .await
                .unwrap(),
        );
        let barrier = Arc::new(tokio::sync::Barrier::new(3));
        let mut jobs = Vec::new();
        for _ in 0..2 {
            let proxy = proxy.clone();
            let barrier = barrier.clone();
            jobs.push(tokio::spawn(async move {
                barrier.wait().await;
                proxy.expose_interface("::1").await
            }));
        }
        barrier.wait().await;
        for job in jobs {
            if job.await.unwrap().is_err() {
                failures += 1;
            }
        }
        Arc::try_unwrap(proxy)
            .unwrap_or_else(|_| panic!("proxy still shared"))
            .shutdown()
            .await
            .unwrap();
    }
    assert_eq!(failures, 0, "simultaneous interface exposure failures");
}

type Headers = HashMap<String, String>;

/// Fake origin: known playlist URLs return scripted bodies (optionally with an
/// effective URL after a redirect); every other URL is a segment. Segment
/// requests are recorded so tests assert on them from the test task instead of
/// panicking inside spawned fetch tasks.
struct RecordingFetcher {
    playlists: HashMap<String, (String, Option<String>)>,
    segments: std::sync::Mutex<Vec<(String, Headers)>>,
}

impl RecordingFetcher {
    fn new(playlists: &[(&str, &str, Option<&str>)]) -> Arc<Self> {
        Arc::new(Self {
            playlists: playlists
                .iter()
                .map(|(url, body, effective)| {
                    (
                        url.to_string(),
                        (body.to_string(), effective.map(str::to_string)),
                    )
                })
                .collect(),
            segments: Default::default(),
        })
    }

    fn segment_log(&self) -> Vec<(String, Headers)> {
        self.segments.lock().unwrap().clone()
    }

    async fn wait_for_segments(&self, count: usize) {
        tokio::time::timeout(std::time::Duration::from_secs(5), async {
            while self.segment_log().len() < count {
                tokio::time::sleep(std::time::Duration::from_millis(5)).await;
            }
        })
        .await
        .expect("prefetch did not reach the upstream segment");
    }
}

impl stream_proxy_rust::UpstreamFetcher for RecordingFetcher {
    fn connect_with_policy<'a>(
        &'a self,
        url: &'a str,
        headers: &'a HashMap<String, String>,
        policy: Option<stream_proxy_rust::upstream::NetworkPolicy>,
    ) -> stream_proxy_rust::upstream::UpstreamConnectFuture<'a> {
        Box::pin(async move {
            assert!(policy.is_some());
            let mut response_headers = HeaderMap::new();
            let body = if let Some((body, effective)) = self.playlists.get(url) {
                response_headers.insert(
                    header::CONTENT_TYPE,
                    "application/vnd.apple.mpegurl".parse().unwrap(),
                );
                if let Some(effective) = effective {
                    response_headers.insert(
                        "x-playbridge-internal-effective-url",
                        effective.parse().unwrap(),
                    );
                }
                body.clone()
            } else {
                self.segments
                    .lock()
                    .unwrap()
                    .push((url.to_string(), headers.clone()));
                "segment".to_string()
            };
            Ok(stream_proxy_rust::UpstreamResponse {
                status: StatusCode::OK,
                headers: response_headers,
                body: axum::body::Body::from(body),
            })
        })
    }
}

fn has_header(headers: &Headers, name: &str) -> bool {
    headers.keys().any(|key| key.eq_ignore_ascii_case(name))
}

fn header_values<'a>(headers: &'a Headers, name: &str) -> Vec<&'a str> {
    headers
        .iter()
        .filter(|(key, _)| key.eq_ignore_ascii_case(name))
        .map(|(_, value)| value.as_str())
        .collect()
}

async fn get_text(url: impl reqwest::IntoUrl) -> String {
    reqwest::get(url)
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .text()
        .await
        .unwrap()
}

fn first_uri(playlist: &str, root: &str) -> Url {
    let line = playlist
        .lines()
        .find(|line| !line.starts_with('#') && !line.is_empty())
        .unwrap();
    Url::parse(root).unwrap().join(line).unwrap()
}

fn credential_headers() -> Headers {
    HashMap::from([
        ("Authorization".into(), "secret".into()),
        ("Cookie".into(), "secret".into()),
        ("X-Custom-Secret".into(), "secret".into()),
        ("User-Agent".into(), "Fixture".into()),
    ])
}

fn root_url(media: &stream_proxy_rust::RegisteredMedia, encrypted: bool) -> String {
    if encrypted {
        media.encrypted_url.clone().unwrap()
    } else {
        media.url.clone()
    }
}

#[tokio::test]
async fn cross_cdn_children_and_prefetch_do_not_receive_original_credentials() {
    // Literal public addresses with a fake fetcher: no external server contacted.
    for redirected in [false, true] {
        for encrypted in [false, true] {
            // A fresh proxy, cache and log per root so each root's prefetch is
            // observed on its own.
            let fetcher = if redirected {
                RecordingFetcher::new(&[(
                    "http://8.8.8.8/master.m3u8",
                    "#EXTM3U\n#EXTINF:1,\nsegment.ts\n",
                    Some("http://1.1.1.1/final/list"),
                )])
            } else {
                RecordingFetcher::new(&[(
                    "http://8.8.8.8/master.m3u8",
                    "#EXTM3U\n#EXTINF:1,\nhttp://1.1.1.1/segment.ts\n",
                    None,
                )])
            };
            let expected = if redirected {
                "http://1.1.1.1/final/segment.ts"
            } else {
                "http://1.1.1.1/segment.ts"
            };
            let proxy =
                ProxyServer::start_with_fetcher(ProxyServerConfig::default(), fetcher.clone())
                    .await
                    .unwrap();
            let media = proxy
                .register_remote(
                    "127.0.0.1",
                    "http://8.8.8.8/master.m3u8",
                    credential_headers(),
                )
                .unwrap();
            let root = root_url(&media, encrypted);
            let child = first_uri(&get_text(&root).await, &root);
            // Wait for the background prefetch before foreground loading.
            fetcher.wait_for_segments(1).await;
            assert_eq!(get_text(child).await, "segment");
            let log = fetcher.segment_log();
            assert_eq!(
                log.len(),
                1,
                "foreground must be served from the prefetch cache"
            );
            for (url, headers) in &log {
                assert_eq!(url, expected);
                for name in ["authorization", "cookie", "x-custom-secret"] {
                    assert!(!has_header(headers, name), "{name} leaked cross-origin");
                }
                assert_eq!(
                    headers.get("User-Agent").map(String::as_str),
                    Some("Fixture")
                );
            }
            proxy.shutdown().await.unwrap();
        }
    }
}

#[tokio::test]
async fn byterange_prefetch_sends_a_single_range_and_serves_the_foreground_from_cache() {
    for encrypted in [false, true] {
        let fetcher = RecordingFetcher::new(&[(
            "http://8.8.8.8/master.m3u8",
            "#EXTM3U\n#EXTINF:1,\n#EXT-X-BYTERANGE:10@0\nseg.ts\n",
            None,
        )]);
        let proxy = ProxyServer::start_with_fetcher(ProxyServerConfig::default(), fetcher.clone())
            .await
            .unwrap();
        // A session-level Range must never be forwarded alongside the playlist range.
        let mut session_headers = credential_headers();
        session_headers.insert("range".into(), "bytes=500-600".into());
        let media = proxy
            .register_remote("127.0.0.1", "http://8.8.8.8/master.m3u8", session_headers)
            .unwrap();
        let root = root_url(&media, encrypted);
        let child = first_uri(&get_text(&root).await, &root);
        fetcher.wait_for_segments(1).await;
        let (_, headers) = fetcher.segment_log().remove(0);
        assert_eq!(
            header_values(&headers, "range"),
            vec!["bytes=0-9"],
            "exactly one Range header expected"
        );
        let response = reqwest::Client::new()
            .get(child)
            .header("Range", "bytes=0-9")
            .send()
            .await
            .unwrap();
        assert!(response.status().is_success());
        response.bytes().await.unwrap();
        assert_eq!(fetcher.segment_log().len(), 1, "foreground refetched");
        proxy.shutdown().await.unwrap();
    }
}

#[tokio::test]
async fn origin_a_to_cdn_b_to_origin_a_prefetch_keeps_origin_credentials() {
    for encrypted in [false, true] {
        let fetcher = RecordingFetcher::new(&[
            (
                "http://8.8.8.8/master.m3u8",
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nhttp://1.1.1.1/media.m3u8\n",
                None,
            ),
            (
                "http://1.1.1.1/media.m3u8",
                "#EXTM3U\n#EXTINF:1,\nhttp://8.8.8.8/seg0.ts\n",
                None,
            ),
        ]);
        let proxy = ProxyServer::start_with_fetcher(ProxyServerConfig::default(), fetcher.clone())
            .await
            .unwrap();
        let mut session_headers = credential_headers();
        session_headers.insert("Referer".into(), "http://8.8.8.8/page?token=1".into());
        let media = proxy
            .register_remote("127.0.0.1", "http://8.8.8.8/master.m3u8", session_headers)
            .unwrap();
        let root = root_url(&media, encrypted);
        let media_playlist_url = first_uri(&get_text(&root).await, &root);
        let segment_url = first_uri(
            &get_text(media_playlist_url.clone()).await,
            media_playlist_url.as_str(),
        );
        fetcher.wait_for_segments(1).await;
        let (url, headers) = fetcher.segment_log().remove(0);
        assert_eq!(url, "http://8.8.8.8/seg0.ts");
        assert_eq!(
            headers.get("Authorization").map(String::as_str),
            Some("secret")
        );
        assert_eq!(headers.get("Cookie").map(String::as_str), Some("secret"));
        assert_eq!(
            headers.get("Referer").map(String::as_str),
            Some("http://8.8.8.8/page?token=1")
        );
        assert_eq!(get_text(segment_url).await, "segment");
        assert_eq!(
            fetcher.segment_log().len(),
            1,
            "foreground segment must hit the prefetch cache"
        );
        proxy.shutdown().await.unwrap();
    }
}
