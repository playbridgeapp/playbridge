use super::*;
use serde_json::json;
use wiremock::{
    Mock, MockServer, ResponseTemplate,
    matchers::{header, method, path},
};

fn response(action: &str, fields: &str) -> String {
    format!("<Envelope><Body><{action}Response>{fields}</{action}Response></Body></Envelope>")
}

async fn renderer() -> MockServer {
    let server = MockServer::start().await;
    Mock::given(method("GET")).and(path("/device.xml"))
        .respond_with(ResponseTemplate::new(200).set_body_string("<root><device><friendlyName>Test TV</friendlyName><serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/control</controlURL></service></serviceList></device></root>"))
        .mount(&server).await;
    server
}

fn action(name: &str, fields: &str) -> Mock {
    Mock::given(header(
        "SOAPAction",
        format!("\"urn:schemas-upnp-org:service:AVTransport:1#{name}\""),
    ))
    .respond_with(ResponseTemplate::new(200).set_body_string(response(name, fields)))
}

fn target(server: &MockServer) -> SessionTarget {
    serde_json::from_value(json!({"protocol":"dlna", "location":format!("{}/device.xml", server.uri()), "network_handle":42})).unwrap()
}

fn submit(session: &CastSession, command: Value) {
    assert!(session.submit(serde_json::from_value(command).unwrap()));
}

