use std::{net::UdpSocket, path::PathBuf};
use axum::{Router};
use tower_http::{cors::CorsLayer, services::ServeFile};
use tokio::net::TcpListener;

pub struct LocalMediaServer {
    pub url: String,
    pub path: PathBuf,
}

pub fn get_local_lan_ip() -> Option<String> {
    let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("8.8.8.8:80").ok()?;
    socket.local_addr().ok().map(|addr| addr.ip().to_string())
}

impl LocalMediaServer {
    pub async fn start(file_path: PathBuf) -> Result<(Self, tokio::task::JoinHandle<()>), String> {
        if !file_path.exists() {
            return Err(format!("File does not exist: {:?}", file_path));
        }

        let lan_ip = get_local_lan_ip().ok_or_else(|| "Could not determine local LAN IP address".to_string())?;
        let listener = match TcpListener::bind("0.0.0.0:8767").await {
            Ok(l) => l,
            Err(_) => TcpListener::bind("0.0.0.0:0")
                .await
                .map_err(|e| format!("Failed to bind local HTTP server: {e}"))?,
        };

        let port = listener.local_addr().map_err(|e| e.to_string())?.port();
        let file_name = file_path.file_name().and_then(|s| s.to_str()).unwrap_or("media.mp4");
        let route_path = format!("/{file_name}");
        let url = format!("http://{lan_ip}:{port}{route_path}");

        let serve_file = ServeFile::new(&file_path);
        let app = Router::new()
            .route_service(&route_path, serve_file)
            .layer(CorsLayer::permissive());

        let path = file_path.clone();
        let handle = tokio::spawn(async move {
            let _ = axum::serve(listener, app).await;
        });

        Ok((Self { url, path }, handle))
    }
}
