use std::fmt;
use std::io::ErrorKind;
use std::sync::{Arc, Mutex};
use std::time::Duration;

#[cfg(test)]
use rustls::server::danger::{ClientCertVerified, ClientCertVerifier};
use rustls::{
    ClientConfig, DigitallySignedStruct, Error as TlsError, SignatureScheme,
    client::danger::HandshakeSignatureValid,
    crypto::{CryptoProvider, verify_tls12_signature, verify_tls13_signature},
    pki_types::{CertificateDer, PrivateKeyDer, ServerName, UnixTime},
};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use tokio::net::TcpStream;
use tokio_rustls::{TlsConnector, client::TlsStream};

use super::error::AtvError;
use super::identity::ClientIdentity;

/// Connect, TLS, pairing-step, and idle limits.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AtvTimeouts {
    pub connect: Duration,
    pub handshake: Duration,
    pub idle: Duration,
}

impl Default for AtvTimeouts {
    fn default() -> Self {
        Self {
            connect: Duration::from_secs(5),
            handshake: Duration::from_secs(10),
            idle: Duration::from_secs(16),
        }
    }
}

pub(crate) fn normalize_host(host: &str) -> &str {
    let host = host.trim();
    host.strip_prefix('[')
        .and_then(|inner| inner.strip_suffix(']'))
        .unwrap_or(host)
}

pub(crate) async fn connect_tcp(
    host: &str,
    port: u16,
    timeout: Duration,
) -> Result<TcpStream, AtvError> {
    let host = normalize_host(host);
    if host.is_empty() {
        return Err(AtvError::Unreachable("host is empty".to_owned()));
    }
    match tokio::time::timeout(timeout, TcpStream::connect((host, port))).await {
        Ok(Ok(stream)) => {
            let _ = stream.set_nodelay(true);
            Ok(stream)
        }
        Ok(Err(error)) => Err(classify_connect(error)),
        Err(_) => Err(AtvError::Timeout),
    }
}

pub(crate) async fn connect_pairing(
    host: &str,
    port: u16,
    identity: &ClientIdentity,
    timeouts: AtvTimeouts,
) -> Result<(TlsStream<TcpStream>, Vec<u8>), AtvError> {
    let captured = Arc::new(Mutex::new(None));
    let config = client_config(identity, None, Arc::clone(&captured))?;
    let stream = connect_tls(host, port, config, timeouts).await?;
    let der = take_captured(&captured)?;
    Ok((stream, der))
}

pub(crate) async fn connect_control(
    host: &str,
    port: u16,
    identity: &ClientIdentity,
    pin: [u8; 32],
    timeouts: AtvTimeouts,
) -> Result<TlsStream<TcpStream>, AtvError> {
    let captured = Arc::new(Mutex::new(None));
    let config = client_config(identity, Some(pin), captured)?;
    connect_tls(host, port, config, timeouts).await
}

async fn connect_tls(
    host: &str,
    port: u16,
    config: ClientConfig,
    timeouts: AtvTimeouts,
) -> Result<TlsStream<TcpStream>, AtvError> {
    let host = normalize_host(host).to_owned();
    let server_name = ServerName::try_from(host.as_str())
        .map_err(|_| AtvError::Unreachable("invalid remote host".to_owned()))?
        .to_owned();
    let tcp = connect_tcp(&host, port, timeouts.connect).await?;
    let connector = TlsConnector::from(Arc::new(config));
    match tokio::time::timeout(timeouts.handshake, connector.connect(server_name, tcp)).await {
        Ok(Ok(stream)) => Ok(stream),
        Ok(Err(error)) => Err(classify_handshake(error)),
        Err(_) => Err(AtvError::Timeout),
    }
}

fn client_config(
    identity: &ClientIdentity,
    pin: Option<[u8; 32]>,
    captured: Arc<Mutex<Option<Vec<u8>>>>,
) -> Result<ClientConfig, AtvError> {
    let provider = Arc::new(rustls::crypto::aws_lc_rs::default_provider());
    let verifier = Arc::new(AtvServerVerifier {
        provider: Arc::clone(&provider),
        pin,
        captured,
    });
    let certificate = CertificateDer::from(identity.certificate_der().to_vec());
    let key = PrivateKeyDer::Pkcs8(identity.private_key_der().to_vec().into());
    ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|error| AtvError::TlsHandshake(brief(&error)))?
        .dangerous()
        .with_custom_certificate_verifier(verifier)
        .with_client_auth_cert(vec![certificate], key)
        .map_err(|_| AtvError::TlsHandshake("client certificate was rejected".to_owned()))
}

fn take_captured(captured: &Mutex<Option<Vec<u8>>>) -> Result<Vec<u8>, AtvError> {
    captured
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone()
        .ok_or_else(|| AtvError::Protocol("TV certificate was not presented".to_owned()))
}

struct AtvServerVerifier {
    provider: Arc<CryptoProvider>,
    pin: Option<[u8; 32]>,
    captured: Arc<Mutex<Option<Vec<u8>>>>,
}