fn next(session: &CastSession) -> Value {
    serde_json::to_value(session.next_event(3000).expect("worker event")).unwrap()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn dlna_worker_keeps_single_timeout_and_http_fault_request_scoped() {
    let server = renderer().await;
    Mock::given(header(
        "SOAPAction",
        "\"urn:schemas-upnp-org:service:AVTransport:1#Pause\"",
    ))
    .respond_with(
        ResponseTemplate::new(200)
            .set_delay(Duration::from_secs(2))
            .set_body_string(response("Pause", "")),
    )
    .expect(1)
    .mount(&server)
    .await;
    Mock::given(header("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#Play\""))
        .respond_with(ResponseTemplate::new(500).set_body_string("<Envelope><Body><Fault><detail><UPnPError><errorCode>501</errorCode><errorDescription>Rejected https://example.test/secret?token=private</errorDescription></UPnPError></detail></Fault></Body></Envelope>"))
        .expect(1).mount(&server).await;
    action("Stop", "").expect(1).mount(&server).await;
    let session = CastSession::start(target(&server), 250).unwrap();
    assert_eq!(next(&session)["event"], "connected");
    submit(&session, json!({"command":"pause", "request_id":"pause"}));
    let timeout = next(&session);
    assert_eq!(timeout["event"], "error");
    assert_eq!(timeout["reason"], "action_failed");
    assert_eq!(timeout["request_id"], "pause");
    submit(&session, json!({"command":"play", "request_id":"play"}));
    let fault = next(&session);
    assert_eq!(fault["reason"], "action_failed");
    assert_eq!(
        fault["upnp"],
        json!({"action":"Play", "code":501, "http_status":500, "description":"Rejected [URL]"})
    );
    assert!(!fault.to_string().contains("private"));
    submit(&session, json!({"command":"stop", "request_id":"stop"}));
    assert_eq!(
        next(&session),
        json!({"event":"operation", "request_id":"stop", "operation":"stop", "ok":true})
    );
    submit(
        &session,
        json!({"command":"disconnect", "request_id":"bye"}),
    );
    assert_eq!(next(&session)["event"], "operation");
    assert_eq!(next(&session)["reason"], "disconnected");
    server.verify().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn dlna_load_media_facts_status_round_trip_preserves_current_load_only() {
    let server = renderer().await;
    action("SetAVTransportURI", "")
        .expect(2)
        .mount(&server)
        .await;
    action("Play", "").expect(2).mount(&server).await;
    action(
        "GetTransportInfo",
        "<CurrentTransportState>PLAYING</CurrentTransportState>",
    )
    .mount(&server)
    .await;
    action(
        "GetPositionInfo",
        "<TrackDuration>00:00:00</TrackDuration><RelTime>00:00:03</RelTime>",
    )
    .mount(&server)
    .await;
    action("GetMediaInfo", "<MediaDuration>00:00:00</MediaDuration>")
        .expect(2)
        .mount(&server)
        .await;
    let session = CastSession::start(target(&server), 1000).unwrap();
    let connected = next(&session);
    assert_eq!(connected["name"], "Test TV");
    assert_eq!(connected["capabilities"]["volume"], false);
    submit(
        &session,
        json!({"command":"load", "request_id":"load", "url":"http://media.test/movie.mp4", "duration_seconds":10.0, "stream_type":"BUFFERED"}),
    );
    assert_eq!(next(&session)["event"], "operation");
    submit(
        &session,
        json!({"command":"media_facts", "request_id":"facts", "is_live":false, "duration_seconds":60.0}),
    );
    assert_eq!(
        next(&session),
        json!({"event":"operation", "request_id":"facts", "operation":"media_facts", "ok":true})
    );
    submit(&session, json!({"command":"status", "request_id":"status"}));
    assert_eq!(
        next(&session)["status"],
        json!({"state":"playing", "position_seconds":3.0, "duration_seconds":60.0, "is_live":false, "volume_supported":false})
    );
    submit(
        &session,
        json!({"command":"media_facts", "request_id":"live", "is_live":true}),
    );
    assert_eq!(next(&session)["event"], "operation");
    submit(
        &session,
        json!({"command":"status", "request_id":"live-status"}),
    );
    let status = next(&session);
    assert_eq!(status["status"]["is_live"], true);
    assert_eq!(status["status"]["duration_seconds"], 0.0);
    submit(
        &session,
        json!({"command":"load", "request_id":"next-load", "url":"http://media.test/next.mp4"}),
    );
    assert_eq!(next(&session)["event"], "operation");
    submit(
        &session,
        json!({"command":"status", "request_id":"next-status"}),
    );
    let status = next(&session);
    assert_eq!(status["status"]["is_live"], false);
    assert_eq!(status["status"]["duration_seconds"], 0.0);
    submit(
        &session,
        json!({"command":"disconnect", "request_id":"bye"}),
    );
    assert_eq!(next(&session)["event"], "operation");
    assert_eq!(next(&session)["reason"], "disconnected");
    server.verify().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn dlna_worker_ends_only_after_three_consecutive_transport_losses() {
    let server = renderer().await;
    Mock::given(header(
        "SOAPAction",
        "\"urn:schemas-upnp-org:service:AVTransport:1#Pause\"",
    ))
    .respond_with(
        ResponseTemplate::new(200)
            .set_delay(Duration::from_secs(2))
            .set_body_string(response("Pause", "")),
    )
    .expect(3)
    .mount(&server)
    .await;
    let session = CastSession::start(target(&server), 250).unwrap();
    assert_eq!(next(&session)["event"], "connected");
    for id in 1..=3 {
        submit(&session, json!({"command":"pause", "request_id":id}));
        let error = next(&session);
        assert_eq!(error["request_id"], id);
        assert_eq!(error["reason"], "action_failed");
    }
    assert_eq!(next(&session)["reason"], "connection_lost");
    server.verify().await;
}

#[test]
fn transport_failure_streak_resets_after_success_or_soap_fault() {
    let mut failures = 0;
    let timeout = || Err(playbridge_cast_core::upnp::action_timeout("Play"));
    assert_eq!(
        command_result_finish_reason(SessionProtocol::Dlna, "play", &timeout(), &mut failures),
        None
    );
    assert_eq!(failures, 1);
    assert_eq!(
        command_result_finish_reason(
            SessionProtocol::Dlna,
            "media_facts",
            &Ok(None),
            &mut failures
        ),
        None
    );
    assert_eq!(failures, 1); // Local facts cannot prove transport recovery.
    assert_eq!(
        command_result_finish_reason(SessionProtocol::Dlna, "play", &Ok(None), &mut failures),
        None
    );
    assert_eq!(failures, 0);
    command_result_finish_reason(SessionProtocol::Dlna, "play", &timeout(), &mut failures);
    let fault = Err(CastError::UpnpAction(Box::new(ActionFailure {
        action: "Play".into(),
        code: Some(501),
        http_status: Some(500),
        description: "Action Failed".into(),
        transport_failure: false,
    })));
    assert_eq!(
        command_result_finish_reason(SessionProtocol::Dlna, "play", &fault, &mut failures),
        None
    );
    assert_eq!(failures, 0);
    let error = Err(CastError::Transport("closed".into()));
    assert_eq!(
        command_result_finish_reason(SessionProtocol::GoogleCast, "play", &error, &mut failures),
        Some("connection_lost")
    );
}

#[test]
fn additive_load_fields_and_media_facts_preserve_old_json_defaults() {
    let command: SessionCommand = serde_json::from_value(
        json!({"command":"load", "request_id":"old", "url":"http://media.test/movie.mp4"}),
    )
    .unwrap();
    let SessionCommand::Load { dlna, .. } = command else {
        unreachable!()
    };
    assert!(!dlna.is_screen_mirror);
    assert!(dlna.fallback_url.is_none());
    assert!(dlna.fallback_content_type.is_none());
    let command: SessionCommand = serde_json::from_value(json!({"command":"load", "request_id":"mirror", "url":"http://media.test/ts", "is_screen_mirror":true, "fallback_url":"http://media.test/hls.m3u8", "fallback_content_type":"application/x-mpegURL"})).unwrap();
    let SessionCommand::Load { dlna, .. } = command else {
        unreachable!()
    };
    assert!(dlna.is_screen_mirror);
    assert_eq!(
        dlna.fallback_url.as_deref(),
        Some("http://media.test/hls.m3u8")
    );
    let facts: SessionCommand =
        serde_json::from_value(json!({"command":"media_facts", "request_id":"facts"})).unwrap();
    assert_eq!(facts.operation(), "media_facts");
    assert!(facts.has_valid_request_id());
}

#[test]
fn network_handle_accepts_java_signed_longs_and_existing_unsigned_json() {
    for (value, expected) in [
        (json!(42), Some(42)),
        (json!(-1), Some(u64::MAX)),
        (json!(u64::MAX), Some(u64::MAX)),
        (Value::Null, None),
    ] {
        let target: SessionTarget = serde_json::from_value(json!({"protocol":"dlna", "location":"http://renderer.test/device.xml", "network_handle":value})).unwrap();
        assert_eq!(target.network_handle, expected);
    }
}
