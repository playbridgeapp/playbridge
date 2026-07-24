use std::{
    io::{self, Write},
    net::IpAddr,
};

use clap::Parser;
use playbridge_browser_receiver::{
    BrowserReceiverConfig, BrowserReceiverEvent, BrowserReceiverHost,
};
use tracing_subscriber::EnvFilter;

#[derive(Debug, Parser)]
#[command(
    name = "playbridge-browser-receiver",
    about = "Host a PlayBridge receiver page for ordinary web browsers"
)]
struct Arguments {
    #[arg(long, default_value = "0.0.0.0")]
    address: IpAddr,
    #[arg(long, default_value_t = 8770)]
    port: u16,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();
    let arguments = Arguments::parse();
    let host = BrowserReceiverHost::start(BrowserReceiverConfig {
        address: arguments.address,
        preferred_port: arguments.port,
        ..Default::default()
    })
    .await?;
    println!("Open one of these addresses on the receiving device:");
    for url in host.urls() {
        println!("  {url}");
    }
    let service = host.service();
    let mut events = service.subscribe();
    loop {
        tokio::select! {
            signal = tokio::signal::ctrl_c() => {
                signal?;
                break;
            }
            event = events.recv() => {
                match event? {
                    BrowserReceiverEvent::PairingRequested { session, .. } => {
                        println!("\nPairing request from \"{}\"", session.name);
                        print!("Enter the six-digit code shown in the browser: ");
                        io::stdout().flush()?;
                        let code = tokio::task::spawn_blocking(|| {
                            let mut input = String::new();
                            io::stdin().read_line(&mut input).map(|_| input)
                        }).await??;
                        match service.approve(&session.session_id, code.trim()).await {
                            Ok(()) => println!("Browser approved."),
                            Err(error) => eprintln!("Pairing failed: {error}"),
                        }
                    }
                    BrowserReceiverEvent::Connected { session } => {
                        println!("Connected: {}", session.name);
                    }
                    BrowserReceiverEvent::Disconnected { name, .. } => {
                        println!("Disconnected: {name}");
                    }
                    _ => {}
                }
            }
        }
    }
    host.shutdown().await?;
    Ok(())
}
