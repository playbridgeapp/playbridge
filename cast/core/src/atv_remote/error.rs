/// Failure from discovery, pairing, or a control session.
///
/// Messages name the host, the port, and the protocol step. They do not include
/// certificates, private keys, or pairing codes.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum AtvError {
    #[error("Android TV remote is unreachable: {0}")]
    Unreachable(String),
    #[error("TLS handshake failed: {0}")]
    TlsHandshake(String),
    /// The TV closed the handshake because it does not trust this client certificate.
    #[error("Android TV does not trust this client; pair again")]
    NotTrusted,
    /// The TV certificate's SHA-256 does not match the pin from pairing.
    #[error("Android TV certificate does not match the stored pin")]
    PinMismatch,
    /// The on-screen code failed the local check, or the TV rejected the secret.
    #[error("pairing code was rejected")]
    BadCode,
    #[error("Android TV remote protocol error: {0}")]
    Protocol(String),
    #[error("Android TV remote timed out")]
    Timeout,
    /// The control connection ended, including a missed ping interval.
    #[error("Android TV remote connection closed")]
    Closed,
}
