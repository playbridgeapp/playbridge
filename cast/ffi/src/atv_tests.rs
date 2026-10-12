//! ABI tests against loopback-only fake Remote v2 TVs. Wire fixtures are
//! assembled independently of the production encoder.
use std::{
    ffi::{CStr, CString},
    fmt,
    sync::{Arc, OnceLock},
    time::{Duration, Instant},
};

use base64::{Engine, engine::general_purpose::STANDARD};
use playbridge_cast_core::atv_remote::{AtvError, ClientIdentity, fingerprint_hex, pairing_secret};
use rustls::{
    DigitallySignedStruct, Error as TlsError, SignatureScheme,
    client::danger::HandshakeSignatureValid,
    crypto::{CryptoProvider, verify_tls12_signature, verify_tls13_signature},
    pki_types::{CertificateDer, PrivateKeyDer, UnixTime},
    server::danger::{ClientCertVerified, ClientCertVerifier},
};
use serde_json::{Value, json};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    sync::{mpsc, oneshot},
};
use tokio_rustls::{TlsAcceptor, server::TlsStream};
use x509_parser::{prelude::FromDer, public_key::PublicKey};

use crate::{atv_remote::*, pb_string_free};

struct Handle(*mut AtvRemote);
impl Handle {
    fn start() -> Self {
        let ptr = pb_atv_start();
        assert!(!ptr.is_null());
        Self(ptr)
    }
    fn raw_submit(&self, command: &str) -> bool {
        let command = CString::new(command).unwrap();
        unsafe { pb_atv_submit_json(self.0, command.as_ptr()) }
    }
    fn submit(&self, command: Value) {
        assert!(self.raw_submit(&command.to_string()));
    }
    fn next(&self) -> Value {
        self.next_with_timeout(5000)
    }
    fn next_with_timeout(&self, wait_ms: u64) -> Value {
        let ptr = unsafe { pb_atv_next_json(self.0, wait_ms) };
        assert!(!ptr.is_null(), "expected event");
        let value = serde_json::from_str(unsafe { CStr::from_ptr(ptr) }.to_str().unwrap()).unwrap();
        unsafe { pb_string_free(ptr) };
        value
    }
    fn event(&self, name: &str) -> Value {
        for _ in 0..16 {
            let value = self.next();
            if value["event"] == name {
                return value;
            }
        }
        panic!("missing {name} event")
    }
}
impl Drop for Handle {
    fn drop(&mut self) {
        unsafe { pb_atv_free(self.0) };
    }
}

fn identities() -> &'static (ClientIdentity, ClientIdentity) {
    static IDS: OnceLock<(ClientIdentity, ClientIdentity)> = OnceLock::new();
    IDS.get_or_init(|| {
        (
            ClientIdentity::generate("PlayBridge").unwrap(),
            ClientIdentity::generate("atvremote/AA:BB:CC:DD:EE:FF").unwrap(),
        )
    })
}

fn code_and_secret() -> (String, [u8; 32]) {
    fn parts(identity: &ClientIdentity) -> (Vec<u8>, Vec<u8>) {
        let (_, cert) =
            x509_parser::certificate::X509Certificate::from_der(identity.certificate_der())
                .unwrap();
        let PublicKey::RSA(key) = cert.public_key().parsed().unwrap() else {
            panic!("RSA")
        };
        (key.modulus.to_vec(), key.exponent.to_vec())
    }
    let (c, s) = identities();
    let (cn, ce) = parts(c);
    let (sn, se) = parts(s);
    (0..=255)
        .find_map(|byte| {
            let code = format!("{byte:02X}B2C3");
            pairing_secret(&cn, &ce, &sn, &se, &code)
                .ok()
                .map(|secret| (code, secret))
        })
        .unwrap()
}

fn command(name: &str, id: &str, port: u16) -> Value {
    let (client, server) = identities();
    json!({"command":name, "request_id":id, "host":"127.0.0.1", "port":port,
        "cert_pem":client.certificate_pem(), "key_pem":client.private_key_pem(), "client_name":"PlayBridge",
        "pin_sha256_hex":fingerprint_hex(&server.fingerprint_sha256())})
}

async fn listener() -> (TcpListener, u16) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    (listener, port)
}
type Stream = TlsStream<TcpStream>;
async fn accept(listener: TcpListener) -> Stream {
    let config = server_config(&identities().1, true).unwrap();
    let (tcp, _) = listener.accept().await.unwrap();
    TlsAcceptor::from(config).accept(tcp).await.unwrap()
}

