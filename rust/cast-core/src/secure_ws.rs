use std::sync::Arc;

use base64::{Engine, engine::general_purpose::STANDARD as BASE64};
use futures_util::{SinkExt, StreamExt};
use rustls::{
    ClientConfig, DigitallySignedStruct, Error as TlsError, SignatureScheme,
    client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
    crypto::{CryptoProvider, verify_tls12_signature, verify_tls13_signature},
    pki_types::{CertificateDer, ServerName, UnixTime},
};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use tokio::net::TcpStream;
use tokio_tungstenite::{
    Connector, MaybeTlsStream, WebSocketStream, connect_async_tls_with_config,
    tungstenite::{Message, client::IntoClientRequest},
};
use x509_parser::parse_x509_certificate;

use crate::{
    CastError, Result,
    playbridge::{ReceiverFrame, SenderFrame, decode_receiver_text, encode_text},
};

pub struct SecureWebSocket {
    socket: WebSocketStream<MaybeTlsStream<TcpStream>>,
    served_spki_pin: String,
}

impl SecureWebSocket {
    /// Opens a first-pairing TLS connection and returns the pin extracted from
    /// that same active socket. The SAS handshake must authenticate and bind it
    /// before callers persist credentials.
    pub async fn connect_for_pairing(endpoint: &str) -> Result<Self> {
        Self::connect(endpoint, None).await
    }

    /// Opens a known-receiver connection and verifies its active SPKI before a
    /// bearer token or any other application frame can be sent.
    pub async fn connect_pinned(endpoint: &str, expected_spki_pin: &str) -> Result<Self> {
        Self::connect(endpoint, Some(expected_spki_pin)).await
    }

    async fn connect(endpoint: &str, expected_spki_pin: Option<&str>) -> Result<Self> {
        let provider = Arc::new(rustls::crypto::aws_lc_rs::default_provider());
        let verifier = Arc::new(SelfSignedVerifier {
            provider: provider.clone(),
        });
        let config = ClientConfig::builder_with_provider(provider)
            .with_safe_default_protocol_versions()
            .map_err(protocol_error)?
            .dangerous()
            .with_custom_certificate_verifier(verifier)
            .with_no_client_auth();
        let request = endpoint.into_client_request().map_err(protocol_error)?;
        let (socket, _) = connect_async_tls_with_config(
            request,
            None,
            false,
            Some(Connector::Rustls(Arc::new(config))),
        )
        .await
        .map_err(protocol_error)?;
        let served_spki_pin = active_spki_pin(socket.get_ref())?;
        if let Some(expected) = expected_spki_pin
            && expected
                .as_bytes()
                .ct_eq(served_spki_pin.as_bytes())
                .unwrap_u8()
                != 1
        {
            return Err(CastError::Protocol("active TLS SPKI pin mismatch".into()));
        }
        Ok(Self {
            socket,
            served_spki_pin,
        })
    }

    pub fn served_spki_pin(&self) -> &str {
        &self.served_spki_pin
    }

    pub async fn send(&mut self, frame: &SenderFrame) -> Result<()> {
        self.socket
            .send(Message::Text(encode_text(frame)?.into()))
            .await
            .map_err(protocol_error)
    }

    pub async fn send_pointer(&mut self, frame: [u8; 9]) -> Result<()> {
        self.socket
            .send(Message::Binary(frame.to_vec().into()))
            .await
            .map_err(protocol_error)
    }

    pub async fn receive(&mut self) -> Result<Option<ReceiverFrame>> {
        while let Some(message) = self.socket.next().await {
            match message.map_err(protocol_error)? {
                Message::Text(text) => return decode_receiver_text(&text).map(Some),
                Message::Ping(bytes) => self
                    .socket
                    .send(Message::Pong(bytes))
                    .await
                    .map_err(protocol_error)?,
                Message::Close(_) => return Ok(None),
                _ => {}
            }
        }
        Ok(None)
    }

    pub async fn close(mut self) -> Result<()> {
        self.socket.close(None).await.map_err(protocol_error)
    }
}

/// Accepts a self-signed certificate chain during first contact while still
/// verifying the certificate's TLS handshake signatures. Identity is enforced
/// by SAS during pairing or the stored SPKI immediately after the handshake.
#[derive(Debug)]
struct SelfSignedVerifier {
    provider: Arc<CryptoProvider>,
}

impl ServerCertVerifier for SelfSignedVerifier {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> std::result::Result<ServerCertVerified, TlsError> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, TlsError> {
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
    ) -> std::result::Result<HandshakeSignatureValid, TlsError> {
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

fn active_spki_pin(stream: &MaybeTlsStream<TcpStream>) -> Result<String> {
    let MaybeTlsStream::Rustls(stream) = stream else {
        return Err(CastError::Protocol("WSS did not negotiate rustls".into()));
    };
    let certificate = stream
        .get_ref()
        .1
        .peer_certificates()
        .and_then(|certificates| certificates.first())
        .ok_or(CastError::MissingField("peer TLS certificate"))?;
    let (_, certificate) = parse_x509_certificate(certificate.as_ref()).map_err(protocol_error)?;
    let digest = Sha256::digest(certificate.public_key().raw);
    Ok(format!("sha256/{}", BASE64.encode(digest)))
}

fn protocol_error(error: impl std::fmt::Display) -> CastError {
    CastError::Protocol(error.to_string())
}
