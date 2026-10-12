use std::sync::OnceLock;
use std::time::Duration;

use tokio::net::TcpListener;
use tokio::sync::oneshot;
use tokio_rustls::TlsAcceptor;

use super::AtvError;
use super::control::{ControlSession, normalize_app_link};
use super::framed::AtvStream;
use super::identity::{
    ClientIdentity, fingerprint_sha256, pairing_digest, pairing_secret, rsa_components,
};
use super::pairing::start_pairing_timeout;
use super::proto::{
    CLIENT_FEATURES, DIR_SHORT, KEY_DPAD_UP, RemoteEvent, STATUS_BAD_SECRET, decode_app_link,
    decode_key, decode_outer, decode_ping_response, decode_remote, encode_configuration_ack,
    encode_configure_request, encode_frame, encode_ime_batch, encode_ime_key,
    encode_pairing_request_ack, encode_ping_request, encode_secret, encode_secret_ack,
    encode_server_options, encode_set_active, encode_start, encode_status, encode_volume,
};
use super::tls::{self, AtvTimeouts};

fn identities() -> &'static (ClientIdentity, ClientIdentity) {
    static IDS: OnceLock<(ClientIdentity, ClientIdentity)> = OnceLock::new();
    IDS.get_or_init(|| {
        (
            ClientIdentity::generate("PlayBridge").expect("client identity"),
            ClientIdentity::generate("atvremote/AA:BB:CC:DD:EE:FF").expect("server identity"),
        )
    })
}

fn fast() -> AtvTimeouts {
    AtvTimeouts {
        connect: Duration::from_secs(3),
        handshake: Duration::from_secs(3),
        idle: Duration::from_secs(3),
    }
}

async fn bind() -> (TcpListener, u16) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    (listener, port)
}

fn code_for(nonce_hex: &str) -> String {
    let (client, server) = identities();
    let client_parts = rsa_components(client.certificate_der()).unwrap();
    let server_parts = rsa_components(server.certificate_der()).unwrap();
    let nonce = decode_hex(nonce_hex);
    let digest = pairing_digest(
        &client_parts.modulus,
        &client_parts.exponent,
        &server_parts.modulus,
        &server_parts.exponent,
        &nonce,
    )
    .unwrap();
    format!("{:02X}{nonce_hex}", digest[0])
}

fn decode_hex(hex: &str) -> Vec<u8> {
    (0..hex.len())
        .step_by(2)
        .map(|index| u8::from_str_radix(&hex[index..index + 2], 16).unwrap())
        .collect()
}

async fn pairing_server(listener: TcpListener, reject_secret: bool) -> Result<Vec<u8>, String> {
    let (_, server_identity) = identities();
    let config = tls::server_config(server_identity, true).map_err(|error| error.to_string())?;
    let (tcp, _) = listener.accept().await.map_err(|error| error.to_string())?;
    let tls = TlsAcceptor::from(config)
        .accept(tcp)
        .await
        .map_err(|error| error.to_string())?;
    let mut stream = AtvStream::new(tls);
    let step = Duration::from_secs(3);
    let request = stream
        .read_frame(step)
        .await
        .map_err(|error| error.to_string())?;
    let message = decode_outer(&request).map_err(|error| error.to_string())?;
    let pairing = message.pairing_request.ok_or("missing pairing request")?;
    if pairing.service_name != "atvremote" {
        return Err(format!("service {}", pairing.service_name));
    }
    stream
        .write_frame(&encode_pairing_request_ack("Living Room"), step)
        .await
        .map_err(|error| error.to_string())?;
    let options = stream
        .read_frame(step)
        .await
        .map_err(|error| error.to_string())?;
    if !decode_outer(&options)
        .map_err(|error| error.to_string())?
        .options
    {
        return Err("client options missing".to_owned());
    }
    stream
        .write_frame(&encode_server_options(), step)
        .await
        .map_err(|error| error.to_string())?;
    let configuration = stream
        .read_frame(step)
        .await
        .map_err(|error| error.to_string())?;
    if !decode_outer(&configuration)
        .map_err(|error| error.to_string())?
        .configuration
    {
        return Err("client configuration missing".to_owned());
    }
    stream
        .write_frame(&encode_configuration_ack(), step)
        .await
        .map_err(|error| error.to_string())?;
    let secret_frame = stream
        .read_frame(step)
        .await
        .map_err(|error| error.to_string())?;
    let secret = decode_outer(&secret_frame)
        .map_err(|error| error.to_string())?
        .secret
        .ok_or("missing secret")?;
    if reject_secret {
        stream
            .write_frame(&encode_status(STATUS_BAD_SECRET), step)
            .await
            .map_err(|error| error.to_string())?;
    } else {
        stream
            .write_frame(&encode_secret_ack(&secret), step)
            .await
            .map_err(|error| error.to_string())?;
    }
    Ok(secret)
}

