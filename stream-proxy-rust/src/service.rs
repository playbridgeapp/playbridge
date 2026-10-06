use std::{
    collections::HashMap,
    net::{IpAddr, Ipv4Addr, SocketAddr},
    path::Path,
    sync::Arc,
    time::Duration,
};

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use rand::Rng;
use tokio::{net::TcpListener, sync::oneshot, task::JoinHandle};

use crate::{
    config::Config,
    server::{ProxyService, RegisteredMedia},
    upstream::{ConnectionEngine, UpstreamFetcher},
};

#[derive(Debug, Clone)]
pub struct ProxyServerConfig {
    pub address: IpAddr,
    pub port: u16,
    pub password: String,
    pub ffmpeg_path: Option<String>,
    /// Standalone opt-in diagnostic surface; embedded instances leave it off.
    pub enable_demo: bool,
}

impl Default for ProxyServerConfig {
    fn default() -> Self {
        Self {
            address: IpAddr::V4(Ipv4Addr::LOCALHOST),
            port: 0,
            password: random_secret(),
            ffmpeg_path: None,
            enable_demo: false,
        }
    }
}

impl TryFrom<Config> for ProxyServerConfig {
    type Error = String;

    fn try_from(config: Config) -> Result<Self, Self::Error> {
        Ok(Self {
            address: config
                .address
                .parse()
                .map_err(|error| format!("invalid bind address: {error}"))?,
            port: config.port,
            password: config.get_validated_password()?,
            ffmpeg_path: config.ffmpeg_path,
            enable_demo: true,
        })
    }
}

pub struct ProxyServer {
    service: ProxyService,
    local_addr: SocketAddr,
    shutdown: Option<oneshot::Sender<()>>,
    task: Option<JoinHandle<Result<(), std::io::Error>>>,
    interfaces: std::sync::Mutex<std::collections::HashMap<IpAddr, JoinHandle<()>>>,
    expose: tokio::sync::Mutex<()>,
}

impl ProxyServer {
    /// Start with the feature-default origin fetcher (reqwest on Docker/Desktop).
    ///
    /// When built with **only** `upstream-jni`, the host should register callbacks
    /// before remote origin fetches; local file grants still work without them.
    pub async fn start(config: ProxyServerConfig) -> Result<Self, String> {
        #[cfg(all(feature = "upstream-jni", not(feature = "upstream-reqwest")))]
        {
            if !crate::upstream::jni_fetcher::upstream_callbacks_registered() {
                tracing::warn!(
                    "[stream-proxy] JNI upstream callbacks not registered yet; \
                     remote /s/ origin fetches will fail until the host installs them"
                );
            }
        }
        let engine = Arc::new(ConnectionEngine::new(config.ffmpeg_path.clone()));
        Self::start_with_engine(config, engine).await
    }

    /// Start with an explicit origin fetcher (tests / Android JNI embed).
    pub async fn start_with_fetcher(
        config: ProxyServerConfig,
        fetcher: Arc<dyn UpstreamFetcher>,
    ) -> Result<Self, String> {
        let engine = Arc::new(ConnectionEngine::with_fetcher(fetcher));
        Self::start_with_engine(config, engine).await
    }

    pub async fn start_with_engine(
        config: ProxyServerConfig,
        engine: Arc<ConnectionEngine>,
    ) -> Result<Self, String> {
        if config.password.trim().is_empty() {
            return Err("proxy password cannot be empty".into());
        }
        let listener = TcpListener::bind(SocketAddr::new(config.address, config.port))
            .await
            .map_err(|error| format!("failed to bind proxy server: {error}"))?;
        let local_addr = listener
            .local_addr()
            .map_err(|error| format!("failed to inspect proxy listener: {error}"))?;
        let service = ProxyService::with_engine(config.password, engine);
        let app = service.router_with_demo(config.enable_demo);
        let (shutdown, shutdown_rx) = oneshot::channel();
        let task = tokio::spawn(async move {
            axum::serve(listener, app)
                .with_graceful_shutdown(async {
                    let _ = shutdown_rx.await;
                })
                .await
        });
        Ok(Self {
            service,
            local_addr,
            shutdown: Some(shutdown),
            task: Some(task),
            interfaces: std::sync::Mutex::new(std::collections::HashMap::new()),
            expose: tokio::sync::Mutex::new(()),
        })
    }

