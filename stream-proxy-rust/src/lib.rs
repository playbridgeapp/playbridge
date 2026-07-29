#[cfg(feature = "upstream-avio")]
pub mod avio;
pub mod config;
pub mod crypto;
pub mod dash;
pub mod epg;
pub mod hls;
pub mod local_file;
pub mod server;
pub mod service;
pub mod session;
pub mod upstream;

pub use config::Config;
pub use crypto::{EncryptionHandler, ProxyData};
pub use server::{create_router, ProxyService, RegisteredMedia};
pub use service::{ProxyServer, ProxyServerConfig};
pub use upstream::{
    default_upstream_fetcher, ConnectionEngine, PrefetchTarget, SegmentCache, UpstreamFetcher,
    UpstreamResponse,
};

#[cfg(feature = "upstream-jni")]
pub use upstream::jni_fetcher::{
    clear_upstream_callbacks, pb_proxy_upstream_abi_version,
    pb_proxy_upstream_callbacks_registered, pb_proxy_upstream_clear_callbacks,
    pb_proxy_upstream_set_callbacks, set_upstream_callbacks, upstream_callbacks_registered,
    JniUpstreamFetcher, PbUpstreamCallbacks, UPSTREAM_JNI_ABI_VERSION,
};