#[tokio::test]
async fn loopback_pairing_accepts_a_matching_code() {
    let (client, server) = identities();
    let (listener, port) = bind().await;
    let server_task = tokio::spawn(async move { pairing_server(listener, false).await });
    let session = start_pairing_timeout("127.0.0.1", port, client, "PlayBridge", fast())
        .await
        .unwrap();
    assert_eq!(session.mac(), Some("AA:BB:CC:DD:EE:FF"));
    assert_eq!(session.server_name(), Some("Living Room"));
    assert_eq!(session.fingerprint_sha256(), server.fingerprint_sha256());
    let code = code_for("B2C3");
    let result = session.finish(&code).await.unwrap();
    assert_eq!(result.mac.as_deref(), Some("AA:BB:CC:DD:EE:FF"));
    let wire = server_task.await.unwrap().unwrap();
    let client_parts = rsa_components(client.certificate_der()).unwrap();
    let server_parts = rsa_components(server.certificate_der()).unwrap();
    let expected = pairing_secret(
        &client_parts.modulus,
        &client_parts.exponent,
        &server_parts.modulus,
        &server_parts.exponent,
        &code,
    )
    .unwrap();
    assert_eq!(wire, expected);
}

#[tokio::test]
async fn local_bad_code_is_rejected_before_send() {
    let (client, _) = identities();
    let (listener, port) = bind().await;
    let server_task = tokio::spawn(async move { pairing_server(listener, false).await });
    let session = start_pairing_timeout("127.0.0.1", port, client, "PlayBridge", fast())
        .await
        .unwrap();
    let code = code_for("B2C3");
    let first = u8::from_str_radix(&code[..2], 16).unwrap() ^ 0xff;
    let wrong = format!("{first:02X}{}", &code[2..]);
    let error = session.finish(&wrong).await.unwrap_err();
    assert_eq!(error, AtvError::BadCode);
    let server_result = server_task.await.unwrap();
    assert!(server_result.is_err());
}

#[tokio::test]
async fn tv_bad_secret_is_bad_code() {
    let (client, _) = identities();
    let (listener, port) = bind().await;
    let server_task = tokio::spawn(async move { pairing_server(listener, true).await });
    let session = start_pairing_timeout("127.0.0.1", port, client, "PlayBridge", fast())
        .await
        .unwrap();
    let error = session.finish(&code_for("B2C3")).await.unwrap_err();
    assert_eq!(error, AtvError::BadCode);
    server_task.await.unwrap().unwrap();
}

