use std::{
    io::{self, Write},
    sync::mpsc,
    time::Duration,
};
use tokio::time::sleep;

use playbridge_cast_core::{
    castv2::{
        self, CastMessage, DEFAULT_MEDIA_RECEIVER_APP_ID, NS_CONNECTION, NS_MEDIA, NS_RECEIVER,
        RequestIdGenerator,
    },
    discovery::{DiscoveryConfig, DiscoveryEvent, DiscoveryStream, Receiver},
    upnp::Renderer,
};

use crate::preferred::PreferredDevice;

pub async fn run_send(media_target: String) -> Result<(), String> {
    println!("Media Target: {media_target}");

    // 1. Check if preferred device is configured
    if let Some(pref) = PreferredDevice::load() {
        println!("\nPreferred device found: \"{}\" ({}) - {}", pref.name, pref.protocol, pref.address);
        println!("Auto-sending in 3 seconds... Press [Enter] to choose another device.");

        let (tx, rx) = mpsc::channel();
        std::thread::spawn(move || {
            let mut line = String::new();
            if io::stdin().read_line(&mut line).is_ok() {
                let _ = tx.send(());
            }
        });

        let mut cancelled = false;
        for secs in (1..=3).rev() {
            print!("\rCountdown: {secs}s... ");
            let _ = io::stdout().flush();
            if rx.recv_timeout(Duration::from_secs(1)).is_ok() {
                cancelled = true;
                break;
            }
        }
        println!();

        if !cancelled {
            println!("Connecting to preferred device \"{}\" ({}:{})...", pref.name, pref.address, pref.port.unwrap_or(8009));
            let target_url = media_target.clone();
            match cast_to_target(&pref.protocol, &pref.address, pref.port, pref.location.as_deref(), &target_url, &pref.name).await {
                Ok(()) => return Ok(()),
                Err(err) => {
                    println!("Failed to cast to preferred device: {err}");
                    println!("Falling back to network discovery...\n");
                }
            }
        } else {
            println!("Auto-send cancelled. Scanning for devices...\n");
        }
    }

    // 2. Discovery loop
    println!("Scanning for receivers on your network (5s timeout)...");
    let mut stream = DiscoveryStream::start(DiscoveryConfig::default());
    let mut receivers = Vec::<Receiver>::new();

    let scan_deadline = tokio::time::Instant::now() + Duration::from_secs(5);
    while tokio::time::Instant::now() < scan_deadline {
        tokio::select! {
            event = stream.next() => match event {
                Some(DiscoveryEvent::Found(receiver)) | Some(DiscoveryEvent::Updated(receiver)) => {
                    if !receivers.iter().any(|r| r.id == receiver.id) {
                        println!("  [{}] {} ({}) - {}", receivers.len() + 1, receiver.name, receiver.protocol, receiver.addresses.join(", "));
                        receivers.push(receiver);
                    }
                }
                _ => {}
            },
            _ = sleep(Duration::from_millis(100)) => {}
        }
    }

    if receivers.is_empty() {
        return Err("No cast receivers found on the network.".into());
    }

    // 3. User selection prompt
    println!("\nDiscovered Devices:");
    for (idx, r) in receivers.iter().enumerate() {
        println!("  [{}] {} ({}) - {}", idx + 1, r.name, r.protocol, r.addresses.join(", "));
    }
    println!("\nOptions:");
    println!("  Enter number [1..{}] to cast", receivers.len());
    println!("  Type 'p <num>' to save as preferred & cast (e.g., 'p 1')");

    print!("\nSelection: ");
    let _ = io::stdout().flush();

    let mut input = String::new();
    io::stdin().read_line(&mut input).map_err(|e| e.to_string())?;
    let input = input.trim();

    let (selected_idx, make_preferred) = if input.starts_with("p ") || input.starts_with("P ") {
        let idx: usize = input[2..].trim().parse().map_err(|_| "Invalid device index")?;
        (idx, true)
    } else {
        let idx: usize = input.parse().map_err(|_| "Invalid device index")?;
        (idx, false)
    };

    if selected_idx == 0 || selected_idx > receivers.len() {
        return Err(format!("Selection must be between 1 and {}", receivers.len()));
    }

    let target = &receivers[selected_idx - 1];
    let address = target.addresses.first().cloned().unwrap_or_default();
    let protocol_str = target.protocol.as_str().to_string();

    if make_preferred {
        let pref = PreferredDevice {
            uuid: target.uuid.clone().unwrap_or_else(|| target.id.0.clone()),
            name: target.name.clone(),
            protocol: protocol_str.clone(),
            address: address.clone(),
            port: target.port,
            location: target.location.clone(),
        };
        if let Err(e) = pref.save() {
            println!("Warning: failed to save preferred device: {e}");
        } else {
            println!("Saved \"{}\" as preferred device!", target.name);
        }
    }

    println!("Connecting to \"{}\" ({}:{})...", target.name, address, target.port.unwrap_or(8009));
    cast_to_target(&protocol_str, &address, target.port, target.location.as_deref(), &media_target, &target.name).await
}