impl fmt::Debug for AtvServerVerifier {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("AtvServerVerifier")
            .finish_non_exhaustive()
    }
}

impl rustls::client::danger::ServerCertVerifier for AtvServerVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<rustls::client::danger::ServerCertVerified, TlsError> {
        let der = end_entity.as_ref();
        let fingerprint: [u8; 32] = Sha256::digest(der).into();
        if let Some(pin) = &self.pin
            && fingerprint.ct_eq(pin).unwrap_u8() != 1
        {
            return Err(TlsError::General("atv pin mismatch".to_owned()));
        }
        *self
            .captured
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(der.to_vec());
        Ok(rustls::client::danger::ServerCertVerified::assertion())
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

pub(crate) fn classify_connect(error: std::io::Error) -> AtvError {
    if error.kind() == ErrorKind::TimedOut {
        return AtvError::Timeout;
    }
    if let Some(mapped) = inspect_tls(&error) {
        return mapped;
    }
    AtvError::Unreachable(format!("tcp connect failed: {}", brief(&error)))
}

pub(crate) fn classify_handshake(error: std::io::Error) -> AtvError {
    if let Some(mapped) = inspect_tls(&error) {
        return mapped;
    }
    if error.kind() == ErrorKind::TimedOut {
        return AtvError::Timeout;
    }
    AtvError::TlsHandshake(brief(&error))
}

pub(crate) fn classify_stream(error: std::io::Error, mid_frame: bool) -> AtvError {
    if let Some(mapped) = inspect_tls(&error) {
        return mapped;
    }
    match error.kind() {
        ErrorKind::TimedOut => AtvError::Timeout,
        ErrorKind::UnexpectedEof | ErrorKind::ConnectionReset | ErrorKind::BrokenPipe => {
            if mid_frame {
                AtvError::Protocol("truncated frame".to_owned())
            } else {
                AtvError::Closed
            }
        }
        ErrorKind::ConnectionRefused
        | ErrorKind::AddrNotAvailable
        | ErrorKind::NetworkUnreachable
        | ErrorKind::HostUnreachable
        | ErrorKind::NotFound => {
            AtvError::Unreachable(format!("tcp connect failed: {}", brief(&error)))
        }
        _ => AtvError::TlsHandshake(brief(&error)),
    }
}

fn inspect_tls(error: &(dyn std::error::Error + 'static)) -> Option<AtvError> {
    let mut pin_mismatch = false;
    let mut not_trusted = false;
    let mut current = Some(error);
    while let Some(item) = current {
        let text = item.to_string();
        if text.to_ascii_lowercase().contains("atv pin mismatch") {
            pin_mismatch = true;
        }
        if let Some(tls) = item.downcast_ref::<TlsError>() {
            match tls {
                TlsError::General(message) if message.contains("atv pin mismatch") => {
                    pin_mismatch = true;
                }
                TlsError::AlertReceived(alert) if trust_alert(*alert) => not_trusted = true,
                _ => {}
            }
        } else if trust_alert_text(&text) {
            not_trusted = true;
        }
        current = item.source();
    }
    if pin_mismatch {
        Some(AtvError::PinMismatch)
    } else if not_trusted {
        Some(AtvError::NotTrusted)
    } else {
        None
    }
}

fn trust_alert(alert: rustls::AlertDescription) -> bool {
    matches!(
        alert,
        rustls::AlertDescription::CertificateUnknown
            | rustls::AlertDescription::BadCertificate
            | rustls::AlertDescription::CertificateExpired
            | rustls::AlertDescription::CertificateRevoked
            | rustls::AlertDescription::UnknownCA
            | rustls::AlertDescription::UnsupportedCertificate
            | rustls::AlertDescription::AccessDenied
    )
}

fn trust_alert_text(text: &str) -> bool {
    [
        "CertificateUnknown",
        "BadCertificate",
        "CertificateExpired",
        "CertificateRevoked",
        "UnknownCA",
        "UnsupportedCertificate",
        "AccessDenied",
    ]
    .iter()
    .any(|label| text.contains(label))
}

fn brief(error: &dyn std::fmt::Display) -> String {
    let mut text = error.to_string().replace(['\n', '\r'], " ");
    if text.len() > 180 {
        text.truncate(180);
    }
    text
}

#[cfg(test)]
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
        .map_err(|error| AtvError::TlsHandshake(brief(&error)))?
        .with_client_cert_verifier(verifier)
        .with_single_cert(vec![certificate], key)
        .map_err(|_| AtvError::TlsHandshake("server certificate was rejected".to_owned()))?;
    Ok(Arc::new(config))
}

#[cfg(test)]
struct TestClientVerifier {
    provider: Arc<CryptoProvider>,
    trust_client: bool,
}

#[cfg(test)]
impl fmt::Debug for TestClientVerifier {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("TestClientVerifier")
            .finish_non_exhaustive()
    }
}

#[cfg(test)]
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