async fn control_handshake(
    listener: TcpListener,
) -> Result<AtvStream<tokio_rustls::server::TlsStream<tokio::net::TcpStream>>, String> {
    let (_, server_identity) = identities();
    let config = tls::server_config(server_identity, true).map_err(|error| error.to_string())?;
    let (tcp, _) = listener.accept().await.map_err(|error| error.to_string())?;
    let tls = TlsAcceptor::from(config)
        .accept(tcp)
        .await
        .map_err(|error| error.to_string())?;
    let mut stream = AtvStream::new(tls);
    let step = Duration::from_secs(3);
    let offered = CLIENT_FEATURES | 8 | 16;
    stream
        .write_frame(
            &encode_configure_request(offered, "Sony", "Bravia", "9"),
            step,
        )
        .await
        .map_err(|error| error.to_string())?;
    let reply = stream
        .read_frame(step)
        .await
        .map_err(|error| error.to_string())?;
    if !reply.windows(9).any(|window| window == b"atvremote")
        || !reply.windows(5).any(|window| window == b"1.0.0")
    {
        return Err("configure reply device info mismatch".to_owned());
    }
    match decode_remote(&reply)
        .map_err(|error| error.to_string())?
        .as_slice()
    {
        [RemoteEvent::Configure { code1, .. }] if *code1 == CLIENT_FEATURES => {}
        other => return Err(format!("configure reply {other:?}")),
    }
    stream
        .write_frame(&encode_set_active(offered), step)
        .await
        .map_err(|error| error.to_string())?;
    let active = stream
        .read_frame(step)
        .await
        .map_err(|error| error.to_string())?;
    match decode_remote(&active)
        .map_err(|error| error.to_string())?
        .as_slice()
    {
        [RemoteEvent::SetActive { active }] if *active == CLIENT_FEATURES => {}
        other => return Err(format!("set active reply {other:?}")),
    }
    stream
        .write_frame(&encode_ime_key("com.example.player", "hello"), step)
        .await
        .map_err(|error| error.to_string())?;
    stream
        .write_frame(&encode_volume(4, 20, true), step)
        .await
        .map_err(|error| error.to_string())?;
    stream
        .write_frame(&encode_ping_request(7, 99), step)
        .await
        .map_err(|error| error.to_string())?;
    let pong = stream
        .read_frame(step)
        .await
        .map_err(|error| error.to_string())?;
    if decode_ping_response(&pong).map_err(|error| error.to_string())? != Some(7) {
        return Err("ping response val1 mismatch".to_owned());
    }
    stream
        .write_frame(&encode_start(true), step)
        .await
        .map_err(|error| error.to_string())?;
    Ok(stream)
}

async fn control_ready(
    listener: TcpListener,
    level: u32,
    max: u32,
) -> Result<AtvStream<tokio_rustls::server::TlsStream<tokio::net::TcpStream>>, String> {
    let (_, server_identity) = identities();
    let config = tls::server_config(server_identity, true).map_err(|error| error.to_string())?;
    let (tcp, _) = listener.accept().await.map_err(|error| error.to_string())?;
    let tls = TlsAcceptor::from(config)
        .accept(tcp)
        .await
        .map_err(|error| error.to_string())?;
    let mut stream = AtvStream::new(tls);
    let step = Duration::from_secs(3);
    let offered = CLIENT_FEATURES | 8 | 16;
    stream
        .write_frame(
            &encode_configure_request(offered, "Sony", "Hisense", "1"),
            step,
        )
        .await
        .map_err(|error| error.to_string())?;
    stream
        .read_frame(step)
        .await
        .map_err(|error| error.to_string())?;
    stream
        .write_frame(&encode_set_active(offered), step)
        .await
        .map_err(|error| error.to_string())?;
    stream
        .read_frame(step)
        .await
        .map_err(|error| error.to_string())?;
    stream
        .write_frame(&encode_volume(level, max, false), step)
        .await
        .map_err(|error| error.to_string())?;
    stream
        .write_frame(&encode_start(true), step)
        .await
        .map_err(|error| error.to_string())?;
    Ok(stream)
}

