use std::{env, time::Duration};

use playbridge_cast_core::{
    castv2::{
        self, CastChannel, CastMessage, NS_HEARTBEAT, NS_RECEIVER, RECEIVER_ID, RequestIdGenerator,
    },
    discovery::{DiscoveryConfig, DiscoveryEvent, DiscoveryStream, Receiver, ReceiverProtocol},
};
use serde_json::{Value, json};
use tokio::time::Instant;

const DEFAULT_PORT: u16 = 8009;
const DEFAULT_TIMEOUT: Duration = Duration::from_secs(20);

pub async fn run_google_cast(arguments: &[String]) -> Result<(), String> {
    let command = arguments.first().map(String::as_str);
    if !matches!(command, Some("status" | "launch")) {
        return Err(
            "expected: playbridge google-cast <status|launch> [--device <name>] [--address <address>]"
                .into(),
        );
    }
    let options = parse_options(&arguments[1..])?;
    let receiver = match options.address.as_deref() {
        Some(address) => Receiver {
            id: playbridge_cast_core::discovery::ReceiverId(format!("google_cast:{address}")),
            protocol: ReceiverProtocol::GoogleCast,
            name: options.device.clone().unwrap_or_else(|| address.to_owned()),
            addresses: vec![address.to_owned()],
            port: Some(options.port),
            wss_port: None,
            location: None,
            uuid: None,
        },
        None => discover_receiver(options.device.as_deref(), options.timeout).await?,
    };
    let address = preferred_address(&receiver).ok_or_else(|| {
        format!(
            "Google Cast receiver {:?} has no usable address",
            receiver.name
        )
    })?;
    let port = receiver.port.unwrap_or(options.port);

    if command == Some("launch") {
        let details = tokio::time::timeout(
            options.timeout,
            castv2::launch_app_session(address, port, &options.application_id),
        )
        .await
        .map_err(|_| "Google Cast receiver application launch timed out".to_owned())??;
        if options.json {
            println!(
                "{}",
                serde_json::to_string_pretty(&json!({
                    "name": receiver.name,
                    "protocol": "google_cast",
                    "address": address,
                    "port": port,
                    "application_id": details.app_id,
                    "session_id": details.session_id,
                    "transport_id": details.transport_id,
                    "ready": true,
                }))
                .map_err(|error| error.to_string())?
            );
        } else {
            println!(
                "Google Cast ready: {} ({}:{}, app {})",
                receiver.name, address, port, details.app_id
            );
            println!("The receiver is showing its idle screen and is ready for LOAD.");
        }
        return Ok(());
    }

    let mut channel = CastChannel::connect(address, port).await?;
    let ids = RequestIdGenerator::new();
    channel
        .send_message(&CastMessage::new(
            RECEIVER_ID,
            castv2::NS_CONNECTION,
            castv2::build_connect_payload(),
        ))
        .await?;
    channel
        .send_message(&CastMessage::new(
            RECEIVER_ID,
            NS_RECEIVER,
            json!({ "type": "GET_STATUS", "requestId": ids.next() }).to_string(),
        ))
        .await?;

    let deadline = Instant::now() + options.timeout;
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err("Google Cast receiver did not return status before the timeout".into());
        }
        let message = match tokio::time::timeout(
            remaining.min(Duration::from_millis(750)),
            channel.read_message(),
        )
        .await
        {
            Ok(result) => result?,
            Err(_) => continue,
        };
        if message.namespace == NS_HEARTBEAT {
            channel.handle_heartbeat(&message).await?;
            continue;
        }
        if message.namespace != NS_RECEIVER {
            continue;
        }
        let payload: Value = serde_json::from_str(&message.payload_utf8)
            .map_err(|error| format!("invalid Google Cast status payload: {error}"))?;
        if payload["type"] != "RECEIVER_STATUS" {
            continue;
        }
        if options.json {
            println!(
                "{}",
                serde_json::to_string_pretty(&json!({
                    "name": receiver.name,
                    "protocol": "google_cast",
                    "address": address,
                    "port": port,
                    "status": payload,
                }))
                .map_err(|error| error.to_string())?
            );
        } else {
            println!(
                "Google Cast reachable: {} ({}:{})",
                receiver.name, address, port
            );
            println!(
                "{}",
                serde_json::to_string_pretty(&payload).map_err(|error| error.to_string())?
            );
        }
        return Ok(());
    }
}

