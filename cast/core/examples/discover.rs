use playbridge_cast_core::discovery::{DiscoveryConfig, DiscoveryEvent, DiscoveryStream};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut discovery = DiscoveryStream::start(DiscoveryConfig::default());
    while let Some(event) = discovery.next().await {
        match event {
            DiscoveryEvent::Found(receiver) | DiscoveryEvent::Updated(receiver) => println!(
                "{:?}\t{}\t{}\t{:?}",
                receiver.protocol,
                receiver.name,
                receiver.addresses.join(","),
                receiver.port
            ),
            DiscoveryEvent::Error { protocol, message } => {
                eprintln!("{protocol:?} discovery error: {message}")
            }
            DiscoveryEvent::Started(_) | DiscoveryEvent::Finished(_) => {}
        }
    }
    Ok(())
}