#[tokio::test]
async fn loopback_control_tracks_state_and_commands() {
    let (client, server) = identities();
    let (listener, port) = bind().await;
    let (done_tx, done_rx) = oneshot::channel();
    tokio::spawn(async move {
        let result = async {
            let mut stream = control_handshake(listener).await?;
            let step = Duration::from_secs(3);
            let mut saw_key = false;
            let mut saw_text = false;
            let mut saw_app = false;
            for _ in 0..6 {
                let frame = stream
                    .read_frame(step)
                    .await
                    .map_err(|error| error.to_string())?;
                if let Some((key, direction)) =
                    decode_key(&frame).map_err(|error| error.to_string())?
                    && key == KEY_DPAD_UP
                    && direction == DIR_SHORT
                {
                    saw_key = true;
                }
                if let Some(uri) = decode_app_link(&frame).map_err(|error| error.to_string())? {
                    if uri != "https://www.youtube.com" {
                        return Err(format!("app link {uri}"));
                    }
                    saw_app = true;
                }
                for event in decode_remote(&frame).map_err(|error| error.to_string())? {
                    if let RemoteEvent::Ime {
                        text,
                        ime_counter,
                        field_counter,
                        ..
                    } = event
                    {
                        let text = text.ok_or("missing ime text")?;
                        if text.value.as_deref() != Some("hi")
                            || text.start != Some(1)
                            || text.end != Some(1)
                        {
                            return Err(format!("ime text {text:?}"));
                        }
                        if ime_counter != Some(0) || field_counter != Some(0) {
                            return Err("ime counters were not the defaults".to_owned());
                        }
                        saw_text = true;
                    }
                }
                if saw_key && saw_text && saw_app {
                    return Ok(());
                }
            }
            Err("client commands were incomplete".to_owned())
        }
        .await;
        let _ = done_tx.send(result);
    });
    let mut session = ControlSession::connect_timeout(
        "127.0.0.1",
        port,
        client,
        server.fingerprint_sha256(),
        fast(),
    )
    .await
    .unwrap();
    let state = session.state();
    assert!(state.is_on);
    assert_eq!(state.volume_level, Some(4));
    assert_eq!(state.volume_max, Some(20));
    assert!(state.volume_muted);
    assert_eq!(state.current_app.as_deref(), Some("com.example.player"));
    assert_eq!(state.ime_text.as_deref(), Some("hello"));
    assert_eq!(state.model.as_deref(), Some("Bravia"));
    assert_eq!(state.vendor.as_deref(), Some("Sony"));
    assert!(matches!(
        session.insert_text("").await,
        Err(AtvError::Protocol(_))
    ));
    session.key(KEY_DPAD_UP, DIR_SHORT).await.unwrap();
    session.insert_text("hi").await.unwrap();
    session.launch_app("https://www.youtube.com").await.unwrap();
    done_rx.await.unwrap().unwrap();
    session.shutdown().await.unwrap();
}

#[tokio::test]
async fn pin_mismatch_is_distinct() {
    let (client, _) = identities();
    let (listener, port) = bind().await;
    tokio::spawn(async move {
        let _ = control_handshake(listener).await;
    });
    let error = ControlSession::connect_timeout("127.0.0.1", port, client, [0; 32], fast())
        .await
        .unwrap_err();
    assert_eq!(error, AtvError::PinMismatch);
}

#[tokio::test]
async fn untrusted_client_is_distinct() {
    let (client, server) = identities();
    let (listener, port) = bind().await;
    tokio::spawn(async move {
        let config = tls::server_config(&identities().1, false).unwrap();
        let (tcp, _) = listener.accept().await.unwrap();
        let _ = TlsAcceptor::from(config).accept(tcp).await;
    });
    let error = ControlSession::connect_timeout(
        "127.0.0.1",
        port,
        client,
        server.fingerprint_sha256(),
        fast(),
    )
    .await
    .unwrap_err();
    assert_eq!(error, AtvError::NotTrusted);
}

