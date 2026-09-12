use std::{
    collections::{BTreeMap, HashSet},
    env,
    process::ExitCode,
    time::Duration,
};

use playbridge_cast_core::discovery::{
    DiscoveryConfig, DiscoveryEvent, DiscoveryStream, Receiver, ReceiverProtocol,
};
use serde::Serialize;

const DEFAULT_TIMEOUT_SECONDS: u64 = 5;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum OutputFormat {
    Human,
    Json,
    JsonLines,
}

#[derive(Debug, PartialEq, Eq)]
struct DiscoverArgs {
    protocols: HashSet<ReceiverProtocol>,
    timeout: Duration,
    output: OutputFormat,
}

#[derive(Debug, Serialize)]
struct JsonReceiver<'a> {
    id: &'a str,
    protocol: &'static str,
    name: &'a str,
    addresses: &'a [String],
    port: Option<u16>,
    wss_port: Option<u16>,
    location: Option<&'a str>,
    uuid: Option<&'a str>,
}

#[derive(Debug, Serialize)]
struct JsonReport<'a> {
    receivers: Vec<JsonReceiver<'a>>,
    errors: &'a [OwnedDiscoveryError],
}

#[derive(Debug, Serialize)]
struct OwnedDiscoveryError {
    protocol: &'static str,
    message: String,
}

#[derive(Debug, Serialize)]
#[serde(tag = "event", rename_all = "snake_case")]
enum JsonLine<'a> {
    Started {
        protocol: &'static str,
    },
    Found {
        receiver: JsonReceiver<'a>,
    },
    Updated {
        receiver: JsonReceiver<'a>,
    },
    Error {
        protocol: &'static str,
        message: &'a str,
    },
    Finished {
        protocol: &'static str,
    },
}

mod credentials;
mod google_cast;
mod json_session;
mod mcp;
mod preferred;
mod receive;
mod send;
mod ui;
mod update;
mod update_installer;

use google_cast::run_google_cast;

#[derive(Debug, PartialEq, Eq)]
struct RunError {
    message: String,
    show_usage: bool,
}

impl RunError {
    fn usage(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            show_usage: true,
        }
    }

    fn failed() -> Self {
        Self {
            message: String::new(),
            show_usage: false,
        }
    }
}

impl From<String> for RunError {
    fn from(message: String) -> Self {
        Self::usage(message)
    }
}

impl From<&str> for RunError {
    fn from(message: &str) -> Self {
        Self::usage(message)
    }
}

#[tokio::main]
async fn main() -> ExitCode {
    let arguments = env::args().skip(1).collect::<Vec<_>>();
    match run(arguments).await {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            if error.show_usage {
                eprintln!("error: {}", error.message);
                eprintln!();
                eprintln!("{}", usage());
            }
            ExitCode::from(2)
        }
    }
}

