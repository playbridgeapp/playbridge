use super::*;
use crate::upnp::tests::{soap_fault, soap_response, test_renderer};
use wiremock::{
    Mock, MockServer, ResponseTemplate,
    matchers::{body_string_contains, header},
};

fn action(name: &str, values: &str) -> Mock {
    Mock::given(header(
        "SOAPAction",
        format!("\"urn:schemas-upnp-org:service:AVTransport:1#{name}\""),
    ))
    .respond_with(ResponseTemplate::new(200).set_body_string(soap_response(name, values)))
}

async fn mount_load(server: &MockServer) {
    action("SetAVTransportURI", "").mount(server).await;
    action("Play", "").mount(server).await;
}

#[tokio::test]
async fn buffered_resume_waits_for_playing_then_seeks_best_effort() {
    let server = MockServer::start().await;
    let renderer = test_renderer(&server, false).await;
    mount_load(&server).await;
    action(
        "GetTransportInfo",
        "<CurrentTransportState>TRANSITIONING</CurrentTransportState>",
    )
    .up_to_n_times(1)
    .with_priority(1)
    .mount(&server)
    .await;
    action(
        "GetTransportInfo",
        "<CurrentTransportState>PLAYING</CurrentTransportState>",
    )
    .with_priority(2)
    .expect(1)
    .mount(&server)
    .await;
    Mock::given(header(
        "SOAPAction",
        "\"urn:schemas-upnp-org:service:AVTransport:1#Seek\"",
    ))
    .and(body_string_contains(
        "<Unit>REL_TIME</Unit><Target>01:01:01</Target>",
    ))
    .respond_with(ResponseTemplate::new(500).set_body_string(soap_fault("701", "Seek rejected")))
    .expect(1)
    .mount(&server)
    .await;
    let mut session = ReceiverSession::Dlna(renderer);
    let mut media = MediaRequest::new("http://media.test/movie.mp4");
    media.start_seconds = 3661.9;
    session.load(&media).await.unwrap();
    let requests = server.received_requests().await.unwrap();
    let actions: Vec<_> = requests
        .iter()
        .skip(1)
        .map(|r| {
            r.headers["soapaction"]
                .to_str()
                .unwrap()
                .split('#')
                .nth(1)
                .unwrap()
                .trim_end_matches('"')
        })
        .collect();
    assert_eq!(
        actions,
        [
            "SetAVTransportURI",
            "Play",
            "GetTransportInfo",
            "GetTransportInfo",
            "Seek"
        ]
    );
    server.verify().await;
}

#[tokio::test]
async fn resume_wait_is_bounded_even_when_transport_poll_hangs() {
    let server = MockServer::start().await;
    let renderer = test_renderer(&server, false).await;
    Mock::given(header(
        "SOAPAction",
        "\"urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo\"",
    ))
    .respond_with(
        ResponseTemplate::new(200)
            .set_delay(Duration::from_secs(2))
            .set_body_string(soap_response("GetTransportInfo", "")),
    )
    .mount(&server)
    .await;
    action("Seek", "").expect(1).mount(&server).await;
    let start = Instant::now();
    resume_dlna(&renderer, 20.0, Duration::from_millis(50)).await;
    assert!(start.elapsed() < Duration::from_secs(1));
    server.verify().await;
}

#[tokio::test]
async fn live_and_mirror_loads_skip_resume_and_duration_attributes() {
    for mirror in [false, true] {
        let server = MockServer::start().await;
        let renderer = test_renderer(&server, false).await;
        mount_load(&server).await;
        let mut media = MediaRequest::new("http://media.test/stream");
        media.is_screen_mirror = mirror;
        media.stream_type = Some(if mirror { "BUFFERED" } else { "LIVE" }.into());
        media.content_type = Some("video/mp2t".into());
        media.start_seconds = 30.0;
        media.duration_seconds = Some(100.0);
        let mut session = ReceiverSession::Dlna(renderer);
        session.load(&media).await.unwrap();
        let requests = server.received_requests().await.unwrap();
        assert_eq!(requests.len(), 3); // description, SetURI, Play; no poll/Seek.
        let body = String::from_utf8_lossy(&requests[1].body);
        assert!(body.contains("DLNA.ORG_OP=00"));
        assert!(!body.contains("duration="));
        if mirror {
            assert!(body.contains("video/mpeg"));
        }
    }
}