#[tokio::test]
async fn ping_miss_closes_the_session() {
    let (client, server) = identities();
    let (listener, port) = bind().await;
    tokio::spawn(async move {
        if let Ok(mut stream) = control_handshake(listener).await {
            let _ = stream.read_frame(Duration::from_secs(2)).await;
        }
    });
    let mut timeouts = fast();
    timeouts.idle = Duration::from_millis(400);
    let mut session = ControlSession::connect_timeout(
        "127.0.0.1",
        port,
        client,
        server.fingerprint_sha256(),
        timeouts,
    )
    .await
    .unwrap();
    assert_eq!(session.until_closed().await, AtvError::Closed);
    assert_eq!(
        session.key(KEY_DPAD_UP, DIR_SHORT).await,
        Err(AtvError::Closed)
    );
}

#[test]
fn handshake_failure_stays_a_tls_error() {
    let failure = std::io::Error::other(rustls::Error::AlertReceived(
        rustls::AlertDescription::HandshakeFailure,
    ));
    assert!(matches!(
        tls::classify_handshake(failure),
        AtvError::TlsHandshake(_)
    ));
    let rejected = std::io::Error::other(rustls::Error::AlertReceived(
        rustls::AlertDescription::CertificateUnknown,
    ));
    assert_eq!(tls::classify_handshake(rejected), AtvError::NotTrusted);
}

#[test]
fn package_id_launch_uses_market_uri() {
    assert_eq!(
        normalize_app_link("com.google.android.youtube.tv"),
        "market://launch?id=com.google.android.youtube.tv"
    );
    assert_eq!(
        normalize_app_link("https://www.youtube.com"),
        "https://www.youtube.com"
    );
    assert_eq!(
        normalize_app_link("market://launch?id=com.example"),
        "market://launch?id=com.example"
    );
}

#[test]
fn certificate_fixture_secret_matches_reference() {
    let fixture: serde_json::Value =
        serde_json::from_str(include_str!("testdata/certificate-fixture.json")).unwrap();
    let hex = |key: &str| decode_hex(fixture[key].as_str().unwrap());
    let client = hex("client_cert_der_hex");
    let server = hex("server_cert_der_hex");
    let client_parts = rsa_components(&client).unwrap();
    let server_parts = rsa_components(&server).unwrap();
    assert_eq!(client_parts.modulus.len(), 257);
    assert_eq!(server_parts.modulus.len(), 257);
    assert_eq!(client_parts.modulus[0], 0);
    assert_eq!(server_parts.modulus[0], 0);
    let code = fixture["code"].as_str().unwrap();
    let secret = pairing_secret(
        &client_parts.modulus,
        &client_parts.exponent,
        &server_parts.modulus,
        &server_parts.exponent,
        code,
    )
    .unwrap();
    assert_eq!(secret.as_slice(), hex("secret_hex"));
    assert_eq!(
        pairing_secret(
            &client_parts.modulus[1..],
            &client_parts.exponent,
            &server_parts.modulus[1..],
            &server_parts.exponent,
            &code.to_lowercase(),
        )
        .unwrap(),
        secret
    );
    assert_eq!(
        pairing_secret(
            &client_parts.modulus,
            &client_parts.exponent,
            &server_parts.modulus,
            &server_parts.exponent,
            fixture["wrong_check_code"].as_str().unwrap(),
        ),
        Err(AtvError::BadCode)
    );
    assert_eq!(
        fingerprint_sha256(&client).as_slice(),
        hex("client_cert_sha256_hex")
    );
    assert_eq!(
        fingerprint_sha256(&server).as_slice(),
        hex("server_cert_sha256_hex")
    );
    assert_eq!(
        encode_frame(&encode_secret(&secret)).unwrap(),
        hex("secret_frame_hex")
    );
}