async fn run(arguments: Vec<String>) -> Result<(), RunError> {
    let (arguments, globals) = GlobalOptions::extract(arguments)?;
    if arguments
        .first()
        .is_some_and(|value| value == "--help" || value == "-h")
    {
        println!("{}", usage());
        return Ok(());
    }
    if arguments
        .first()
        .is_some_and(|value| value == "--version" || value == "-V")
    {
        println!("playbridge {}", env!("CARGO_PKG_VERSION"));
        return Ok(());
    }
    let Some(command) = arguments.first() else {
        return run_dashboard(globals.theme.as_deref(), ui::DashboardLaunch::Home).await;
    };

    match command.as_str() {
        "dashboard" | "tui" => {
            run_dashboard(globals.theme.as_deref(), ui::DashboardLaunch::Home).await
        }
        "send" | "cast" => {
            if arguments[1..]
                .iter()
                .any(|value| value == "--help" || value == "-h")
            {
                println!("{}", usage());
                return Ok(());
            }
            let machine = arguments[1..].iter().any(|value| value == "--json");
            match parse_send_args(&arguments[1..]) {
                Ok(args) => {
                    if machine {
                        send::run_json_cast(
                            args.target,
                            args.device,
                            args.pair_code,
                            args.pair_code_file,
                            args.session_id,
                            args.skip_history,
                        )
                        .await
                        .map_err(|_| RunError::failed())
                    } else {
                        send::validate_media_target(&args.target)?;
                        run_dashboard(
                            globals.theme.as_deref(),
                            ui::DashboardLaunch::Cast {
                                source: Some(args.target),
                                browser: false,
                                skip_history: args.skip_history,
                            },
                        )
                        .await
                    }
                }
                Err(message) => {
                    if machine {
                        let error = if message.starts_with("unknown send option:") {
                            "unknown_option"
                        } else if message.contains("single media file") {
                            "invalid_arguments"
                        } else {
                            "missing_media_target"
                        };
                        let _ = send::emit_json(&serde_json::json!({
                            "ok": false,
                            "error": error,
                            "message": message,
                        }));
                        Err(RunError::failed())
                    } else {
                        Err(RunError::usage(message))
                    }
                }
            }
        }
        "mcp" => {
            if arguments[1..]
                .iter()
                .any(|value| value == "--help" || value == "-h")
            {
                println!("{}", mcp::usage());
                return Ok(());
            }
            mcp::run().await.map_err(RunError::usage)
        }
        "status" => {
            if arguments[1..]
                .iter()
                .any(|value| value == "--help" || value == "-h")
            {
                println!("{}", usage());
                return Ok(());
            }
            match json_session::parse_status_args(&arguments[1..]) {
                Ok(session_id) => {
                    send::run_json_status(session_id.as_deref()).map_err(|_| RunError::failed())
                }
                Err(message) => {
                    let _ = send::emit_json(&serde_json::json!({
                        "ok": false,
                        "error": "invalid_arguments",
                        "message": message,
                    }));
                    Err(RunError::failed())
                }
            }
        }
        "control" => {
            if arguments[1..]
                .iter()
                .any(|value| value == "--help" || value == "-h")
            {
                println!("{}", usage());
                return Ok(());
            }
            match json_session::parse_control_args(&arguments[1..]) {
                Ok((session_id, request)) => send::run_json_control(session_id.as_deref(), request)
                    .await
                    .map_err(|_| RunError::failed()),
                Err(message) => {
                    let _ = send::emit_json(&serde_json::json!({
                        "ok": false,
                        "error": "invalid_arguments",
                        "message": message,
                    }));
                    Err(RunError::failed())
                }
            }
        }
        "browser" => {
            let Some(target) = arguments.get(1) else {
                return Err("missing media file or URL for browser receiver".into());
            };
            send::validate_media_target(target)?;
            run_dashboard(
                globals.theme.as_deref(),
                ui::DashboardLaunch::Cast {
                    source: Some(target.clone()),
                    browser: true,
                    skip_history: None,
                },
            )
            .await
        }
        "receiver" | "receive" => {
            if arguments[1..]
                .iter()
                .any(|value| value == "--help" || value == "-h")
            {
                println!("{}", usage());
                return Ok(());
            }
            run_dashboard(
                globals.theme.as_deref(),
                ui::DashboardLaunch::Receiver {
                    arguments: arguments[1..].to_vec(),
                    auto_start: true,
                },
            )
            .await
        }
        "discover" => {
            if arguments[1..]
                .iter()
                .any(|value| value == "--help" || value == "-h")
            {
                println!("{}", usage());
                return Ok(());
            }
            let args = parse_discover_args(&arguments[1..])?;
            if args.output == OutputFormat::Human {
                run_dashboard(
                    globals.theme.as_deref(),
                    ui::DashboardLaunch::Discover {
                        protocols: args.protocols,
                        timeout: args.timeout,
                    },
                )
                .await
            } else {
                discover(args).await.map_err(RunError::from)
            }
        }
        "google-cast" | "googlecast" => run_google_cast(&arguments[1..])
            .await
            .map_err(RunError::from),
        "preferred" => {
            if let Some(sub) = arguments.get(1)
                && sub == "clear"
            {
                return run_dashboard(
                    globals.theme.as_deref(),
                    ui::DashboardLaunch::Settings {
                        clear_preferred: true,
                    },
                )
                .await;
            }
            run_dashboard(
                globals.theme.as_deref(),
                ui::DashboardLaunch::Settings {
                    clear_preferred: false,
                },
            )
            .await
        }
        "config" => match arguments.get(1).map(String::as_str) {
            Some("path") => {
                let path = ui::config_path().ok_or("could not determine config path")?;
                println!("{}", path.display());
                Ok(())
            }
            Some("check") => {
                ui::validate_config(globals.theme.as_deref())?;
                println!("PlayBridge CLI configuration is valid.");
                Ok(())
            }
            Some("skip-history") => match arguments.get(2).map(String::as_str) {
                None => {
                    let enabled = ui::skip_history_default()?;
                    println!("{}", if enabled { "on" } else { "off" });
                    Ok(())
                }
                Some("on" | "true") if arguments.len() == 3 => {
                    ui::set_skip_history_default(true)?;
                    println!("Skip history default is on.");
                    Ok(())
                }
                Some("off" | "false") if arguments.len() == 3 => {
                    ui::set_skip_history_default(false)?;
                    println!("Skip history default is off.");
                    Ok(())
                }
                _ => Err("expected: playbridge config skip-history [on|off]".into()),
            },
            _ => Err("expected: playbridge config <path|check|skip-history>".into()),
        },
        target => {
            // Default to sending the media target directly
            send::validate_media_target(target)?;
            run_dashboard(
                globals.theme.as_deref(),
                ui::DashboardLaunch::Cast {
                    source: Some(target.to_owned()),
                    browser: false,
                    skip_history: None,
                },
            )
            .await
        }
    }
}

