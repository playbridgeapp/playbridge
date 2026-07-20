//! Portable casting core for PlayBridge.
//!
//! Platform lifecycle, permissions, multicast locks, and UI state do not belong
//! in this crate. The public types deliberately avoid Kotlin, Swift, and Dart
//! concepts so a later production crate can expose them through multiple FFI
//! frontends.

pub mod dial;
pub mod discovery;
pub mod hls;
pub mod native_discovery;
pub mod playbridge;
pub mod secure_ws;
pub mod ssdp;
pub mod upnp;

pub use ssdp::{DiscoveryConfig, DiscoveryHit, DiscoveryProtocol, DiscoverySession};

#[derive(Debug, thiserror::Error)]
pub enum CastError {
    #[error("network operation failed: {0}")]
    Network(#[from] std::io::Error),
    #[error("HTTP operation failed: {0}")]
    Http(#[from] reqwest::Error),
    #[error("invalid URL: {0}")]
    Url(#[from] url::ParseError),
    #[error("invalid HTTP URI: {0}")]
    Uri(#[from] http::uri::InvalidUri),
    #[error("UPnP operation failed: {0}")]
    Upnp(#[from] rupnp::Error),
    #[error("receiver description is missing {0}")]
    MissingField(&'static str),
    #[error("receiver returned HTTP {status} for {operation}")]
    ReceiverHttp {
        operation: &'static str,
        status: reqwest::StatusCode,
    },
    #[error("mDNS operation failed: {0}")]
    Mdns(String),
    #[error("invalid PlayBridge frame: {0}")]
    Protocol(String),
    #[error("pairing cryptography failed")]
    Crypto,
}

pub type Result<T> = std::result::Result<T, CastError>;
