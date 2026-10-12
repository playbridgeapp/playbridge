use std::fs::{self, OpenOptions};
use std::io::{self, IsTerminal, Write};
use std::path::{Path, PathBuf};
use std::time::Duration;

use base64::{Engine, engine::general_purpose::STANDARD as BASE64};
use playbridge_cast_core::atv_remote::{
    AtvError, AtvState, CONTROL_PORT, ClientIdentity, ControlSession, DIR_END_LONG, DIR_SHORT,
    DIR_START_LONG, PAIRING_PORT, discover, fingerprint_hex, key_code_from_name, start_pairing,
};
use serde::{Deserialize, Serialize};

use super::RunError;

pub(crate) const USAGE: &str = "\
Android TV remote

Usage:
  playbridge atv discover [--json] [--timeout <seconds>]
  playbridge atv pair <host> [--name <name>] [--port <port>]
  playbridge atv status <host> [--json] [--port <port>]
  playbridge atv key <host> <KEY> [--long] [--port <port>]
  playbridge atv volume <host> up|down|mute [--port <port>]
  playbridge atv text <host> <text>
  playbridge atv app <host> <package|uri>

Keys include DPAD_UP, HOME, BACK, VOLUME_UP, and KEYCODE_ names.
KEYCODE_ENTER is 66 and KEYCODE_MUTE is microphone mute. Bare MUTE is speaker mute.
Package ids are preferred:
  playbridge atv app <host> com.google.android.youtube.tv
pair prompts for the on-screen code. Credentials stay in ~/.config/playbridge/atv/.";

const DEFAULT_NAME: &str = "PlayBridge";
const DISCOVER_TIMEOUT: Duration = Duration::from_secs(5);

pub(crate) async fn run_atv(arguments: &[String]) -> Result<(), RunError> {
    if arguments.is_empty()
        || arguments
            .iter()
            .any(|argument| argument == "--help" || argument == "-h")
    {
        println!("{USAGE}");
        return Ok(());
    }
    let command = arguments[0].as_str();
    let flags = parse_flags(&arguments[1..])?;
    match command {
        "discover" => discover_command(flags).await,
        "pair" => pair_command(flags).await,
        "status" => status_command(flags).await,
        "key" => key_command(flags).await,
        "volume" => volume_command(flags).await,
        "text" => text_command(flags).await,
        "app" => app_command(flags).await,
        _ => Err(RunError::usage(
            "expected: playbridge atv <discover|pair|status|key|volume|text|app>",
        )),
    }
}

struct Flags {
    json: bool,
    long_press: bool,
    port: Option<u16>,
    timeout: Option<u64>,
    name: Option<String>,
    positionals: Vec<String>,
}

fn parse_flags(arguments: &[String]) -> Result<Flags, RunError> {
    let mut flags = Flags {
        json: false,
        long_press: false,
        port: None,
        timeout: None,
        name: None,
        positionals: Vec::new(),
    };
    let mut index = 0;
    while index < arguments.len() {
        let argument = &arguments[index];
        match argument.as_str() {
            "--json" => flags.json = true,
            "--long" => flags.long_press = true,
            "--port" => {
                flags.port = Some(parse_u16(require_value(arguments, &mut index, "--port")?)?);
            }
            "--timeout" => {
                flags.timeout = Some(
                    require_value(arguments, &mut index, "--timeout")?
                        .parse()
                        .map_err(|_| RunError::usage("timeout must be a number of seconds"))?,
                );
            }
            "--name" => {
                flags.name = Some(require_value(arguments, &mut index, "--name")?.to_owned());
            }
            value if value.starts_with('-') => {
                return Err(RunError::usage(format!("unknown atv option {value}")));
            }
            value => flags.positionals.push(value.to_owned()),
        }
        index += 1;
    }
    Ok(flags)
}

fn require_value<'a>(
    arguments: &'a [String],
    index: &mut usize,
    flag: &str,
) -> Result<&'a str, RunError> {
    *index += 1;
    arguments
        .get(*index)
        .map(String::as_str)
        .ok_or_else(|| RunError::usage(format!("{flag} needs a value")))
}

fn parse_u16(value: &str) -> Result<u16, RunError> {
    value
        .parse()
        .map_err(|_| RunError::usage(format!("invalid port {value}")))
}