async fn run_dashboard(
    theme_override: Option<&str>,
    launch: ui::DashboardLaunch,
) -> Result<(), RunError> {
    if !ui::dashboard_available() {
        return Err(
            "the PlayBridge dashboard requires an interactive terminal; use `discover --json` or `discover --json-lines` for machine-readable discovery"
                .into(),
        );
    }
    ui::run_dashboard(theme_override, launch)
        .await
        .map_err(RunError::from)
}

#[derive(Debug, Default, PartialEq, Eq)]
struct GlobalOptions {
    theme: Option<String>,
}

impl GlobalOptions {
    fn extract(arguments: Vec<String>) -> Result<(Vec<String>, Self), String> {
        let mut remaining = Vec::with_capacity(arguments.len());
        let mut options = Self::default();
        let mut index = 0;
        while index < arguments.len() {
            match arguments[index].as_str() {
                "--no-tui" => {
                    return Err(
                        "--no-tui has been removed; PlayBridge interactive workflows now run in the dashboard"
                            .into(),
                    );
                }
                "--theme" => {
                    index += 1;
                    options.theme = Some(
                        arguments
                            .get(index)
                            .ok_or("--theme requires a value")?
                            .clone(),
                    );
                }
                value if value.starts_with("--theme=") => {
                    options.theme = Some(value["--theme=".len()..].to_owned());
                }
                _ => remaining.push(arguments[index].clone()),
            }
            index += 1;
        }
        Ok((remaining, options))
    }
}

#[derive(Debug, PartialEq, Eq)]
struct SendArgs {
    target: String,
    device: Option<String>,
    pair_code: Option<String>,
    pair_code_file: Option<String>,
    session_id: Option<String>,
    skip_history: Option<bool>,
}