fn varint(mut value: u64) -> Vec<u8> {
    let mut result = Vec::new();
    loop {
        let byte = (value & 127) as u8;
        value >>= 7;
        result.push(byte | if value == 0 { 0 } else { 128 });
        if value == 0 {
            return result;
        }
    }
}
fn int(field: u64, value: u64) -> Vec<u8> {
    [varint(field << 3), varint(value)].concat()
}
fn bytes(field: u64, value: &[u8]) -> Vec<u8> {
    [
        varint((field << 3) | 2),
        varint(value.len() as u64),
        value.to_vec(),
    ]
    .concat()
}
fn polo(field: u64, payload: &[u8]) -> Vec<u8> {
    [int(1, 2), int(2, 200), bytes(field, payload)].concat()
}
async fn write(stream: &mut Stream, payload: &[u8]) {
    stream
        .write_all(&[varint(payload.len() as u64), payload.to_vec()].concat())
        .await
        .unwrap();
}
async fn read(stream: &mut Stream) -> Vec<u8> {
    tokio::time::timeout(Duration::from_secs(5), async {
        let mut len = 0;
        let mut shift = 0;
        loop {
            let byte = stream.read_u8().await.unwrap();
            len |= ((byte & 127) as usize) << shift;
            if byte & 128 == 0 {
                break;
            }
            shift += 7;
            assert!(shift < 35);
        }
        assert!(len <= 1024 * 1024);
        let mut payload = vec![0; len];
        stream.read_exact(&mut payload).await.unwrap();
        payload
    })
    .await
    .unwrap()
}

async fn pairing_ready(listener: TcpListener) -> Stream {
    let mut stream = accept(listener).await;
    let request = read(&mut stream).await;
    assert!(request.windows(9).any(|window| window == b"atvremote"));
    write(&mut stream, &polo(11, &bytes(1, b"Living Room"))).await;
    assert_eq!(
        read(&mut stream).await,
        polo(
            20,
            &[bytes(1, &[int(1, 3), int(2, 6)].concat()), int(3, 1)].concat()
        )
    );
    write(
        &mut stream,
        &polo(
            20,
            &[bytes(2, &[int(1, 3), int(2, 6)].concat()), int(3, 2)].concat(),
        ),
    )
    .await;
    assert_eq!(
        read(&mut stream).await,
        polo(
            30,
            &[bytes(1, &[int(1, 3), int(2, 6)].concat()), int(2, 1)].concat()
        )
    );
    write(&mut stream, &polo(31, &[])).await;
    stream
}