async fn discover_command(flags: Flags) -> Result<(), RunError> {
    if !flags.positionals.is_empty() {
        return Err(RunError::usage("atv discover does not take a host"));
    }
    let timeout = flags
        .timeout
        .map(Duration::from_secs)
        .unwrap_or(DISCOVER_TIMEOUT);
    let devices = discover(timeout).await.map_err(map_atv)?;
    if flags.json {
        println!(
            "{}",
            serde_json::to_string_pretty(&devices).map_err(|error| op(error.to_string()))?
        );
        return Ok(());
    }
    if devices.is_empty() {
        println!("No Android TV remote devices found.");
        return Ok(());
    }
    for device in devices {
        let bt = device
            .bluetooth_mac()
            .map(|mac| format!(" bt={mac}"))
            .unwrap_or_default();
        println!("{}  {}:{}{bt}", device.name, device.host, device.port);
    }
    Ok(())
}

async fn pair_command(flags: Flags) -> Result<(), RunError> {
    let host = one_host(&flags, "pair")?.to_owned();
    let port = flags.port.unwrap_or(PAIRING_PORT);
    let name = flags.name.unwrap_or_else(|| DEFAULT_NAME.to_owned());
    let dir = atv_dir()?;
    let identity = load_or_create_identity(&dir, &name)?;
    println!("Pairing with {host}:{port}. Check the TV for a code.");
    let session = start_pairing(&host, port, &identity, &name)
        .await
        .map_err(map_atv)?;
    let code = prompt_code()?;
    let result = session.finish(&code).await.map_err(map_atv)?;
    save_pin(
        &dir,
        &host,
        &PinFile {
            fingerprint_sha256: fingerprint_hex(&result.fingerprint_sha256),
            mac: result.mac.clone(),
            server_name: result.server_name.clone(),
            certificate_der_base64: BASE64.encode(&result.server_certificate_der),
        },
    )?;
    println!("Paired with {host}.");
    println!(
        "Fingerprint: {}",
        fingerprint_hex(&result.fingerprint_sha256)
    );
    if let Some(mac) = result.mac {
        println!("MAC: {mac}");
    }
    Ok(())
}

async fn status_command(flags: Flags) -> Result<(), RunError> {
    let host = one_host(&flags, "status")?;
    let port = flags.port.unwrap_or(CONTROL_PORT);
    let mut session = open_control(&atv_dir()?, host, port).await?;
    let state = wait_for_volume(&mut session, Duration::from_secs(1)).await;
    if flags.json {
        println!(
            "{}",
            serde_json::to_string_pretty(&state_json(host, port, &state))
                .map_err(|error| op(error.to_string()))?
        );
    } else {
        print_state(host, port, &state, None);
    }
    session.shutdown().await.map_err(map_atv)?;
    Ok(())
}

async fn key_command(flags: Flags) -> Result<(), RunError> {
    let host = flags
        .positionals
        .first()
        .ok_or_else(|| RunError::usage("atv key needs a host and a key"))?;
    let name = flags
        .positionals
        .get(1)
        .ok_or_else(|| RunError::usage("atv key needs a key name"))?;
    if flags.positionals.len() != 2 {
        return Err(RunError::usage("atv key needs a host and a key"));
    }
    let key =
        key_code_from_name(name).ok_or_else(|| RunError::usage(format!("unknown key {name}")))?;
    let port = flags.port.unwrap_or(CONTROL_PORT);
    let mut session = open_control(&atv_dir()?, host, port).await?;
    if flags.long_press {
        session.key(key, DIR_START_LONG).await.map_err(map_atv)?;
        tokio::time::sleep(Duration::from_millis(500)).await;
        session.key(key, DIR_END_LONG).await.map_err(map_atv)?;
        println!("Sent {name} (long) to {host}:{port}");
    } else {
        session.key(key, DIR_SHORT).await.map_err(map_atv)?;
        println!("Sent {name} to {host}:{port}");
    }
    session.shutdown().await.map_err(map_atv)?;
    Ok(())
}

async fn volume_command(flags: Flags) -> Result<(), RunError> {
    let host = flags
        .positionals
        .first()
        .ok_or_else(|| RunError::usage("atv volume needs a host and up|down|mute"))?;
    let action = flags
        .positionals
        .get(1)
        .map(String::as_str)
        .ok_or_else(|| RunError::usage("atv volume needs up, down, or mute"))?;
    if flags.positionals.len() != 2 {
        return Err(RunError::usage("atv volume needs a host and up|down|mute"));
    }
    let port = flags.port.unwrap_or(CONTROL_PORT);
    let mut session = open_control(&atv_dir()?, host, port).await?;
    let written = match action {
        "up" => session.volume_up().await.map_err(map_atv)?,
        "down" => session.volume_down().await.map_err(map_atv)?,
        "mute" => session.volume_mute().await.map_err(map_atv)?,
        _ => {
            return Err(RunError::usage(
                "atv volume action must be up, down, or mute",
            ));
        }
    };
    let (state, fresh) = session
        .wait_for_volume_revision(written, Duration::from_secs(1))
        .await;
    print_state(host, port, &state, Some(fresh));
    session.shutdown().await.map_err(map_atv)?;
    Ok(())
}