#[tokio::test]
async fn non_mirror_hls_retries_only_501_with_empty_metadata_even_when_live() {
    for stream_type in ["LIVE", "BUFFERED"] {
        for code in [501, 714, 716] {
            let server = MockServer::start().await;
            let renderer = test_renderer(&server, false).await;
            Mock::given(body_string_contains("&lt;DIDL-Lite"))
                .respond_with(
                    ResponseTemplate::new(500)
                        .set_body_string(soap_fault(&code.to_string(), "Metadata rejected")),
                )
                .expect(1)
                .mount(&server)
                .await;
            Mock::given(body_string_contains(
                "<CurrentURIMetaData></CurrentURIMetaData>",
            ))
            .respond_with(
                ResponseTemplate::new(200).set_body_string(soap_response("SetAVTransportURI", "")),
            )
            .expect(if code == 501 { 1 } else { 0 })
            .mount(&server)
            .await;
            action("Play", "")
                .expect(if code == 501 { 1 } else { 0 })
                .mount(&server)
                .await;
            let mut media = MediaRequest::new("http://media.test/master.m3u8?token=unused");
            media.stream_type = Some(stream_type.into());
            media.fallback_url = Some("http://media.test/ignored.m3u8".into());
            let result = ReceiverSession::Dlna(renderer).load(&media).await;
            assert_eq!(result.is_ok(), code == 501);
            server.verify().await;
        }
    }
}

#[tokio::test]
async fn mirror_501_loads_live_hls_fallback_keeps_title_and_plays_once() {
    let server = MockServer::start().await;
    let renderer = test_renderer(&server, false).await;
    Mock::given(body_string_contains(
        "<CurrentURI>http://media.test/continuous</CurrentURI>",
    ))
    .and(body_string_contains("http-get:*:video/mpeg:DLNA.ORG_OP=00"))
    .respond_with(ResponseTemplate::new(500).set_body_string(soap_fault("501", "Action Failed")))
    .expect(1)
    .mount(&server)
    .await;
    Mock::given(body_string_contains(
        "<CurrentURI>http://media.test/live.m3u8</CurrentURI>",
    ))
    .and(body_string_contains(
        "http-get:*:application/x-mpegURL:DLNA.ORG_OP=00",
    ))
    .and(body_string_contains("Phone Mirror"))
    .respond_with(
        ResponseTemplate::new(200).set_body_string(soap_response("SetAVTransportURI", "")),
    )
    .expect(1)
    .mount(&server)
    .await;
    action("Play", "").expect(1).mount(&server).await;
    let mut media = MediaRequest::new("http://media.test/continuous");
    media.title = Some("Phone Mirror".into());
    media.content_type = Some("video/mp2t".into());
    media.is_screen_mirror = true;
    media.fallback_url = Some("http://media.test/live.m3u8".into());
    media.fallback_content_type = Some("application/x-mpegURL".into());
    media.start_seconds = 10.0;
    let mut session = ReceiverSession::Dlna(renderer);
    session.load(&media).await.unwrap();
    session.media_facts(Some(false), Some(100.0)).unwrap();
    let ReceiverSession::Dlna(renderer) = session else {
        unreachable!()
    };
    assert!(renderer.facts.is_live); // mirror cannot become buffered via late facts.
    assert_eq!(server.received_requests().await.unwrap().len(), 4);
    server.verify().await;
}

