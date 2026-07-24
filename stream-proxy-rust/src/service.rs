use std::{
    collections::HashMap,
    net::{IpAddr, Ipv4Addr, SocketAddr},
    path::Path,
    time::Duration,
};

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use rand::Rng;
use tokio::{net::TcpListener, sync::oneshot, task::JoinHandle};

use crate::{
    config::Config,
    server::{ProxyService, RegisteredMedia},
};

#[derive(Debug, Clone)]
pub struct ProxyServerConfig {
    pub address: IpAddr,
    pub port: u16,
    pub password: String,
    pub ffmpeg_path: Option<String>,
}

impl Default for ProxyServerConfig {
    fn default() -> Self {
        Self {
            address: IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            port: 0,
            password: random_secret(),
            ffmpeg_path: None,
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
        })
    }
}

pub struct ProxyServer {
    service: ProxyService,
    local_addr: SocketAddr,
    shutdown: Option<oneshot::Sender<()>>,
    task: Option<JoinHandle<Result<(), std::io::Error>>>,
}

impl ProxyServer {
    pub async fn start(config: ProxyServerConfig) -> Result<Self, String> {
        if config.password.trim().is_empty() {
            return Err("proxy password cannot be empty".into());
        }
        let listener = TcpListener::bind(SocketAddr::new(config.address, config.port))
            .await
            .map_err(|error| format!("failed to bind proxy server: {error}"))?;
        let local_addr = listener
            .local_addr()
            .map_err(|error| format!("failed to inspect proxy listener: {error}"))?;
        let service = ProxyService::new(config.password, config.ffmpeg_path);
        let app = service.router();
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
        })
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