async fn text_command(flags: Flags) -> Result<(), RunError> {
    let host = flags
        .positionals
        .first()
        .ok_or_else(|| RunError::usage("atv text needs a host and text"))?;
    if flags.positionals.len() < 2 {
        return Err(RunError::usage("atv text needs a host and text"));
    }
    let text = flags.positionals[1..].join(" ");
    if text.is_empty() {
        return Err(op("text must not be empty"));
    }
    let port = flags.port.unwrap_or(CONTROL_PORT);
    let mut session = open_control(&atv_dir()?, host, port).await?;
    session.insert_text(&text).await.map_err(map_atv)?;
    println!(
        "Sent text ({} characters) to {host}:{port}",
        text.chars().count()
    );
    session.shutdown().await.map_err(map_atv)?;
    Ok(())
}

async fn app_command(flags: Flags) -> Result<(), RunError> {
    let host = flags
        .positionals
        .first()
        .ok_or_else(|| RunError::usage("atv app needs a host and a uri"))?;
    let uri = flags
        .positionals
        .get(1)
        .ok_or_else(|| RunError::usage("atv app needs a uri"))?;
    if flags.positionals.len() != 2 {
        return Err(RunError::usage("atv app needs a host and a uri"));
    }
    let port = flags.port.unwrap_or(CONTROL_PORT);
    let mut session = open_control(&atv_dir()?, host, port).await?;
    session.launch_app(uri).await.map_err(map_atv)?;
    println!("Sent app link ({} bytes) to {host}:{port}", uri.len());
    session.shutdown().await.map_err(map_atv)?;
    Ok(())
}

fn one_host<'a>(flags: &'a Flags, command: &str) -> Result<&'a str, RunError> {
    if flags.positionals.len() != 1 {
        return Err(RunError::usage(format!("atv {command} needs a host")));
    }
    Ok(flags.positionals[0].as_str())
}

async fn open_control(dir: &Path, host: &str, port: u16) -> Result<ControlSession, RunError> {
    let identity = load_identity(dir)?;
    let pin = load_pin(dir, host)?;
    let fingerprint = decode_fingerprint(&pin.fingerprint_sha256)
        .ok_or_else(|| op("stored Android TV pin is invalid"))?;
    ControlSession::connect(host, port, &identity, fingerprint)
        .await
        .map_err(map_atv)
}