    /// Open only the exact local interface selected for the receiver, on the same
    /// port as loopback. The OS rejects non-local addresses; never bind a wildcard.
    pub async fn expose_interface(&self, host: &str) -> Result<(), String> {
        let address: IpAddr = host
            .trim_matches(['[', ']'])
            .parse()
            .map_err(|_| "receiver interface must be a local IP address")?;
        if address.is_unspecified() || address.is_multicast() {
            return Err("invalid receiver interface".into());
        }
        if address == self.local_addr.ip() || self.local_addr.ip().is_unspecified() {
            return Ok(());
        }
        let _expose = self.expose.lock().await;
        if self
            .interfaces
            .lock()
            .map_err(|_| "interface registry unavailable")?
            .contains_key(&address)
        {
            return Ok(());
        }
        let listener = TcpListener::bind(SocketAddr::new(address, self.local_addr.port()))
            .await
            .map_err(|_| "selected receiver interface is unavailable")?;
        let router = self.service.router_with_demo(false);
        let task = tokio::spawn(async move {
            let _ = axum::serve(listener, router).await;
        });
        let mut interfaces = self
            .interfaces
            .lock()
            .map_err(|_| "interface registry unavailable")?;
        if let Some(old) = interfaces.insert(address, task) {
            old.abort();
        }
        Ok(())
    }

    pub fn service(&self) -> &ProxyService {
        &self.service
    }

    pub fn local_addr(&self) -> SocketAddr {
        self.local_addr
    }

    pub fn base_url(&self, host: &str) -> String {
        let host = if host.contains(':') && !host.starts_with('[') {
            format!("[{host}]")
        } else {
            host.to_owned()
        };
        format!("http://{host}:{}", self.local_addr.port())
    }

    pub fn register_remote(
        &self,
        host: &str,
        url: impl Into<String>,
        headers: HashMap<String, String>,
    ) -> Result<RegisteredMedia, String> {
        self.service
            .register_remote(&self.base_url(host), url.into(), headers)
    }

    pub fn register_remote_with_content_type(
        &self,
        host: &str,
        url: impl Into<String>,
        headers: HashMap<String, String>,
        content_type: Option<&str>,
    ) -> Result<RegisteredMedia, String> {
        self.service.register_remote_with_content_type(
            &self.base_url(host),
            url.into(),
            headers,
            content_type,
        )
    }

    /// Native-approved registration with bounded async DNS classification.
    pub async fn register_native_remote_with_content_type(
        &self,
        host: &str,
        url: impl Into<String>,
        headers: HashMap<String, String>,
        content_type: Option<&str>,
    ) -> Result<RegisteredMedia, String> {
        self.service
            .register_native_remote_with_content_type(
                &self.base_url(host),
                url.into(),
                headers,
                content_type,
            )
            .await
    }

    pub fn register_remote_with_policy(
        &self,
        host: &str,
        url: impl Into<String>,
        headers: HashMap<String, String>,
        content_type: Option<&str>,
        allowed_private_origins: Vec<String>,
    ) -> Result<RegisteredMedia, String> {
        self.service.register_remote_with_policy(
            &self.base_url(host),
            url.into(),
            headers,
            content_type,
            allowed_private_origins,
        )
    }

    pub fn register_file(
        &self,
        host: &str,
        path: impl AsRef<Path>,
        content_type: Option<String>,
        ttl: Duration,
    ) -> Result<RegisteredMedia, String> {
        self.service
            .register_file(&self.base_url(host), path, content_type, ttl)
    }

    pub async fn shutdown(mut self) -> Result<(), String> {
        self.service.clear();
        for (_, task) in self
            .interfaces
            .lock()
            .map_err(|_| "interface registry unavailable")?
            .drain()
        {
            task.abort();
        }
        if let Some(shutdown) = self.shutdown.take() {
            let _ = shutdown.send(());
        }
        self.task
            .take()
            .expect("proxy server task must exist")
            .await
            .map_err(|error| format!("proxy server task failed: {error}"))?
            .map_err(|error| format!("proxy server failed: {error}"))
    }
}

impl Drop for ProxyServer {
    fn drop(&mut self) {
        self.service.clear();
        if let Ok(mut interfaces) = self.interfaces.lock() {
            for (_, task) in interfaces.drain() {
                task.abort();
            }
        }
        if let Some(shutdown) = self.shutdown.take() {
            let _ = shutdown.send(());
        }
        if let Some(task) = self.task.take() {
            task.abort();
        }
    }
}

fn random_secret() -> String {
    let mut bytes = [0_u8; 32];
    rand::thread_rng().fill(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}
