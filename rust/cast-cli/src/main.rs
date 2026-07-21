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
mod http_server;
mod preferred;
mod send;

use preferred::PreferredDevice;
use send::run_send;

#[tokio::main]
async fn main() -> ExitCode {
    match run(env::args().skip(1).collect()).await {
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
    if arguments
        .first()
        .is_some_and(|value| value == "--help" || value == "-h")
    {
        println!("{}", usage());
        return Ok(());
    }
    let Some(command) = arguments.first() else {
        return Err("missing command or media target. Usage: playbridge <filename|URL> or playbridge [send|cast|discover|preferred]".into());
    };

    match command.as_str() {
        "send" | "cast" => {
            let Some(target) = arguments.get(1) else {
                return Err("missing media file or URL to send".into());
            };
            run_send(target.clone()).await
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
            discover(args).await
        }
        "preferred" => {
            if let Some(sub) = arguments.get(1)
                && sub == "clear"
            {
                PreferredDevice::clear()?;
                println!("Cleared preferred device configuration.");
                return Ok(());
            }
            if let Some(pref) = PreferredDevice::load() {
                println!("Preferred Device Configuration:");
                println!("  Name:     {}", pref.name);
                println!("  Protocol: {}", pref.protocol);
                println!("  Address:  {}", pref.address);
                if let Some(port) = pref.port {
                    println!("  Port:     {}", port);
                }
                println!("  UUID:     {}", pref.uuid);
            } else {
                println!("No preferred device configured.");
            }
            Ok(())
        }
        target => {
            // Default to sending the media target directly
            run_send(target.to_string()).await
        }
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
  playbridge <filename|URL>              Interactively cast a file/URL with auto-send
  playbridge send <filename|URL>         Explicit send command
  playbridge cast <filename|URL>         Explicit cast command
  playbridge discover [options]          Discover receivers on your local network
  playbridge preferred [clear]           View or clear the saved preferred device

Discover Options:
  -p, --protocol <names>  playbridge, native, dlna, roku, dial, googlecast, or all
                         Repeat the option or use comma-separated names
  -t, --timeout <seconds> Bounded scan duration (1-300, default 5)
      --json              Print one final JSON report
      --json-lines        Stream one JSON event per line
  -h, --help              Show this help"#
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
}