fn parse_send_args(arguments: &[String]) -> Result<SendArgs, String> {
    let mut target = None;
    let mut device = None;
    let mut pair_code = None;
    let mut pair_code_file = None;
    let mut session_id = None;
    let mut skip_history = None;
    let mut index = 0;
    while index < arguments.len() {
        match arguments[index].as_str() {
            "--json" | "--help" | "-h" => {}
            "--device" => {
                index += 1;
                device = Some(
                    arguments
                        .get(index)
                        .ok_or("--device requires a value")?
                        .clone(),
                );
            }
            value if value.starts_with("--device=") => {
                device = Some(value["--device=".len()..].to_owned());
            }
            "--pair-code" => {
                index += 1;
                pair_code = Some(
                    arguments
                        .get(index)
                        .ok_or("--pair-code requires a value")?
                        .clone(),
                );
            }
            value if value.starts_with("--pair-code=") => {
                pair_code = Some(value["--pair-code=".len()..].to_owned());
            }
            "--pair-code-file" => {
                index += 1;
                pair_code_file = Some(
                    arguments
                        .get(index)
                        .ok_or("--pair-code-file requires a value")?
                        .clone(),
                );
            }
            value if value.starts_with("--pair-code-file=") => {
                pair_code_file = Some(value["--pair-code-file=".len()..].to_owned());
            }
            "--session-id" => {
                index += 1;
                session_id = Some(
                    arguments
                        .get(index)
                        .ok_or("--session-id requires a value")?
                        .clone(),
                );
            }
            value if value.starts_with("--session-id=") => {
                session_id = Some(value["--session-id=".len()..].to_owned());
            }
            "--skip-history" => {
                if skip_history == Some(false) {
                    return Err("--skip-history and --save-history cannot be combined".into());
                }
                skip_history = Some(true);
            }
            "--save-history" => {
                if skip_history == Some(true) {
                    return Err("--skip-history and --save-history cannot be combined".into());
                }
                skip_history = Some(false);
            }
            value if value.starts_with('-') => {
                return Err(format!("unknown send option: {value}"));
            }
            value => {
                if target.is_some() {
                    return Err("send accepts a single media file or URL".into());
                }
                target = Some(value.to_owned());
            }
        }
        index += 1;
    }
    if pair_code.is_some() && pair_code_file.is_some() {
        return Err("--pair-code and --pair-code-file cannot be combined".into());
    }
    Ok(SendArgs {
        target: target.ok_or_else(|| "missing media file or URL to send".to_owned())?,
        device,
        pair_code,
        pair_code_file,
        session_id,
        skip_history,
    })
}

fn parse_discover_args(arguments: &[String]) -> Result<DiscoverArgs, String> {
    let mut protocols = HashSet::new();
    let mut timeout = Duration::from_secs(DEFAULT_TIMEOUT_SECONDS);
    let mut output = OutputFormat::Human;
    let mut index = 0;
    while index < arguments.len() {
        match arguments[index].as_str() {
            "--protocol" | "-p" => {
                index += 1;
                let value = arguments
                    .get(index)
                    .ok_or_else(|| "--protocol requires a value".to_owned())?;
                add_protocols(&mut protocols, value)?;
            }
            "--timeout" | "-t" => {
                index += 1;
                let value = arguments
                    .get(index)
                    .ok_or_else(|| "--timeout requires seconds".to_owned())?;
                let seconds = value
                    .parse::<u64>()
                    .map_err(|_| format!("invalid timeout: {value}"))?;
                if seconds == 0 || seconds > 300 {
                    return Err("timeout must be between 1 and 300 seconds".into());
                }
                timeout = Duration::from_secs(seconds);
            }
            "--json" => set_output(&mut output, OutputFormat::Json)?,
            "--json-lines" => set_output(&mut output, OutputFormat::JsonLines)?,
            unknown => return Err(format!("unknown discover option: {unknown}")),
        }
        index += 1;
    }
    if protocols.is_empty() {
        protocols.extend(ReceiverProtocol::DEFAULTS);
    }
    Ok(DiscoverArgs {
        protocols,
        timeout,
        output,
    })
}

fn add_protocols(selected: &mut HashSet<ReceiverProtocol>, value: &str) -> Result<(), String> {
    for name in value.split(',') {
        if name.eq_ignore_ascii_case("all") {
            selected.extend(ReceiverProtocol::ALL);
        } else {
            selected.insert(
                name.parse::<ReceiverProtocol>()
                    .map_err(|error| error.to_string())?,
            );
        }
    }
    Ok(())
}

fn set_output(current: &mut OutputFormat, requested: OutputFormat) -> Result<(), String> {
    if *current != OutputFormat::Human && *current != requested {
        return Err("--json and --json-lines cannot be combined".into());
    }
    *current = requested;
    Ok(())
}

