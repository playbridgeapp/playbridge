use clap::Parser;
use std::net::SocketAddr;
use stream_proxy_rust::{create_router, Config};
use tracing::info;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();

    let config = Config::parse();

    let (app, address, port) = match create_router(config) {
        Ok(res) => res,
        Err(e) => {
            eprintln!("Cannot start proxy: {}", e);
            std::process::exit(64);
        }
    };

    let bind_addr: SocketAddr = format!("{}:{}", address, port).parse()?;
    info!("[stream-proxy-rust] Listening on {}", bind_addr);
    info!("[stream-proxy-rust] Authentication active");

    let listener = tokio::net::TcpListener::bind(bind_addr).await?;
    info!("[pb-proxy-cli] Server running at http://{}", bind_addr);

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await?;

    info!("[stream-proxy-rust] Stopped proxy server");
    Ok(())
}

async fn shutdown_signal() {
    let ctrl_c = async {
        tokio::signal::ctrl_c()
            .await
            .expect("failed to install Ctrl+C handler");
    };

    #[cfg(unix)]
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("failed to install signal handler")
            .recv()
            .await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => info!("\n[pb-proxy-cli] Shutting down gracefully (SIGINT)..."),
        _ = terminate => info!("\n[pb-proxy-cli] Shutting down gracefully (SIGTERM)..."),
    }
}
