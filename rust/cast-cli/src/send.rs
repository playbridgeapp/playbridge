use std::{
    io::{self, Write},
    sync::mpsc,
    time::Duration,
};
use crossterm::{
    cursor, event::{self, Event, KeyCode},
    execute, terminal::{disable_raw_mode, enable_raw_mode, Clear, ClearType},
};
use tokio::time::sleep;

use playbridge_cast_core::{
    castv2::{
        self, CastMessage, DEFAULT_MEDIA_RECEIVER_APP_ID, NS_CONNECTION, NS_MEDIA, NS_RECEIVER,
        RequestIdGenerator,
    },
    discovery::{DiscoveryConfig, DiscoveryEvent, DiscoveryStream, Receiver},
    playbridge::{PairingSession, ReceiverFrame, SenderFrame},
    secure_ws::SecureWebSocket,
    upnp::Renderer,
};

use crate::credentials::PlaybridgeCredentials;
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
            let target_url = media_target.clone();
            let result = match pref.protocol.to_lowercase().as_str() {
                "playbridge" | "native" => {
                    let wss_port = pref.wss_port.or(pref.port).unwrap_or(8765);
                    cast_to_playbridge(&pref.address, wss_port, &pref.name, &pref.uuid, &target_url).await
                }
                _ => {
                    println!("Connecting to preferred device \"{}\" ({}:{})...", pref.name, pref.address, pref.port.unwrap_or(8009));
                    cast_to_target(&pref.protocol, &pref.address, pref.port, pref.location.as_deref(), &target_url, &pref.name).await
                }
            };
            match result {
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

    // 3. Interactive device selection
    let (selected_idx, make_preferred) = interactive_device_select(&receivers)?;

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
            wss_port: target.wss_port,
            location: target.location.clone(),
        };
        if let Err(e) = pref.save() {
            println!("Warning: failed to save preferred device: {e}");
        } else {
            println!("Saved \"{}\" as preferred device!", target.name);
        }
    }

    match protocol_str.to_lowercase().as_str() {
        "playbridge" | "native" => {
            let wss_port = target.wss_port.or(target.port).unwrap_or(8765);
            let uuid = target.uuid.clone().unwrap_or_else(|| target.id.0.clone());
            cast_to_playbridge(&address, wss_port, &target.name, &uuid, &media_target).await
        }
        _ => {
            println!("Connecting to \"{}\" ({}:{})...", target.name, address, target.port.unwrap_or(8009));
            cast_to_target(&protocol_str, &address, target.port, target.location.as_deref(), &media_target, &target.name).await
        }
    }
}

fn interactive_device_select(receivers: &[Receiver]) -> Result<(usize, bool), String> {
    let count = receivers.len();
    if count == 0 {
        return Err("No devices to select from".into());
    }

    // Print initial blank lines so MoveUp can move back up predictably
    for _ in 0..(count + 4) {
        println!();
    }

    enable_raw_mode().map_err(|e| e.to_string())?;

    let mut selection = 0usize;

    let render = |sel: usize| -> Result<(), String> {
        let mut stdout = io::stdout();
        let _ = execute!(
            stdout,
            cursor::MoveToColumn(0),
            cursor::MoveUp((count + 4) as u16),
            Clear(ClearType::FromCursorDown),
        );
        let _ = write!(stdout, "\r\nDiscovered Devices:\r\n");
        for (idx, r) in receivers.iter().enumerate() {
            let prefix = if idx == sel { ">" } else { " " };
            let _ = write!(
                stdout,
                "\r  {} {} ({}) - {}\r\n",
                prefix,
                r.name,
                r.protocol,
                r.addresses.join(", ")
            );
        }
        let _ = write!(
            stdout,
            "\r\n\r[↑/↓] Navigate  [Enter] Cast  [P] Cast & save as preferred  [Q] Cancel\r\n"
        );
        let _ = stdout.flush();
        Ok(())
    };

    let _ = render(selection);

    let result = loop {
        let evt = event::read().map_err(|e| e.to_string())?;
        match evt {
            Event::Key(event::KeyEvent { code, .. }) => match code {
                KeyCode::Up if selection > 0 => {
                    selection -= 1;
                    let _ = render(selection);
                }
                KeyCode::Down if selection < count - 1 => {
                    selection += 1;
                    let _ = render(selection);
                }
                KeyCode::Up | KeyCode::Down => {}
                KeyCode::Enter => {
                    let _ = disable_raw_mode();
                    print!("\r\n");
                    let _ = io::stdout().flush();
                    break Ok((selection + 1, false));
                }
                KeyCode::Char('p') | KeyCode::Char('P') => {
                    let _ = disable_raw_mode();
                    print!("\r\n");
                    let _ = io::stdout().flush();
                    break Ok((selection + 1, true));
                }
                KeyCode::Char('q') | KeyCode::Char('Q') | KeyCode::Esc => {
                    let _ = disable_raw_mode();
                    print!("\r\n");
                    let _ = io::stdout().flush();
                    break Err("Selection cancelled".into());
                }
                _ => {}
            },
            _ => {}
        }
    };

    let _ = disable_raw_mode();
    result
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
            Err("PlayBridge devices must be cast via cast_to_playbridge (internal routing error)".into())
        }
        _ => Err(format!("Unsupported target protocol: {protocol}")),
    }
}