#[tokio::test]
async fn mirror_missing_fallback_or_other_fault_never_retries_empty_metadata() {
    for (code, fallback) in [
        (501, None),
        (714, Some("http://media.test/live.m3u8")),
        (716, Some("http://media.test/live.m3u8")),
    ] {
        let server = MockServer::start().await;
        let renderer = test_renderer(&server, false).await;
        Mock::given(body_string_contains("<CurrentURI>"))
            .respond_with(
                ResponseTemplate::new(500)
                    .set_body_string(soap_fault(&code.to_string(), "Action Failed")),
            )
            .expect(1)
            .mount(&server)
            .await;
        let mut media = MediaRequest::new("http://media.test/continuous");
        media.is_screen_mirror = true;
        media.fallback_url = fallback.map(str::to_owned);
        assert!(ReceiverSession::Dlna(renderer).load(&media).await.is_err());
        server.verify().await;
    }
}

#[tokio::test]
async fn rejected_mirror_fallback_keeps_its_fault_without_empty_metadata_or_play() {
    let server = MockServer::start().await;
    let renderer = test_renderer(&server, false).await;
    Mock::given(body_string_contains("<CurrentURI>"))
        .respond_with(
            ResponseTemplate::new(500).set_body_string(soap_fault("501", "Action Failed")),
        )
        .expect(2)
        .mount(&server)
        .await;
    let mut media = MediaRequest::new("http://media.test/continuous");
    media.is_screen_mirror = true;
    media.fallback_url = Some("http://media.test/live.m3u8".into());
    let error = ReceiverSession::Dlna(renderer)
        .load(&media)
        .await
        .unwrap_err();
    assert_eq!(action_code(&error), Some(501));
    let requests = server.received_requests().await.unwrap();
    assert_eq!(requests.len(), 3);
    assert!(String::from_utf8_lossy(&requests[2].body).contains("application/x-mpegURL"));
    assert!(
        !String::from_utf8_lossy(&requests[2].body)
            .contains("<CurrentURIMetaData></CurrentURIMetaData>")
    );
    server.verify().await;
}

#[tokio::test]
async fn media_info_fault_is_optional_and_positive_duration_is_cached() {
    let server = MockServer::start().await;
    let renderer = test_renderer(&server, false).await;
    mount_load(&server).await;
    action(
        "GetTransportInfo",
        "<CurrentTransportState>PLAYING</CurrentTransportState>",
    )
    .mount(&server)
    .await;
    action("GetPositionInfo", "<TrackDuration>00:00:00</TrackDuration>")
        .mount(&server)
        .await;
    Mock::given(header(
        "SOAPAction",
        "\"urn:schemas-upnp-org:service:AVTransport:1#GetMediaInfo\"",
    ))
    .respond_with(ResponseTemplate::new(500).set_body_string(soap_fault("501", "Action Failed")))
    .up_to_n_times(1)
    .with_priority(1)
    .mount(&server)
    .await;
    action("GetMediaInfo", "<MediaDuration>00:05:00</MediaDuration>")
        .with_priority(2)
        .expect(1)
        .mount(&server)
        .await;
    let mut session = ReceiverSession::Dlna(renderer);
    let mut media = MediaRequest::new("http://media.test/movie.mp4");
    media.duration_seconds = Some(100.0);
    session.load(&media).await.unwrap();
    assert_eq!(session.status().await.unwrap().duration_seconds, 100.0);
    for _ in 0..3 {
        assert_eq!(session.status().await.unwrap().duration_seconds, 300.0);
    }
    session.media_facts(None, Some(500.0)).unwrap();
    assert_eq!(session.status().await.unwrap().duration_seconds, 300.0);
    session.media_facts(Some(true), None).unwrap();
    let live = session.status().await.unwrap();
    assert!(live.is_live);
    assert_eq!(live.duration_seconds, 0.0);
    server.verify().await;
}

