use std::fmt;

use tokio_rustls::client::TlsStream;

use super::error::AtvError;
use super::framed::AtvStream;
use super::identity::{
    ClientIdentity, fingerprint_sha256, mac_from_certificate, pairing_secret, rsa_components,
    validate_client_name,
};
use super::proto::{
    STATUS_BAD_SECRET, STATUS_OK, decode_outer, encode_configuration, encode_options,
    encode_pairing_request, encode_secret,
};
use super::tls::{AtvTimeouts, connect_pairing};

/// TV certificate captured by a finished pairing.
pub struct PairingResult {
    pub server_certificate_der: Vec<u8>,
    pub fingerprint_sha256: [u8; 32],
    pub mac: Option<String>,
    pub server_name: Option<String>,
}

impl fmt::Debug for PairingResult {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("PairingResult")
            .field("certificate_len", &self.server_certificate_der.len())
            .field(
                "fingerprint_sha256",
                &super::identity::fingerprint_hex(&self.fingerprint_sha256),
            )
            .field("mac", &self.mac)
            .field("server_name", &self.server_name)
            .finish()
    }
}

/// Pairing connection paused after `configuration_ack`, ready for the code.
pub struct PairingSession {
    stream: AtvStream<TlsStream<tokio::net::TcpStream>>,
    timeouts: AtvTimeouts,
    client_modulus: Vec<u8>,
    client_exponent: Vec<u8>,
    server_modulus: Vec<u8>,
    server_exponent: Vec<u8>,
    server_certificate_der: Vec<u8>,
    fingerprint_sha256: [u8; 32],
    mac: Option<String>,
    server_name: Option<String>,
}

impl fmt::Debug for PairingSession {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("PairingSession")
            .field("certificate_len", &self.server_certificate_der.len())
            .field(
                "fingerprint_sha256",
                &super::identity::fingerprint_hex(&self.fingerprint_sha256),
            )
            .field("mac", &self.mac)
            .field("server_name", &self.server_name)
            .finish_non_exhaustive()
    }
}

impl PairingSession {
    pub fn server_certificate_der(&self) -> &[u8] {
        &self.server_certificate_der
    }

    pub fn fingerprint_sha256(&self) -> [u8; 32] {
        self.fingerprint_sha256
    }

    pub fn mac(&self) -> Option<&str> {
        self.mac.as_deref()
    }

    pub fn server_name(&self) -> Option<&str> {
        self.server_name.as_deref()
    }

    /// Checks the code locally, then sends the pairing secret.
    pub async fn finish(mut self, code: &str) -> Result<PairingResult, AtvError> {
        let secret = pairing_secret(
            &self.client_modulus,
            &self.client_exponent,
            &self.server_modulus,
            &self.server_exponent,
            code,
        )?;
        self.stream
            .write_frame(&encode_secret(&secret), self.timeouts.handshake)
            .await?;
        let frame = self.stream.read_frame(self.timeouts.handshake).await?;
        let message = decode_outer(&frame)?;
        if message.status == STATUS_BAD_SECRET {
            return Err(AtvError::BadCode);
        }
        if message.status != STATUS_OK {
            return Err(AtvError::Protocol(format!(
                "pairing status {}",
                message.status
            )));
        }
        if !message.secret_ack {
            return Err(AtvError::Protocol("missing secret ack".to_owned()));
        }
        Ok(PairingResult {
            server_certificate_der: self.server_certificate_der,
            fingerprint_sha256: self.fingerprint_sha256,
            mac: self.mac,
            server_name: self.server_name,
        })
    }
}

pub async fn start_pairing(
    host: &str,
    port: u16,
    identity: &ClientIdentity,
    client_name: &str,
) -> Result<PairingSession, AtvError> {
    start_pairing_timeout(host, port, identity, client_name, AtvTimeouts::default()).await
}

pub async fn start_pairing_timeout(
    host: &str,
    port: u16,
    identity: &ClientIdentity,
    client_name: &str,
    timeouts: AtvTimeouts,
) -> Result<PairingSession, AtvError> {
    validate_client_name(client_name)?;
    let client = rsa_components(identity.certificate_der())?;
    let (stream, server_der) = connect_pairing(host, port, identity, timeouts).await?;
    let server = rsa_components(&server_der)?;
    let mut stream = AtvStream::new(stream);
    stream
        .write_frame(&encode_pairing_request(client_name), timeouts.handshake)
        .await?;
    let ack = read_outer(&mut stream, timeouts).await?;
    if ack.status != STATUS_OK || ack.pairing_request_ack.is_none() {
        return Err(AtvError::Protocol("missing pairing request ack".to_owned()));
    }
    let server_name = ack.pairing_request_ack.and_then(|item| item.server_name);
    stream
        .write_frame(&encode_options(), timeouts.handshake)
        .await?;
    let options = read_outer(&mut stream, timeouts).await?;
    if options.status != STATUS_OK || !options.options {
        return Err(AtvError::Protocol("missing pairing options".to_owned()));
    }
    stream
        .write_frame(&encode_configuration(), timeouts.handshake)
        .await?;
    let configured = read_outer(&mut stream, timeouts).await?;
    if configured.status != STATUS_OK || !configured.configuration_ack {
        return Err(AtvError::Protocol(
            "missing pairing configuration ack".to_owned(),
        ));
    }
    Ok(PairingSession {
        stream,
        timeouts,
        client_modulus: client.modulus,
        client_exponent: client.exponent,
        server_modulus: server.modulus,
        server_exponent: server.exponent,
        fingerprint_sha256: fingerprint_sha256(&server_der),
        mac: mac_from_certificate(&server_der),
        server_certificate_der: server_der,
        server_name,
    })
}

async fn read_outer(
    stream: &mut AtvStream<TlsStream<tokio::net::TcpStream>>,
    timeouts: AtvTimeouts,
) -> Result<super::proto::OuterMessage, AtvError> {
    let frame = stream.read_frame(timeouts.handshake).await?;
    decode_outer(&frame)
}