async fn cast_to_playbridge(
    address: &str,
    wss_port: u16,
    device_name: &str,
    device_uuid: &str,
    media_url: &str,
) -> Result<(), String> {
    let endpoint = format!("wss://{address}:{wss_port}");

    // Try stored credentials first
    if let Some(creds) = PlaybridgeCredentials::load(device_uuid) {
        println!("Using stored credentials for \"{}\"...", device_name);
        let mut socket = SecureWebSocket::connect_pinned(&endpoint, &creds.cert_fingerprint)
            .await
            .map_err(|e| e.to_string())?;

        socket
            .send(&SenderFrame::Auth {
                token: creds.token.clone(),
            })
            .await
            .map_err(|e| e.to_string())?;

        loop {
            match socket.receive().await.map_err(|e| e.to_string())? {
                Some(ReceiverFrame::AuthResponse {
                    success: true, ..
                }) => {
                    println!("Authenticated with \"{}\"!", device_name);
                    break;
                }
                Some(ReceiverFrame::AuthResponse {
                    success: false, ..
                }) => return Err("Authentication failed".into()),
                Some(_) => {}
                None => return Err("Receiver closed connection during auth".into()),
            }
        }

        send_playlist(&mut socket, media_url, device_name).await?;
        socket.close().await.map_err(|e| e.to_string())?;
        return Ok(());
    }

    // No stored credentials — full pairing flow
    println!(
        "No stored credentials for \"{}\". Starting pairing...",
        device_name
    );
    let mut socket = SecureWebSocket::connect_for_pairing(&endpoint)
        .await
        .map_err(|e| e.to_string())?;
    let served_pin = socket.served_spki_pin().to_owned();

    let (mut pairing, commit) =
        PairingSession::start(device_name.to_owned(), device_uuid.to_owned())
            .map_err(|e| e.to_string())?;

    socket.send(&commit).await.map_err(|e| e.to_string())?;
    println!("Pairing commit sent. Waiting for receiver challenge...");

    while let Some(frame) = socket.receive().await.map_err(|e| e.to_string())? {
        match frame {
            ReceiverFrame::PairingChallenge {
                tv_eph_pub,
                nonce_t,
            } => {
                let (sas, reveal) = pairing
                    .accept_challenge(&tv_eph_pub, &nonce_t)
                    .map_err(|e| e.to_string())?;
                socket.send(&reveal).await.map_err(|e| e.to_string())?;
                println!("Challenge accepted.");

                print!("Enter the 6-digit code shown on \"{}\": ", device_name);
                io::stdout().flush().map_err(|e| e.to_string())?;
                let mut entered = String::new();
                io::stdin()
                    .read_line(&mut entered)
                    .map_err(|e| e.to_string())?;
                let confirmation = pairing
                    .confirmation(entered.trim(), &sas)
                    .map_err(|e| e.to_string())?;
                socket
                    .send(&confirmation)
                    .await
                    .map_err(|e| e.to_string())?;
                println!("Confirmation sent. Waiting for receiver approval...");
            }
            ReceiverFrame::PairingApproved { nonce, ciphertext } => {
                let bundle = pairing
                    .decrypt_credentials(&nonce, &ciphertext, Some(&served_pin))
                    .map_err(|e| e.to_string())?;
                println!(
                    "Pairing succeeded! Players: {:?}, Browsers: {:?}",
                    bundle.players, bundle.browsers
                );

                let creds = PlaybridgeCredentials {
                    token: bundle.token.clone(),
                    cert_fingerprint: bundle
                        .cert_fingerprint
                        .unwrap_or_else(|| served_pin.clone()),
                    players: bundle.players.clone(),
                    browsers: bundle.browsers.clone(),
                };
                if let Err(e) = creds.save(device_uuid) {
                    println!("Warning: failed to save credentials: {e}");
                } else {
                    println!("Credentials saved for future casts.");
                }

                send_playlist(&mut socket, media_url, device_name).await?;
                socket.close().await.map_err(|e| e.to_string())?;
                return Ok(());
            }
            ReceiverFrame::PairingDenied => {
                return Err("Pairing was denied by the receiver".into());
            }
            _ => {}
        }
    }

    Err("Receiver closed connection before pairing completed".into())
}

async fn send_playlist(
    socket: &mut SecureWebSocket,
    media_url: &str,
    device_name: &str,
) -> Result<(), String> {
    let cmd = SenderFrame::Command {
        action: "playlist".into(),
        payload: Some(serde_json::json!({
            "items": [{
                "url": media_url,
                "title": device_name,
            }]
        })),
    };
    socket.send(&cmd).await.map_err(|e| e.to_string())?;
    println!("Sent playlist command to \"{}\"!", device_name);
    Ok(())
}