#[tokio::test]
async fn duration_prefers_track_then_cached_media_info_then_current_load_facts() {
    let server = MockServer::start().await;
    let renderer = test_renderer(&server, false).await;
    mount_load(&server).await;
    action(
        "GetTransportInfo",
        "<CurrentTransportState>PLAYING</CurrentTransportState>",
    )
    .mount(&server)
    .await;
    action(
        "GetPositionInfo",
        "<TrackDuration>00:00:00</TrackDuration><RelTime>00:00:05</RelTime>",
    )
    .up_to_n_times(1)
    .with_priority(1)
    .mount(&server)
    .await;
    action(
        "GetPositionInfo",
        "<TrackDuration>00:07:00</TrackDuration><RelTime>00:00:05</RelTime>",
    )
    .with_priority(2)
    .mount(&server)
    .await;
    action("GetMediaInfo", "<MediaDuration>00:05:00</MediaDuration>")
        .expect(1)
        .mount(&server)
        .await;
    let mut session = ReceiverSession::Dlna(renderer);
    let mut media = MediaRequest::new("http://media.test/movie.mp4");
    media.duration_seconds = Some(100.0);
    session.load(&media).await.unwrap();
    session.media_facts(None, Some(200.0)).unwrap();
    assert_eq!(session.status().await.unwrap().duration_seconds, 300.0);
    assert_eq!(session.status().await.unwrap().duration_seconds, 420.0);
    session.media_facts(Some(true), Some(800.0)).unwrap();
    let live = session.status().await.unwrap();
    assert!(live.is_live);
    assert_eq!(live.duration_seconds, 0.0);
    session.media_facts(Some(false), None).unwrap();
    assert_eq!(session.status().await.unwrap().duration_seconds, 420.0);
    server.verify().await;
}

#[tokio::test]
async fn unknown_media_duration_probes_twenty_times_and_cache_resets_on_load() {
    let server = MockServer::start().await;
    let renderer = test_renderer(&server, false).await;
    mount_load(&server).await;
    action(
        "GetTransportInfo",
        "<CurrentTransportState>PLAYING</CurrentTransportState>",
    )
    .mount(&server)
    .await;
    action(
        "GetPositionInfo",
        "<TrackDuration>NOT_IMPLEMENTED</TrackDuration>",
    )
    .mount(&server)
    .await;
    action("GetMediaInfo", "<MediaDuration>00:00:00</MediaDuration>")
        .expect(21)
        .mount(&server)
        .await;
    let mut session = ReceiverSession::Dlna(renderer);
    assert!(session.media_facts(Some(false), Some(50.0)).is_err());
    let mut media = MediaRequest::new("http://media.test/movie.mp4");
    media.duration_seconds = Some(100.0);
    session.load(&media).await.unwrap();
    for _ in 0..23 {
        assert_eq!(session.status().await.unwrap().duration_seconds, 100.0);
    }
    session.media_facts(None, Some(200.0)).unwrap();
    assert_eq!(session.status().await.unwrap().duration_seconds, 200.0);
    for duration in [-1.0, f64::NAN, f64::INFINITY] {
        assert!(session.media_facts(None, Some(duration)).is_err());
    }
    session
        .load(&MediaRequest::new("http://media.test/next.mp4"))
        .await
        .unwrap();
    assert_eq!(session.status().await.unwrap().duration_seconds, 0.0);
    server.verify().await;
}

#[test]
fn load_budget_covers_mirror_fallback_and_buffered_resume() {
    assert_eq!(DLNA_LOAD_TIMEOUT, Duration::from_secs(80));
    assert!(DLNA_LOAD_TIMEOUT >= Duration::from_secs(3 + 2 * (3 * 8 + 8) + 8 + 5));
    assert!(DLNA_LOAD_TIMEOUT >= Duration::from_secs(3 + 4 * 8 + 8 + 8 + 10 + 8 + 5));
}
