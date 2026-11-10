//! TLS for relay and pairing connections: one rustls config with an explicit
//! crypto provider and the bundled web PKI roots.
//!
//! Apps link several crates that enable different rustls providers, so the
//! process-wide default cannot be relied on, and on Android the platform
//! verifier needs JNI setup the apps do not do for this crate.

use std::sync::{Arc, OnceLock};

use rustls::{ClientConfig, RootCertStore};
use tokio::net::TcpStream;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::{Connector, MaybeTlsStream, WebSocketStream};

fn config() -> Arc<ClientConfig> {
    static CONFIG: OnceLock<Arc<ClientConfig>> = OnceLock::new();
    CONFIG
        .get_or_init(|| {
            let roots = RootCertStore::from_iter(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
            Arc::new(
                ClientConfig::builder_with_provider(Arc::new(
                    rustls::crypto::aws_lc_rs::default_provider(),
                ))
                .with_safe_default_protocol_versions()
                .expect("aws-lc-rs supports the default protocol versions")
                .with_root_certificates(roots)
                .with_no_client_auth(),
            )
        })
        .clone()
}

pub(crate) fn http_client() -> reqwest::Client {
    reqwest::Client::builder()
        .tls_backend_preconfigured((*config()).clone())
        .build()
        .expect("a preconfigured rustls client always builds")
}

pub(crate) async fn connect<R: IntoClientRequest + Unpin>(
    request: R,
) -> Result<
    (
        WebSocketStream<MaybeTlsStream<TcpStream>>,
        tokio_tungstenite::tungstenite::handshake::client::Response,
    ),
    tokio_tungstenite::tungstenite::Error,
> {
    tokio_tungstenite::connect_async_tls_with_config(
        request,
        None,
        false,
        Some(Connector::Rustls(config())),
    )
    .await
}

#[cfg(test)]
mod tests {
    #[test]
    fn builds_clients_without_a_default_provider() {
        assert!(!super::config().alpn_protocols.contains(&b"h2".to_vec()));
        let _ = super::http_client();
    }
}
