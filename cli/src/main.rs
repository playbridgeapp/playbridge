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
mod preferred;
mod receive;
mod send;
mod ui;
mod update;
mod update_installer;

use google_cast::run_google_cast;

#[tokio::main]
async fn main() -> ExitCode {
    let arguments = env::args().skip(1).collect::<Vec<_>>();
    match run(arguments).await {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("error: {message}");
            eprintln!();
            eprintln!("{}", usage());
            ExitCode::from(2)
        }
    }
}

async fn run(arguments: Vec<String>) -> Result<(), String> {
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
            let Some(target) = arguments.get(1) else {
                return Err("missing media file or URL to send".into());
            };
            send::validate_media_target(target)?;
            run_dashboard(
                globals.theme.as_deref(),
                ui::DashboardLaunch::Cast {
                    source: Some(target.clone()),
                    browser: false,
                },
            )
            .await
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
                discover(args).await
            }
        }
        "google-cast" | "googlecast" => run_google_cast(&arguments[1..]).await,
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
            _ => Err("expected: playbridge config <path|check>".into()),
        },
        target => {
            // Default to sending the media target directly
            send::validate_media_target(target)?;
            run_dashboard(
                globals.theme.as_deref(),
                ui::DashboardLaunch::Cast {
                    source: Some(target.to_owned()),
                    browser: false,
                },
            )
            .await
        }
    }
}

async fn run_dashboard(
    theme_override: Option<&str>,
    launch: ui::DashboardLaunch,
) -> Result<(), String> {
    if !ui::dashboard_available() {
        return Err(
            "the PlayBridge dashboard requires an interactive terminal; use `discover --json` or `discover --json-lines` for machine-readable discovery"
                .into(),
        );
    }
    ui::run_dashboard(theme_override, launch).await
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
  discover --json                   Print one final discovery report
  discover --json-lines             Stream discovery events
  google-cast status [options]      Query Google Cast status without launching
  google-cast launch [options]      Launch or join a Google Cast receiver
  config <path|check>               Locate or validate UI configuration

Global Options:
      --theme <name>               Override the configured UI theme
  -V, --version                    Print the CLI version
  -h, --help                       Show this help

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
        assert!(help.contains("Interactive workflows require a terminal"));
    }

    #[tokio::test]
    async fn removed_no_tui_option_is_rejected() {
        let error = run(strings(&["--no-tui"])).await.unwrap_err();
        assert!(error.contains("has been removed"));
    }
}