fn assert_safe(value: &Value) {
    let text = value.to_string();
    let (client, server) = identities();
    let (code, secret) = code_and_secret();
    for forbidden in [
        "BEGIN CERTIFICATE",
        "PRIVATE KEY",
        "private-ime-text",
        "private-ime-label",
        client.private_key_pem(),
        server.certificate_pem(),
        &code,
        &STANDARD.encode(secret),
    ] {
        assert!(!text.contains(forbidden), "sensitive data in event");
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn atv_ffi_pairing_good_and_bad_code() {
    let (code, secret) = code_and_secret();
    let wrong = format!(
        "{:02X}B2C3",
        u8::from_str_radix(&code[..2], 16).unwrap() ^ 1
    );
    for (attempt, reject) in [(code.clone(), false), (wrong, false), (code.clone(), true)] {
        let local_bad = attempt != code;
        let handle = Handle::start();
        let (listener, port) = listener().await;
        let task = tokio::spawn(async move {
            let mut stream = pairing_ready(listener).await;
            if local_bad {
                let result = tokio::time::timeout(Duration::from_secs(2), stream.read_u8())
                    .await
                    .unwrap();
                assert!(result.is_err(), "local bad code sent a secret");
            } else {
                assert!(
                    read(&mut stream).await == polo(40, &bytes(1, &secret)),
                    "pairing secret frame mismatch"
                );
                if reject {
                    write(&mut stream, &[int(1, 2), int(2, 402)].concat()).await;
                } else {
                    write(&mut stream, &polo(41, &bytes(1, &secret))).await;
                }
            }
        });
        handle.submit(command("start_pairing", "start", port));
        let started = handle.next();
        assert_eq!(started["event"], "pairing_started");
        assert_eq!(started["server_name"], "Living Room");
        assert_eq!(started["mac"], "AA:BB:CC:DD:EE:FF");
        assert_eq!(
            started["fingerprint"],
            fingerprint_hex(&identities().1.fingerprint_sha256())
        );
        assert_safe(&started);
        handle.submit(json!({"command":"finish_pairing", "request_id":"finish", "code":attempt}));
        let event = handle.next();
        if reject || local_bad {
            assert_eq!(event["kind"], "bad_code");
            assert_safe(&event);
        } else {
            assert_eq!(event["event"], "paired");
            assert!(
                STANDARD
                    .decode(event["server_cert_der_b64"].as_str().unwrap())
                    .unwrap()
                    == identities().1.certificate_der(),
                "paired certificate mismatch"
            );
        }
        task.await.unwrap();
    }
}

async fn control_ready(listener: TcpListener, send_ready: bool) -> Stream {
    let mut stream = accept(listener).await;
    let info = [bytes(1, b"Bravia"), bytes(2, b"Sony")].concat();
    write(
        &mut stream,
        &bytes(1, &[int(1, 639), bytes(2, &info)].concat()),
    )
    .await;
    let configured = read(&mut stream).await;
    assert!(configured.windows(9).any(|window| window == b"atvremote"));
    write(&mut stream, &bytes(2, &int(1, 639))).await;
    assert_eq!(read(&mut stream).await, bytes(2, &int(1, 615)));
    write(
        &mut stream,
        &bytes(
            20,
            &[
                bytes(1, &bytes(12, b"com.example.player")),
                bytes(
                    2,
                    &[
                        bytes(2, b"private-ime-text"),
                        bytes(6, b"private-ime-label"),
                    ]
                    .concat(),
                ),
            ]
            .concat(),
        ),
    )
    .await;
    write(&mut stream, &bytes(21, &[int(1, 9), int(2, 4)].concat())).await;
    write(&mut stream, &bytes(50, &[int(6, 100), int(7, 12)].concat())).await;
    write(&mut stream, &bytes(8, &[int(1, 4242), int(2, 1)].concat())).await;
    assert_eq!(read(&mut stream).await, bytes(9, &int(1, 4242)));
    if send_ready {
        write(&mut stream, &bytes(40, &[])).await;
    }
    stream
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn atv_ffi_control_state_and_all_commands() {
    identities();
    let handle = Handle::start();
    let (listener, port) = listener().await;
    let (out, mut inbound) = mpsc::channel(16);
    let task = tokio::spawn(async move {
        let mut stream = control_ready(listener, true).await;
        for index in 0..11 {
            let payload = read(&mut stream).await;
            out.send(payload).await.unwrap();
            if index == 3 {
                write(
                    &mut stream,
                    &bytes(50, &[int(6, 100), int(7, 13), int(8, 1)].concat()),
                )
                .await;
            }
        }
    });
    handle.submit(command("connect", "connect", port));
    assert_eq!(
        handle.next(),
        json!({"event":"connected", "request_id":"connect"})
    );
    let state = handle.next();
    assert_eq!(state["event"], "state");
    assert_safe(&state);
    assert_eq!(
        state["state"],
        json!({"is_on":false,"volume_level":12,"volume_max":100,"volume_muted":false,"volume_revision":1,"current_app":"com.example.player","model":"Bravia","vendor":"Sony"})
    );
    let keys = [
        (json!({"name":"HOME"}), 3, 3),
        (json!({"code":19,"direction":"start_long"}), 19, 1),
        (
            json!({"name":"KEYCODE_ENTER","direction":"end_long"}),
            66,
            2,
        ),
    ];
    for (mut fields, code, direction) in keys {
        fields["command"] = json!("key");
        fields["request_id"] = json!("key");
        handle.submit(fields);
        assert_eq!(handle.event("operation")["operation"], "key");
        assert_eq!(
            inbound.recv().await.unwrap(),
            bytes(10, &[int(1, code), int(2, direction)].concat())
        );
    }
    for (action, code) in [("up", 24), ("down", 25), ("mute", 164)] {
        handle.submit(json!({"command":"volume", "request_id":"volume", "action":action}));
        assert_safe(&handle.event("operation"));
        assert_eq!(
            inbound.recv().await.unwrap(),
            bytes(10, &[int(1, code), int(2, 3)].concat())
        );
        if action == "up" {
            let state = handle.event("state");
            assert_safe(&state);
            assert_eq!(state["state"]["volume_level"], 13);
            assert_eq!(state["state"]["volume_revision"], 2);
            assert_eq!(state["state"]["volume_muted"], true);
        }
    }
    handle.submit(json!({"command":"text","request_id":"text","text":"abcd"}));
    assert_safe(&handle.event("operation"));
    let object = [int(1, 3), int(2, 3), bytes(3, b"abcd")].concat();
    assert_eq!(
        inbound.recv().await.unwrap(),
        bytes(
            21,
            &[
                int(1, 9),
                int(2, 4),
                bytes(3, &[int(1, 1), bytes(2, &object)].concat())
            ]
            .concat()
        )
    );
    for uri in ["com.google.android.youtube.tv", "https://example.test/app"] {
        handle.submit(json!({"command":"launch_app","request_id":"app","uri_or_package":uri}));
        assert_safe(&handle.event("operation"));
        let expected = if uri.contains("://") {
            uri.to_owned()
        } else {
            format!("market://launch?id={uri}")
        };
        assert_eq!(
            inbound.recv().await.unwrap(),
            bytes(90, &bytes(1, expected.as_bytes()))
        );
    }
    for _ in 0..2 {
        handle.submit(json!({"command":"power","request_id":"power"}));
        assert_safe(&handle.event("operation"));
        assert_eq!(
            inbound.recv().await.unwrap(),
            bytes(10, &[int(1, 26), int(2, 3)].concat())
        );
    }
    let closed = handle.event("closed");
    assert_eq!(closed["kind"], "closed");
    assert_safe(&closed);
    task.await.unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn atv_ffi_pin_mismatch_is_distinct() {
    identities();
    let handle = Handle::start();
    let (listener, port) = listener().await;
    let task = tokio::spawn(async move {
        let (tcp, _) = listener.accept().await.unwrap();
        let config = server_config(&identities().1, true).unwrap();
        assert!(TlsAcceptor::from(config).accept(tcp).await.is_err());
    });
    let mut connect = command("connect", "pin", port);
    connect["pin_sha256_hex"] = json!("00".repeat(32));
    handle.submit(connect);
    let error = handle.next();
    assert_eq!(error["kind"], "pin_mismatch");
    assert_safe(&error);
    task.await.unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn atv_ffi_cancel_and_free_close_connecting_socket() {
    identities();
    for free in [false, true] {
        let handle = Handle::start();
        let (listener, port) = listener().await;
        let (ready_tx, ready_rx) = oneshot::channel();
        let task = tokio::spawn(async move {
            let mut stream = control_ready(listener, false).await;
            ready_tx.send(()).unwrap();
            let result = tokio::time::timeout(Duration::from_secs(2), stream.read_u8())
                .await
                .unwrap();
            assert!(
                result.is_err(),
                "connecting reader leaked after cancel/free"
            );
        });
        handle.submit(command("connect", "pending", port));
        ready_rx.await.unwrap();
        let before = Instant::now();
        if free {
            drop(handle);
        } else {
            unsafe { pb_atv_cancel(handle.0) };
            assert!(!handle.raw_submit("{}"));
            drop(handle);
        }
        assert!(before.elapsed() < Duration::from_millis(100));
        task.await.unwrap();
    }
}

#[test]
fn atv_ffi_abi_identity_and_zero_discovery() {
    assert_eq!(pb_atv_abi_version(), 1);
    let handle = Handle::start();
    assert!(unsafe { pb_atv_next_json(handle.0, 0) }.is_null());
    handle.submit(json!({"command":"discover","request_id":7,"timeout_ms":0}));
    assert_eq!(
        handle.next(),
        json!({"event":"devices","request_id":7,"devices":[]})
    );
    handle.submit(
        json!({"command":"generate_identity","request_id":"identity","client_name":"PlayBridge"}),
    );
    let identity = handle.next_with_timeout(60_000);
    assert_eq!(identity["event"], "identity");
    let restored = ClientIdentity::from_pem(
        identity["cert_pem"].as_str().unwrap(),
        identity["key_pem"].as_str().unwrap(),
    )
    .unwrap();
    assert_eq!(
        identity["fingerprint"],
        fingerprint_hex(&restored.fingerprint_sha256())
    );
    handle.submit(json!({"command":"cancel_pairing","request_id":"cancel"}));
    assert_eq!(handle.next()["operation"], "cancel_pairing");
    handle.submit(json!({"command":"disconnect","request_id":"disconnect"}));
    assert_eq!(handle.next()["operation"], "disconnect");
    unsafe {
        pb_atv_cancel(std::ptr::null());
        pb_atv_free(std::ptr::null());
    }
    assert!(unsafe { pb_atv_next_json(std::ptr::null(), 0) }.is_null());
    assert!(!unsafe { pb_atv_submit_json(std::ptr::null(), std::ptr::null()) });
}

#[test]
fn atv_ffi_malformed_commands_never_echo_sensitive_values() {
    let handle = Handle::start();
    for command in [
        "{\"code\":\"SECRET-CODE\"",
        r#"{"command":"SECRET-CODE","request_id":"bad","key_pem":"PRIVATE KEY"}"#,
        r#"{"command":"finish_pairing","request_id":{},"code":"SECRET-CODE"}"#,
    ] {
        assert!(!handle.raw_submit(command));
        let event = handle.next();
        assert_eq!(event["kind"], "protocol");
        for secret in ["SECRET-CODE", "PRIVATE KEY"] {
            assert!(!event.to_string().contains(secret));
        }
    }
    for command in [
        json!({"command":"finish_pairing","request_id":"bad","code":"SECRET-CODE"}),
        json!({"command":"generate_identity","request_id":"bad","client_name":""}),
        json!({"command":"connect","request_id":"bad","host":"SECRET-HOST","cert_pem":"SECRET-CERT","key_pem":"SECRET-KEY","pin_sha256_hex":"aéaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}),
    ] {
        handle.submit(command);
        let event = handle.next();
        assert_eq!(event["event"], "error");
        for secret in ["SECRET-CODE", "SECRET-HOST", "SECRET-CERT", "SECRET-KEY"] {
            assert!(!event.to_string().contains(secret));
        }
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn atv_ffi_connect_replaces_pending_and_active_sessions() {
    identities();
    let handle = Handle::start();
    let (first, first_port) = listener().await;
    let (first_ready_tx, first_ready_rx) = oneshot::channel();
    let first = tokio::spawn(async move {
        let mut stream = control_ready(first, false).await;
        first_ready_tx.send(()).unwrap();
        let ended = tokio::time::timeout(Duration::from_secs(2), stream.read_u8())
            .await
            .unwrap();
        assert!(ended.is_err());
    });
    handle.submit(command("connect", "first", first_port));
    first_ready_rx.await.unwrap();
    let (second, second_port) = listener().await;
    let second = tokio::spawn(async move {
        let mut stream = control_ready(second, true).await;
        let ended = tokio::time::timeout(Duration::from_secs(3), stream.read_u8())
            .await
            .unwrap();
        assert!(ended.is_err());
    });
    handle.submit(command("connect", "second", second_port));
    assert_eq!(
        handle.next(),
        json!({"event":"connected","request_id":"second"})
    );
    assert_eq!(handle.next()["request_id"], "second");
    first.await.unwrap();

    let (third, third_port) = listener().await;
    let third = tokio::spawn(async move {
        let mut stream = control_ready(third, true).await;
        let ended = tokio::time::timeout(Duration::from_secs(3), stream.read_u8())
            .await
            .unwrap();
        assert!(ended.is_err());
    });
    handle.submit(command("connect", "third", third_port));
    assert_eq!(
        handle.next(),
        json!({"event":"closed","request_id":"second","kind":"closed"})
    );
    assert_eq!(
        handle.next(),
        json!({"event":"connected","request_id":"third"})
    );
    assert_eq!(handle.next()["request_id"], "third");
    second.await.unwrap();
    handle.submit(json!({"command":"disconnect","request_id":"bye"}));
    assert_eq!(
        handle.next(),
        json!({"event":"closed","request_id":"third","kind":"closed"})
    );
    assert_eq!(
        handle.next(),
        json!({"event":"operation","request_id":"bye","operation":"disconnect","ok":true})
    );
    third.await.unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn atv_ffi_cancel_pairing_pending_and_started() {
    identities();
    for started in [false, true] {
        let handle = Handle::start();
        let (listener, port) = listener().await;
        let (ready_tx, ready_rx) = oneshot::channel();
        let task = tokio::spawn(async move {
            let mut stream = if started {
                pairing_ready(listener).await
            } else {
                accept(listener).await
            };
            if !started {
                let _ = read(&mut stream).await;
            }
            ready_tx.send(()).unwrap();
            let ended = tokio::time::timeout(Duration::from_secs(2), stream.read_u8())
                .await
                .unwrap();
            assert!(ended.is_err());
        });
        handle.submit(command("start_pairing", "start", port));
        ready_rx.await.unwrap();
        if started {
            assert_eq!(handle.next()["event"], "pairing_started");
        }
        handle.submit(json!({"command":"cancel_pairing","request_id":"cancel"}));
        assert_eq!(
            handle.next(),
            json!({"event":"operation","request_id":"cancel","operation":"cancel_pairing","ok":true})
        );
        handle.submit(json!({"command":"finish_pairing","request_id":"late","code":"SECRET-CODE"}));
        let error = handle.next();
        assert_eq!(error["kind"], "protocol");
        assert_safe(&error);
        task.await.unwrap();
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn atv_ffi_not_trusted_keeps_pair_again_kind() {
    identities();
    let handle = Handle::start();
    let (listener, port) = listener().await;
    let task = tokio::spawn(async move {
        let (tcp, _) = listener.accept().await.unwrap();
        let config = server_config(&identities().1, false).unwrap();
        assert!(TlsAcceptor::from(config).accept(tcp).await.is_err());
    });
    handle.submit(command("connect", "trust", port));
    let error = handle.next();
    assert_eq!(error["kind"], "not_trusted");
    assert_safe(&error);
    task.await.unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn atv_ffi_cancel_during_tls_handshake() {
    identities();
    let handle = Handle::start();
    let (listener, port) = listener().await;
    let (ready_tx, ready_rx) = oneshot::channel();
    let task = tokio::spawn(async move {
        let (mut tcp, _) = listener.accept().await.unwrap();
        let mut hello = [0; 4096];
        assert!(tcp.read(&mut hello).await.unwrap() > 0);
        ready_tx.send(()).unwrap();
        let end = tokio::time::timeout(Duration::from_secs(2), tcp.read(&mut hello))
            .await
            .unwrap();
        assert!(matches!(end, Ok(0)) || end.is_err());
    });
    handle.submit(command("connect", "tls", port));
    ready_rx.await.unwrap();
    let before = Instant::now();
    unsafe { pb_atv_cancel(handle.0) };
    drop(handle);
    assert!(before.elapsed() < Duration::from_millis(100));
    task.await.unwrap();
}

pub(crate) fn server_config(
    identity: &ClientIdentity,
    trust_client: bool,
) -> Result<Arc<rustls::ServerConfig>, AtvError> {
    let provider = Arc::new(rustls::crypto::aws_lc_rs::default_provider());
    let verifier: Arc<dyn ClientCertVerifier> = Arc::new(TestClientVerifier {
        provider: Arc::clone(&provider),
        trust_client,
    });
    let certificate = CertificateDer::from(identity.certificate_der().to_vec());
    let key = PrivateKeyDer::Pkcs8(identity.private_key_der().to_vec().into());
    let config = rustls::ServerConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|error| AtvError::TlsHandshake(error.to_string()))?
        .with_client_cert_verifier(verifier)
        .with_single_cert(vec![certificate], key)
        .map_err(|_| AtvError::TlsHandshake("server certificate was rejected".to_owned()))?;
    Ok(Arc::new(config))
}

struct TestClientVerifier {
    provider: Arc<CryptoProvider>,
    trust_client: bool,
}

impl fmt::Debug for TestClientVerifier {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("TestClientVerifier")
            .finish_non_exhaustive()
    }
}

impl ClientCertVerifier for TestClientVerifier {
    fn offer_client_auth(&self) -> bool {
        true
    }

    fn client_auth_mandatory(&self) -> bool {
        true
    }

    fn root_hint_subjects(&self) -> &[rustls::DistinguishedName] {
        &[]
    }

    fn verify_client_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _now: UnixTime,
    ) -> Result<ClientCertVerified, TlsError> {
        if self.trust_client {
            Ok(ClientCertVerified::assertion())
        } else {
            Err(TlsError::InvalidCertificate(
                rustls::CertificateError::UnknownIssuer,
            ))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}