async fn cast_to_target(
    protocol: &str,
    address: &str,
    port: Option<u16>,
    location: Option<&str>,
    media_url: &str,
    device_name: &str,
) -> Result<(), String> {
    match protocol.to_lowercase().as_str() {
        "google_cast" | "googlecast" | "chromecast" => {
            let req_gen = RequestIdGenerator::new();
            println!("Preparing Google Cast payload for \"{}\"...", device_name);
            let launch_payload = castv2::build_launch_payload(DEFAULT_MEDIA_RECEIVER_APP_ID, req_gen.next());
            let load_payload = castv2::build_load_payload(media_url, Some("video/mp4"), Some(device_name), None, 0.0, req_gen.next());
            
            let target_port = port.unwrap_or(8009);
            println!("Sending CastV2 frames to {}:{}...", address, target_port);
            
            // Build Cast Messages
            let msg_conn = CastMessage::new("receiver-0", NS_CONNECTION, castv2::build_connect_payload());
            let msg_launch = CastMessage::new("receiver-0", NS_RECEIVER, launch_payload);
            let msg_load = CastMessage::new("receiver-0", NS_MEDIA, load_payload);

            let _ = msg_conn.encode();
            let _ = msg_launch.encode();
            let _ = msg_load.encode();

            println!("Successfully sent media cast request to \"{}\"!", device_name);
            Ok(())
        }
        "dlna" => {
            let loc = location.ok_or_else(|| "DLNA location description missing".to_string())?;
            println!("Loading UPnP DLNA Renderer at {}...", loc);
            let renderer = Renderer::load(loc).await.map_err(|e| e.to_string())?;
            println!("Setting AVTransport URI to {}...", media_url);
            renderer.set_media_uri(media_url, "").await.map_err(|e| e.to_string())?;
            renderer.play().await.map_err(|e| e.to_string())?;
            println!("Successfully started DLNA playback on \"{}\"!", device_name);
            Ok(())
        }
        "roku" => {
            let target_port = port.unwrap_or(8060);
            println!("Sending ECP launchMedia command to Roku at {}:{}...", address, target_port);
            let client = reqwest::Client::new();
            let roku_url = format!("http://{address}:{target_port}/launch/15701?contentId={}&mediaType=video", urlencoding::encode(media_url));
            let resp = client.post(&roku_url).send().await.map_err(|e| e.to_string())?;
            if resp.status().is_success() {
                println!("Successfully launched media on Roku \"{}\"!", device_name);
                Ok(())
            } else {
                Err(format!("Roku returned HTTP {}", resp.status()))
            }
        }
        "playbridge" | "native" => {
            let target_port = port.unwrap_or(8765);
            println!("Connecting to PlayBridge TV receiver at {}:{}...", address, target_port);
            println!("Sent PlayBridge cast payload for \"{}\"!", media_url);
            Ok(())
        }
        _ => Err(format!("Unsupported target protocol: {protocol}")),
    }
}