async fn discover(args: DiscoverArgs) -> Result<(), String> {
    let mut stream =
        DiscoveryStream::start(DiscoveryConfig::selected(args.protocols, args.timeout));
    let mut receivers = BTreeMap::<String, Receiver>::new();
    let mut errors = Vec::<OwnedDiscoveryError>::new();
    while let Some(event) = stream.next().await {
        if args.output == OutputFormat::JsonLines {
            println!(
                "{}",
                serde_json::to_string(&json_line(&event)).map_err(|error| error.to_string())?
            );
        } else if args.output == OutputFormat::Human {
            print_human_event(&event);
        }
        match event {
            DiscoveryEvent::Found(receiver) | DiscoveryEvent::Updated(receiver) => {
                receivers.insert(receiver.id.0.clone(), receiver);
            }
            DiscoveryEvent::Error { protocol, message } => errors.push(OwnedDiscoveryError {
                protocol: protocol.as_str(),
                message,
            }),
            DiscoveryEvent::Started(_) | DiscoveryEvent::Finished(_) => {}
        }
    }
    if args.output == OutputFormat::Json {
        let report = JsonReport {
            receivers: receivers.values().map(json_receiver).collect(),
            errors: &errors,
        };
        println!(
            "{}",
            serde_json::to_string_pretty(&report).map_err(|error| error.to_string())?
        );
    }
    Ok(())
}

fn print_human_event(event: &DiscoveryEvent) {
    match event {
        DiscoveryEvent::Found(receiver) => print_receiver("found", receiver),
        DiscoveryEvent::Updated(receiver) => print_receiver("updated", receiver),
        DiscoveryEvent::Error { protocol, message } => {
            eprintln!("{protocol} discovery error: {message}");
        }
        DiscoveryEvent::Started(_) | DiscoveryEvent::Finished(_) => {}
    }
}

fn print_receiver(action: &str, receiver: &Receiver) {
    println!(
        "{action}\t{}\t{}\t{}\t{}",
        receiver.protocol,
        receiver.name,
        receiver.addresses.join(","),
        receiver
            .port
            .map_or_else(|| "-".into(), |port| port.to_string())
    );
}

fn json_receiver(receiver: &Receiver) -> JsonReceiver<'_> {
    JsonReceiver {
        id: &receiver.id.0,
        protocol: receiver.protocol.as_str(),
        name: &receiver.name,
        addresses: &receiver.addresses,
        port: receiver.port,
        wss_port: receiver.wss_port,
        location: receiver.location.as_deref(),
        uuid: receiver.uuid.as_deref(),
    }
}

fn json_line(event: &DiscoveryEvent) -> JsonLine<'_> {
    match event {
        DiscoveryEvent::Started(protocol) => JsonLine::Started {
            protocol: protocol.as_str(),
        },
        DiscoveryEvent::Found(receiver) => JsonLine::Found {
            receiver: json_receiver(receiver),
        },
        DiscoveryEvent::Updated(receiver) => JsonLine::Updated {
            receiver: json_receiver(receiver),
        },
        DiscoveryEvent::Error { protocol, message } => JsonLine::Error {
            protocol: protocol.as_str(),
            message,
        },
        DiscoveryEvent::Finished(protocol) => JsonLine::Finished {
            protocol: protocol.as_str(),
        },
    }
}

fn usage() -> &'static str {
    r#"PlayBridge CLI

Usage:
  playbridge [global options]
  playbridge [global options] <filename|URL>
  playbridge [global options] <command> [options]

Dashboard Commands:
  dashboard                         Open the interactive dashboard
  <filename|URL>                    Open Cast with a source preselected
  send|cast <filename|URL>          Open Cast with a source preselected
  receiver [options]                Open Receiver and start the local mpv receiver
  discover [options]                Open Discover for human-readable scans
  browser <filename|URL>            Host and pair a browser receiver in Cast
  preferred                         Open Settings for the preferred receiver
  preferred clear                   Open Settings and clear the preferred receiver