#[tokio::test]
async fn omitted_zero_ime_counters_replace_stale_values() {
    let (client, server) = identities();
    let (listener, port) = bind().await;
    let (done_tx, done_rx) = oneshot::channel();
    tokio::spawn(async move {
        let result = async {
            let mut stream = control_handshake(listener).await?;
            let step = Duration::from_secs(3);
            stream
                .write_frame(&encode_ime_batch(9, 4), step)
                .await
                .map_err(|error| error.to_string())?;
            let key = stream
                .read_frame(step)
                .await
                .map_err(|error| error.to_string())?;
            let (code, direction) = decode_key(&key)
                .map_err(|error| error.to_string())?
                .ok_or("missing key")?;
            if code != KEY_DPAD_UP || direction != DIR_SHORT {
                return Err(format!("unexpected key {code}/{direction}"));
            }
            stream
                .write_frame(&[0xaa, 0x01, 0x00], step)
                .await
                .map_err(|error| error.to_string())?;
            stream
                .write_frame(&encode_ping_request(42, 1), step)
                .await
                .map_err(|error| error.to_string())?;
            let mut saw_pong = false;
            let mut saw_text = false;
            for _ in 0..4 {
                let frame = stream
                    .read_frame(step)
                    .await
                    .map_err(|error| error.to_string())?;
                if decode_ping_response(&frame).map_err(|error| error.to_string())? == Some(42) {
                    saw_pong = true;
                }
                for event in decode_remote(&frame).map_err(|error| error.to_string())? {
                    if let RemoteEvent::Ime {
                        ime_counter: Some(0),
                        field_counter: Some(0),
                        text: Some(text),
                        ..
                    } = event
                        && text.value.as_deref() == Some("abcd")
                        && text.start == Some(3)
                        && text.end == Some(3)
                    {
                        saw_text = true;
                    }
                }
                if saw_pong && saw_text {
                    break;
                }
            }
            if saw_pong && saw_text {
                Ok(())
            } else {
                Err(format!("pong {saw_pong} text {saw_text}"))
            }
        }
        .await;
        let _ = done_tx.send(result);
    });
    let mut session = ControlSession::connect_timeout(
        "127.0.0.1",
        port,
        client,
        server.fingerprint_sha256(),
        fast(),
    )
    .await
    .unwrap();
    let mut watch = session.watch();
    tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            if watch.borrow().ime_counter == 9 && watch.borrow().field_counter == 4 {
                break;
            }
            watch.changed().await.unwrap();
        }
    })
    .await
    .unwrap();
    session.key(KEY_DPAD_UP, DIR_SHORT).await.unwrap();
    tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            if watch.borrow().ime_counter == 0 && watch.borrow().field_counter == 0 {
                break;
            }
            watch.changed().await.unwrap();
        }
    })
    .await
    .unwrap();
    session.insert_text("abcd").await.unwrap();
    done_rx.await.unwrap().unwrap();
    session.shutdown().await.unwrap();
}

#[tokio::test]
async fn volume_probe_waits_for_the_push_after_the_write() {
    let (client, server) = identities();
    let (listener, port) = bind().await;
    tokio::spawn(async move {
        let Ok(mut stream) = control_ready(listener, 12, 100).await else {
            return;
        };
        let step = Duration::from_secs(3);
        let Ok(frame) = stream.read_frame(step).await else {
            return;
        };
        let Ok(Some((code, direction))) = decode_key(&frame) else {
            return;
        };
        if code != super::proto::KEY_VOLUME_UP || direction != DIR_SHORT {
            return;
        }
        tokio::time::sleep(Duration::from_millis(200)).await;
        let _ = stream
            .write_frame(&encode_volume(13, 100, false), step)
            .await;
        let _ = stream.read_frame(step).await;
    });
    let mut session = ControlSession::connect_timeout(
        "127.0.0.1",
        port,
        client,
        server.fingerprint_sha256(),
        fast(),
    )
    .await
    .unwrap();
    assert_eq!(session.state().volume_level, Some(12));
    let written = session.volume_up().await.unwrap();
    assert_eq!(session.state().volume_level, Some(12));
    assert_eq!(session.state().volume_revision, written);
    let (state, fresh) = session
        .wait_for_volume_revision(written, Duration::from_secs(1))
        .await;
    assert!(fresh);
    assert_eq!(state.volume_level, Some(13));
    assert_ne!(state.volume_revision, written);
    session.shutdown().await.unwrap();
}