#[derive(Debug)]
struct Options {
    device: Option<String>,
    address: Option<String>,
    port: u16,
    timeout: Duration,
    json: bool,
    application_id: String,
}

fn parse_options(arguments: &[String]) -> Result<Options, String> {
    let mut options = Options {
        device: None,
        address: None,
        port: DEFAULT_PORT,
        timeout: DEFAULT_TIMEOUT,
        json: false,
        application_id: env::var("PLAYBRIDGE_GOOGLE_CAST_APP_ID")
            .unwrap_or_else(|_| castv2::DEFAULT_MEDIA_RECEIVER_APP_ID.to_owned()),
    };
    let mut index = 0;
    while index < arguments.len() {
        match arguments[index].as_str() {
            "--device" => {
                index += 1;
                options.device = Some(
                    arguments
                        .get(index)
                        .ok_or("--device requires a value")?
                        .clone(),
                );
            }
            "--address" => {
                index += 1;
                options.address = Some(
                    arguments
                        .get(index)
                        .ok_or("--address requires a value")?
                        .clone(),
                );
            }
            "--port" => {
                index += 1;
                options.port = arguments
                    .get(index)
                    .ok_or("--port requires a value")?
                    .parse()
                    .map_err(|_| "invalid CastV2 port")?;
            }
            "--timeout" => {
                index += 1;
                let seconds: u64 = arguments
                    .get(index)
                    .ok_or("--timeout requires seconds")?
                    .parse()
                    .map_err(|_| "invalid timeout")?;
                if seconds == 0 || seconds > 300 {
                    return Err("timeout must be between 1 and 300 seconds".into());
                }
                options.timeout = Duration::from_secs(seconds);
            }
            "--json" => options.json = true,
            "--app-id" => {
                index += 1;
                options.application_id = arguments
                    .get(index)
                    .ok_or("--app-id requires a value")?
                    .clone();
                if options.application_id.trim().is_empty() {
                    return Err("--app-id must not be empty".into());
                }
            }
            unknown => return Err(format!("unknown Google Cast option: {unknown}")),
        }
        index += 1;
    }
    if options.device.is_none() && options.address.is_none() {
        return Err("Google Cast command requires --device or --address".into());
    }
    if options.device.is_some() && options.address.is_some() {
        return Err("--device and --address cannot be combined".into());
    }
    Ok(options)
}

async fn discover_receiver(
    device_name: Option<&str>,
    timeout: Duration,
) -> Result<Receiver, String> {
    let mut stream = DiscoveryStream::start(DiscoveryConfig::selected(
        [ReceiverProtocol::GoogleCast],
        timeout,
    ));
    let mut fallback = None;
    while let Some(event) = stream.next().await {
        match event {
            DiscoveryEvent::Found(receiver) | DiscoveryEvent::Updated(receiver) => {
                let matches =
                    device_name.is_none_or(|name| receiver.name.eq_ignore_ascii_case(name));
                if matches {
                    return Ok(receiver);
                }
                if device_name.is_none() {
                    fallback.get_or_insert(receiver);
                }
            }
            DiscoveryEvent::Error { message, .. } => return Err(message),
            DiscoveryEvent::Started(_) | DiscoveryEvent::Finished(_) => {}
        }
    }
    match device_name {
        Some(name) => Err(format!("Google Cast receiver {name:?} was not found")),
        None => fallback.ok_or_else(|| "Google Cast receiver was not found".into()),
    }
}

fn preferred_address(receiver: &Receiver) -> Option<&str> {
    receiver
        .addresses
        .iter()
        .find(|address| address.contains('.'))
        .or_else(|| receiver.addresses.first())
        .map(String::as_str)
}

#[cfg(test)]
mod tests {
    use super::parse_options;

    #[test]
    fn requires_one_target_selector() {
        assert!(parse_options(&[]).is_err());
        assert!(parse_options(&["--device".into(), "TV".into()]).is_ok());
        assert!(
            parse_options(&[
                "--device".into(),
                "TV".into(),
                "--address".into(),
                "192.0.2.1".into()
            ])
            .is_err()
        );
    }

    #[test]
    fn accepts_an_explicit_receiver_application_id() {
        let options = parse_options(&[
            "--address".into(),
            "192.0.2.1".into(),
            "--app-id".into(),
            "PLAY1234".into(),
        ])
        .unwrap();
        assert_eq!(options.application_id, "PLAY1234");
    }
}