async fn wait_for_volume(session: &mut ControlSession, limit: Duration) -> AtvState {
    let deadline = tokio::time::Instant::now() + limit;
    loop {
        let state = session.state();
        if state.volume_level.is_some() || tokio::time::Instant::now() >= deadline {
            return state;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
}

fn print_state(host: &str, port: u16, state: &AtvState, fresh_volume: Option<bool>) {
    println!("{host}:{port}");
    println!("Power: {}", if state.is_on { "on" } else { "off" });
    println!("{}", format_volume(state, fresh_volume));
    if let Some(app) = &state.current_app {
        println!("App: {app}");
    }
    if let Some(model) = &state.model {
        println!("Model: {model}");
    }
    if let Some(vendor) = &state.vendor {
        println!("Vendor: {vendor}");
    }
}

pub(crate) fn format_volume(state: &AtvState, fresh_volume: Option<bool>) -> String {
    let stale = if fresh_volume == Some(false) {
        " (last known)"
    } else {
        ""
    };
    match (state.volume_level, state.volume_max) {
        (Some(level), Some(max)) => format!(
            "Volume: {level}/{max}{}{stale}",
            if state.volume_muted { " muted" } else { "" }
        ),
        _ => format!("Volume: unknown{stale}"),
    }
}

fn state_json(host: &str, port: u16, state: &AtvState) -> serde_json::Value {
    serde_json::json!({
        "host": host,
        "port": port,
        "power": state.is_on,
        "volume_level": state.volume_level,
        "volume_max": state.volume_max,
        "muted": state.volume_muted,
        "app": state.current_app,
        "model": state.model,
        "vendor": state.vendor,
    })
}

fn map_atv(error: AtvError) -> RunError {
    match error {
        AtvError::BadCode => op("The code was rejected."),
        other => op(other.to_string()),
    }
}

fn op(message: impl Into<String>) -> RunError {
    RunError::operation(message)
}

fn prompt_code() -> Result<String, RunError> {
    print!("Enter the code shown on the TV: ");
    io::stdout()
        .flush()
        .map_err(|error| op(error.to_string()))?;
    if io::stdin().is_terminal() {
        read_hidden_line()
    } else {
        let mut line = String::new();
        io::stdin()
            .read_line(&mut line)
            .map_err(|error| op(error.to_string()))?;
        Ok(line.trim().to_owned())
    }
}

fn read_hidden_line() -> Result<String, RunError> {
    use crossterm::event::{self, Event, KeyCode, KeyEvent, KeyModifiers};
    use crossterm::terminal::{disable_raw_mode, enable_raw_mode};
    enable_raw_mode().map_err(|error| op(error.to_string()))?;
    let mut code = String::new();
    let result = loop {
        match event::read() {
            Ok(Event::Key(KeyEvent {
                code: KeyCode::Enter,
                ..
            })) => break Ok(code.trim().to_owned()),
            Ok(Event::Key(KeyEvent {
                code: KeyCode::Char('c'),
                modifiers,
                ..
            })) if modifiers.contains(KeyModifiers::CONTROL) => {
                break Err(op("pairing cancelled"));
            }
            Ok(Event::Key(KeyEvent {
                code: KeyCode::Char(character),
                ..
            })) => code.push(character),
            Ok(Event::Key(KeyEvent {
                code: KeyCode::Backspace,
                ..
            })) => {
                code.pop();
            }
            Ok(_) => {}
            Err(error) => break Err(op(error.to_string())),
        }
    };
    let _ = disable_raw_mode();
    println!();
    result
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
struct PinFile {
    fingerprint_sha256: String,
    mac: Option<String>,
    server_name: Option<String>,
    certificate_der_base64: String,
}

pub(crate) fn atv_dir() -> Result<PathBuf, RunError> {
    let home = std::env::var_os("HOME")
        .or_else(|| std::env::var_os("USERPROFILE"))
        .ok_or_else(|| op("home directory is not set"))?;
    Ok(PathBuf::from(home)
        .join(".config")
        .join("playbridge")
        .join("atv"))
}

fn load_or_create_identity(dir: &Path, name: &str) -> Result<ClientIdentity, RunError> {
    let cert_path = dir.join("client-cert.pem");
    let key_path = dir.join("client-key.pem");
    if cert_path.exists() || key_path.exists() {
        return load_identity(dir);
    }
    let identity = ClientIdentity::generate(name).map_err(map_atv)?;
    ensure_private_dir(dir)?;
    write_private(&cert_path, identity.certificate_pem().as_bytes())?;
    write_private(&key_path, identity.private_key_pem().as_bytes())?;
    Ok(identity)
}

fn load_identity(dir: &Path) -> Result<ClientIdentity, RunError> {
    let cert =
        fs::read_to_string(dir.join("client-cert.pem")).map_err(|_| op("pair this TV first"))?;
    let key =
        fs::read_to_string(dir.join("client-key.pem")).map_err(|_| op("pair this TV first"))?;
    ClientIdentity::from_pem(&cert, &key).map_err(map_atv)
}

fn load_pin(dir: &Path, host: &str) -> Result<PinFile, RunError> {
    let path = pin_path(dir, host);
    if !path.exists() {
        return Err(op("pair this TV first"));
    }
    let bytes = fs::read(&path).map_err(|error| op(error.to_string()))?;
    serde_json::from_slice(&bytes).map_err(|_| op("stored Android TV pin is invalid"))
}

pub(crate) fn pin_path(dir: &Path, host: &str) -> PathBuf {
    dir.join("pins").join(format!("{}.json", safe_host(host)))
}

fn save_pin(dir: &Path, host: &str, pin: &PinFile) -> Result<(), RunError> {
    let pins = dir.join("pins");
    ensure_private_dir(&pins)?;
    let bytes = serde_json::to_vec_pretty(pin).map_err(|error| op(error.to_string()))?;
    write_private(&pin_path(dir, host), &bytes)
}

pub(crate) fn safe_host(host: &str) -> String {
    let mut output = String::with_capacity(host.len());
    for character in host.chars() {
        if character.is_ascii_alphanumeric() || character == '.' || character == '-' {
            output.push(character);
        } else {
            output.push('_');
        }
    }
    if output.is_empty() {
        "unknown".to_owned()
    } else {
        output
    }
}

fn decode_fingerprint(hex: &str) -> Option<[u8; 32]> {
    let bytes = hex.as_bytes();
    if bytes.len() != 64 || !bytes.iter().all(u8::is_ascii_hexdigit) {
        return None;
    }
    let mut out = [0_u8; 32];
    for (index, byte) in out.iter_mut().enumerate() {
        let high = hex_nibble(bytes[index * 2])?;
        let low = hex_nibble(bytes[index * 2 + 1])?;
        *byte = (high << 4) | low;
    }
    Some(out)
}

fn hex_nibble(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        b'A'..=b'F' => Some(byte - b'A' + 10),
        _ => None,
    }
}

fn ensure_private_dir(path: &Path) -> Result<(), RunError> {
    fs::create_dir_all(path).map_err(|error| op(error.to_string()))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o700))
            .map_err(|error| op(error.to_string()))?;
    }
    Ok(())
}