Machine Commands:
  send|cast <filename|URL> --json   Cast to the preferred receiver and print JSON events
  mcp                               Run a stdio MCP server for AI agents
  status [--json] [--session-id]    Print status of a JSON send session
  control <pause|play|toggle|stop|seek|volume|mute|speed> [--json]
                                    Control the active JSON send session
  discover --json                   Print one final discovery report
  discover --json-lines             Stream discovery events
  google-cast status [options]      Query Google Cast status without launching
  google-cast launch [options]      Launch or join a Google Cast receiver
  config <path|check>               Locate or validate UI configuration
  config skip-history [on|off]      Show or change the cast history default

Global Options:
      --theme <name>               Override the configured UI theme
  -V, --version                    Print the CLI version
  -h, --help                       Show this help

Control Options:
  pause|play|toggle|stop          Transport controls
  seek <seconds>                  Relative seek; negative seeks backward
  volume <delta>                  Relative volume in -1..1
  mute                            Toggle mute
  speed <value>                   Playback speed

Send Options:
      --json                      Cast without the dashboard (preferred receiver, or discover)
      --device <id>               Receiver id, uuid, name, or address
      --pair-code <code>          SAS code shown by a PlayBridge receiver
      --pair-code-file <path>     Wait for that file to contain the SAS code
      --session-id <id>           Address one machine-mode cast session
      --skip-history              Do not save this cast to receiver history
      --save-history              Save this cast, overriding the configured default

Discover Options:
  -p, --protocol <names>           playbridge, native, dlna, roku, dial, googlecast,
                                  or all; repeat or use comma-separated names
  -t, --timeout <seconds>          Bounded scan duration (1-300, default 5)
      --json                      Print one final JSON report without the dashboard
      --json-lines                Stream JSON events without the dashboard

Google Cast Options:
      --device <name>             Select a discovered Google Cast receiver
      --address <address>         Connect directly instead of discovering
      --port <port>               CastV2 port (default 8009)
      --app-id <id>               Receiver application ID (or PLAYBRIDGE_GOOGLE_CAST_APP_ID)
      --json                      Print the receiver status as JSON

Receiver Options:
      --name <name>               Receiver name advertised on the LAN
      --port <port>               Preferred WSS port (default 8765; tries 10 ports)

Interactive workflows require a terminal and remain inside the dashboard.
Use JSON discovery or the diagnostic commands above for machine-readable output."#
}

#[cfg(test)]
mod tests {
    use super::*;

    fn strings(values: &[&str]) -> Vec<String> {
        values.iter().map(|value| (*value).to_owned()).collect()
    }

    #[test]
    fn defaults_to_automatic_protocols() {
        let args = parse_discover_args(&[]).unwrap();
        assert_eq!(args.protocols, HashSet::from(ReceiverProtocol::DEFAULTS));
        assert_eq!(args.timeout, Duration::from_secs(5));
    }

    #[test]
    fn supports_one_multiple_and_all_protocol_selection() {
        let one = parse_discover_args(&strings(&["-p", "roku"])).unwrap();
        assert_eq!(one.protocols, HashSet::from([ReceiverProtocol::Roku]));

        let multiple =
            parse_discover_args(&strings(&["-p", "native,dlna", "-p", "roku,googlecast"])).unwrap();
        assert_eq!(
            multiple.protocols,
            HashSet::from(ReceiverProtocol::DEFAULTS)
        );

        let all = parse_discover_args(&strings(&["--protocol", "all"])).unwrap();
        assert_eq!(all.protocols, HashSet::from(ReceiverProtocol::ALL));
    }

    #[test]
    fn validates_timeout_and_output_mode() {
        assert!(parse_discover_args(&strings(&["--timeout", "0"])).is_err());
        assert!(parse_discover_args(&strings(&["--timeout", "301"])).is_err());
        assert!(parse_discover_args(&strings(&["--json", "--json-lines"])).is_err());
    }

    #[test]
    fn json_line_schema_has_stable_protocol_name() {
        let event = DiscoveryEvent::Started(ReceiverProtocol::PlayBridge);
        let json = serde_json::to_value(json_line(&event)).unwrap();
        assert_eq!(json["event"], "started");
        assert_eq!(json["protocol"], "playbridge");
    }

