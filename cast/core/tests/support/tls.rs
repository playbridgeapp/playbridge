use rcgen::{CertificateParams, KeyPair};
use rustls::pki_types::PrivatePkcs8KeyDer;
use std::sync::Arc;

/// Generate a fresh localhost identity in memory for a test receiver.
pub(crate) fn localhost_acceptor() -> tokio_rustls::TlsAcceptor {
    let key = KeyPair::generate().unwrap();
    let certificate = CertificateParams::new(vec!["localhost".into()])
        .unwrap()
        .self_signed(&key)
        .unwrap();
    let config = rustls::ServerConfig::builder_with_provider(Arc::new(
        rustls::crypto::aws_lc_rs::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .unwrap()
    .with_no_client_auth()
    .with_single_cert(
        vec![certificate.der().clone()],
        PrivatePkcs8KeyDer::from(key.serialize_der()).into(),
    )
    .unwrap();
    tokio_rustls::TlsAcceptor::from(Arc::new(config))
}