fn write_private(path: &Path, contents: &[u8]) -> Result<(), RunError> {
    if let Some(parent) = path.parent() {
        ensure_private_dir(parent)?;
    }
    let mut random = [0_u8; 8];
    getrandom::fill(&mut random).map_err(|error| op(error.to_string()))?;
    let suffix = random
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    let temporary = path.with_extension(format!("tmp.{suffix}"));
    let result = (|| {
        let mut options = OpenOptions::new();
        options.create_new(true).write(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options.open(&temporary)?;
        file.write_all(contents)?;
        file.sync_all()?;
        fs::rename(&temporary, path)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
        }
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result.map_err(|error: std::io::Error| op(error.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn host_filenames_keep_dns_characters() {
        assert_eq!(safe_host("192.168.1.19"), "192.168.1.19");
        assert_eq!(safe_host("fe80::1"), "fe80__1");
        assert_eq!(safe_host("Living Room"), "Living_Room");
    }

    #[test]
    fn usage_lists_the_probe_commands() {
        assert!(USAGE.contains("atv pair"));
        assert!(USAGE.contains("DPAD_UP"));
        assert!(USAGE.contains("com.google.android.youtube.tv"));
    }

    #[test]
    fn malformed_fingerprint_does_not_panic() {
        let corrupt = format!("aé{}", "a".repeat(61));
        assert_eq!(corrupt.len(), 64);
        assert!(decode_fingerprint(&corrupt).is_none());
        let state = AtvState {
            volume_level: Some(12),
            volume_max: Some(100),
            ..AtvState::default()
        };
        assert_eq!(
            format_volume(&state, Some(false)),
            "Volume: 12/100 (last known)"
        );
        assert_eq!(format_volume(&state, Some(true)), "Volume: 12/100");
    }

    #[cfg(unix)]
    #[test]
    fn identity_and_pin_files_are_private() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let identity = load_or_create_identity(dir.path(), "PlayBridge").unwrap();
        let mode = |path: &Path| fs::metadata(path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode(&dir.path().join("client-cert.pem")), 0o600);
        assert_eq!(mode(&dir.path().join("client-key.pem")), 0o600);
        assert_eq!(mode(dir.path()) & 0o700, 0o700);
        save_pin(
            dir.path(),
            "192.168.1.19",
            &PinFile {
                fingerprint_sha256: fingerprint_hex(&identity.fingerprint_sha256()),
                mac: Some("AA:BB:CC:DD:EE:FF".to_owned()),
                server_name: Some("Living Room".to_owned()),
                certificate_der_base64: BASE64.encode(identity.certificate_der()),
            },
        )
        .unwrap();
        let pin = pin_path(dir.path(), "192.168.1.19");
        assert_eq!(mode(&pin), 0o600);
        assert_eq!(mode(&dir.path().join("pins")) & 0o700, 0o700);
        let loaded = load_pin(dir.path(), "192.168.1.19").unwrap();
        assert_eq!(loaded.mac.as_deref(), Some("AA:BB:CC:DD:EE:FF"));
        let missing = load_pin(dir.path(), "10.0.0.8").unwrap_err();
        assert_eq!(missing.message, "pair this TV first");
        assert!(!missing.show_usage);
        let restored = load_identity(dir.path()).unwrap();
        assert_eq!(restored.fingerprint_sha256(), identity.fingerprint_sha256());
    }
}