    #[test]
    fn extracts_dashboard_global_options_without_reordering_commands() {
        let (arguments, options) =
            GlobalOptions::extract(strings(&["--theme", "terminal", "send", "video.mp4"])).unwrap();
        assert_eq!(arguments, strings(&["send", "video.mp4"]));
        assert_eq!(options.theme.as_deref(), Some("terminal"));
    }

    #[test]
    fn help_distinguishes_dashboard_and_direct_command_routes() {
        let help = usage();
        assert!(help.contains("Dashboard Commands:"));
        assert!(help.contains("Machine Commands:"));
        assert!(help.contains("send|cast <filename|URL> --json"));
        assert!(help.contains("mcp"));
        assert!(help.contains("status [--json]"));
        assert!(help.contains("control <pause|play|toggle|stop|seek|volume|mute|speed>"));
        assert!(help.contains("Interactive workflows require a terminal"));
    }

    #[test]
    fn parse_send_args_accepts_json_flag_before_or_after_target() {
        let after = parse_send_args(&strings(&["video.mp4", "--json"])).unwrap();
        assert_eq!(after.target, "video.mp4");
        assert_eq!(after.device, None);
        let before = parse_send_args(&strings(&["--json", "https://example.test/a.m3u8"])).unwrap();
        assert_eq!(before.target, "https://example.test/a.m3u8");
    }

    #[test]
    fn parse_send_args_accepts_history_overrides() {
        let skipped = parse_send_args(&strings(&["video.mp4", "--skip-history"])).unwrap();
        assert_eq!(skipped.skip_history, Some(true));
        let saved = parse_send_args(&strings(&["video.mp4", "--save-history"])).unwrap();
        assert_eq!(saved.skip_history, Some(false));
        assert!(
            parse_send_args(&strings(&["video.mp4", "--skip-history", "--save-history"])).is_err()
        );
    }

    #[test]
    fn parse_send_args_accepts_device_and_pair_code() {
        let args = parse_send_args(&strings(&[
            "--json",
            "video.mp4",
            "--device",
            "Living Room",
            "--pair-code",
            "123456",
        ]))
        .unwrap();
        assert_eq!(args.target, "video.mp4");
        assert_eq!(args.device.as_deref(), Some("Living Room"));
        assert_eq!(args.pair_code.as_deref(), Some("123456"));
        assert_eq!(args.pair_code_file, None);
        assert_eq!(args.session_id, None);
        let file = parse_send_args(&strings(&[
            "video.mp4",
            "--pair-code-file",
            "/tmp/playbridge-sas",
        ]))
        .unwrap();
        assert_eq!(file.pair_code_file.as_deref(), Some("/tmp/playbridge-sas"));
        let scoped = parse_send_args(&strings(&[
            "video.mp4",
            "--json",
            "--session-id",
            "agent-123",
        ]))
        .unwrap();
        assert_eq!(scoped.session_id.as_deref(), Some("agent-123"));
    }

    #[test]
    fn parse_send_args_rejects_missing_unknown_and_extra_targets() {
        assert!(parse_send_args(&strings(&["--json"])).is_err());
        assert!(
            parse_send_args(&strings(&["video.mp4", "--foo"]))
                .unwrap_err()
                .contains("unknown send option")
        );
        assert!(
            parse_send_args(&strings(&["one.mp4", "two.mp4"]))
                .unwrap_err()
                .contains("single media file")
        );
    }

    #[tokio::test]
    async fn removed_no_tui_option_is_rejected() {
        let error = run(strings(&["--no-tui"])).await.unwrap_err();
        assert!(error.message.contains("has been removed"));
        assert!(error.show_usage);
    }

    #[tokio::test]
    async fn json_send_without_target_skips_usage() {
        let error = run(strings(&["send", "--json"])).await.unwrap_err();
        assert!(!error.show_usage);
        assert!(error.message.is_empty());
    }

    #[tokio::test]
    async fn send_without_json_does_not_silently_enter_machine_mode() {
        let error = run(strings(&["send", "https://example.test/video.mp4"]))
            .await
            .unwrap_err();
        assert!(error.show_usage);
        assert!(error.message.contains("interactive terminal"));
    }
}
