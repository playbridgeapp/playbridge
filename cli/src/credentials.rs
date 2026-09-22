use serde::{Deserialize, Serialize};
use std::{
    fs::{self, OpenOptions},
    io::Write,
    path::PathBuf,
    time::{SystemTime, UNIX_EPOCH},
};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlaybridgeCredentials {
    pub token: String,
    pub cert_fingerprint: String,
    pub players: Vec<String>,
    pub browsers: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub receiver_name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_used_at: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SenderIdentity {
    pub uuid: String,
    pub name: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct PairedReceiver {
    pub uuid: String,
    pub name: Option<String>,
    pub last_used_at: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

impl PlaybridgeCredentials {
    fn cred_dir() -> Option<PathBuf> {
        let home = std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE"))?;
        let mut path = PathBuf::from(home);
        path.push(".config");
        path.push("playbridge");
        path.push("credentials");
        Some(path)
    }

    pub fn path_for(uuid: &str) -> Option<PathBuf> {
        let mut path = Self::cred_dir()?;
        path.push(format!("{}.json", safe_identifier(uuid)));
        Some(path)
    }

    pub fn load(uuid: &str) -> Option<Self> {
        let path = Self::path_for(uuid)?;
        let _ = set_private_file_permissions(&path);
        if let Some(parent) = path.parent() {
            let _ = set_private_dir_permissions(parent);
        }
        let data = fs::read_to_string(path).ok()?;
        serde_json::from_str(&data).ok()
    }

    pub fn save(&self, uuid: &str) -> Result<(), String> {
        let path =
            Self::path_for(uuid).ok_or_else(|| "Could not determine home directory".to_string())?;
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
            set_private_dir_permissions(parent).map_err(|e| e.to_string())?;
        }
        let json = serde_json::to_string_pretty(self).map_err(|e| e.to_string())?;
        write_private_file(&path, json.as_bytes()).map_err(|e| e.to_string())?;
        Ok(())
    }

    pub fn list() -> Result<Vec<PairedReceiver>, String> {
        let Some(directory) = Self::cred_dir() else {
            return Err("Could not determine home directory".into());
        };
        Self::list_from(&directory)
    }

    fn list_from(directory: &std::path::Path) -> Result<Vec<PairedReceiver>, String> {
        let entries = match fs::read_dir(directory) {
            Ok(entries) => entries,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(error) => return Err(format!("Could not read credential directory: {error}")),
        };
        let mut paired = Vec::new();
        for entry in entries {
            let entry =
                entry.map_err(|error| format!("Could not read credential entry: {error}"))?;
            let path = entry.path();
            if path.extension().and_then(|value| value.to_str()) != Some("json") {
                continue;
            }
            let Some(uuid) = path.file_stem().and_then(|value| value.to_str()) else {
                continue;
            };
            match fs::read_to_string(&path)
                .map_err(|_| "credential_unreadable")
                .and_then(|data| {
                    serde_json::from_str::<Self>(&data).map_err(|_| "credential_invalid")
                }) {
                Ok(credentials) => paired.push(PairedReceiver {
                    uuid: uuid.to_owned(),
                    name: credentials.receiver_name,
                    last_used_at: credentials.last_used_at,
                    error: None,
                }),
                Err(error) => paired.push(PairedReceiver {
                    uuid: uuid.to_owned(),
                    name: None,
                    last_used_at: None,
                    error: Some(error.into()),
                }),
            }
        }
        paired.sort_by(|left, right| left.uuid.cmp(&right.uuid));
        Ok(paired)
    }

    pub fn forget(selector: &str) -> Result<PairedReceiver, String> {
        let directory = Self::cred_dir().ok_or("Could not determine home directory")?;
        Self::forget_from(&directory, selector)
    }

    fn forget_from(directory: &std::path::Path, selector: &str) -> Result<PairedReceiver, String> {
        let paired = Self::list_from(directory)?;
        let selector = selector
            .strip_prefix("playbridge:")
            .unwrap_or(selector)
            .trim();
        let exact = paired
            .iter()
            .filter(|item| item.uuid.eq_ignore_ascii_case(selector))
            .cloned()
            .collect::<Vec<_>>();
        let matches = if exact.is_empty() {
            paired
                .iter()
                .filter(|item| {
                    item.name
                        .as_deref()
                        .is_some_and(|name| name.eq_ignore_ascii_case(selector))
                })
                .cloned()
                .collect::<Vec<_>>()
        } else {
            exact
        };
        let matched = match matches.as_slice() {
            [] => return Err("paired_device_not_found".into()),
            [matched] => matched.clone(),
            _ => return Err("ambiguous_device".into()),
        };
        let path = directory.join(format!("{}.json", safe_identifier(&matched.uuid)));
        fs::remove_file(path).map_err(|error| error.to_string())?;
        Ok(matched)
    }

    pub fn forget_all() -> Result<Vec<PairedReceiver>, String> {
        let directory = Self::cred_dir().ok_or("Could not determine home directory")?;
        Self::forget_all_from(&directory)
    }

    fn forget_all_from(directory: &std::path::Path) -> Result<Vec<PairedReceiver>, String> {
        let paired = Self::list_from(directory)?;
        for item in &paired {
            let path = directory.join(format!("{}.json", safe_identifier(&item.uuid)));
            if path.exists() {
                fs::remove_file(path).map_err(|error| error.to_string())?;
            }
        }
        Ok(paired)
    }
}

impl SenderIdentity {
    pub fn load_or_create() -> Result<Self, String> {
        let home = std::env::var_os("HOME")
            .or_else(|| std::env::var_os("USERPROFILE"))
            .ok_or("Could not determine home directory")?;
        let path = PathBuf::from(home).join(".config/playbridge/sender.json");
        Self::load_or_create_at(&path)
    }

    fn load_or_create_at(path: &std::path::Path) -> Result<Self, String> {
        match fs::read_to_string(path) {
            Ok(data) => return Self::parse_existing(&data),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(format!("sender_identity_unreadable: {error}")),
        }
        let mut random = [0_u8; 16];
        getrandom::fill(&mut random).map_err(|error| error.to_string())?;
        // RFC 4122 version 4 / variant 1 bits.
        random[6] = (random[6] & 0x0f) | 0x40;
        random[8] = (random[8] & 0x3f) | 0x80;
        let hex = random
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>();
        let uuid = format!(
            "{}-{}-{}-{}-{}",
            &hex[0..8],
            &hex[8..12],
            &hex[12..16],
            &hex[16..20],
            &hex[20..32]
        );
        let host = std::env::var("HOSTNAME")
            .or_else(|_| std::env::var("COMPUTERNAME"))
            .unwrap_or_default();
        let host = normalized_hostname(&host);
        let identity = Self {
            uuid,
            name: if host.is_empty() {
                "PlayBridge CLI".into()
            } else {
                format!("PlayBridge CLI — {host}")
            },
        };
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|error| error.to_string())?;
            set_private_dir_permissions(parent).map_err(|error| error.to_string())?;
        }
        let json = serde_json::to_vec_pretty(&identity).map_err(|error| error.to_string())?;
        match write_private_file_new(path, &json) {
            Ok(()) => Ok(identity),
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
                for _ in 0..10 {
                    if let Ok(data) = fs::read_to_string(path)
                        && let Ok(existing) = Self::parse_existing(&data)
                    {
                        return Ok(existing);
                    }
                    std::thread::sleep(std::time::Duration::from_millis(10));
                }
                let data = fs::read_to_string(path)
                    .map_err(|error| format!("sender_identity_unreadable: {error}"))?;
                Self::parse_existing(&data)
            }
            Err(error) => Err(error.to_string()),
        }
    }

    fn parse_existing(data: &str) -> Result<Self, String> {
        let identity =
            serde_json::from_str::<Self>(data).map_err(|_| "sender_identity_invalid".to_owned())?;
        if !is_uuid(&identity.uuid) || identity.name.trim().is_empty() || identity.name.len() > 128
        {
            return Err("sender_identity_invalid".into());
        }
        Ok(identity)
    }
}

fn is_uuid(value: &str) -> bool {
    value.len() == 36
        && value.chars().enumerate().all(|(index, character)| {
            if matches!(index, 8 | 13 | 18 | 23) {
                character == '-'
            } else {
                character.is_ascii_hexdigit()
            }
        })
}

fn normalized_hostname(value: &str) -> String {
    let trimmed = value.trim();
    let without_local = trimmed
        .get(..trimmed.len().saturating_sub(6))
        .filter(|_| trimmed.to_ascii_lowercase().ends_with(".local"))
        .unwrap_or(trimmed);
    let mut normalized = String::new();
    let mut pending_space = false;
    for character in without_local.chars() {
        if character.is_whitespace() {
            pending_space = !normalized.is_empty();
            continue;
        }
        if character.is_control() {
            continue;
        }
        if pending_space {
            normalized.push(' ');
            pending_space = false;
        }
        if normalized.chars().count() >= 64 {
            break;
        }
        normalized.push(character);
    }
    normalized.trim().to_owned()
}

pub fn now_seconds() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|value| value.as_secs())
        .unwrap_or(0)
}

fn write_private_file(path: &std::path::Path, contents: &[u8]) -> std::io::Result<()> {
    let mut random = [0_u8; 8];
    getrandom::fill(&mut random).map_err(std::io::Error::other)?;
    let suffix = random
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    let temporary = path.with_extension(format!("json.{suffix}.tmp"));
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
        replace_file(&temporary, path)?;
        set_private_file_permissions(path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

fn write_private_file_new(path: &std::path::Path, contents: &[u8]) -> std::io::Result<()> {
    let mut options = OpenOptions::new();
    options.create_new(true).write(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(path)?;
    if let Err(error) = file.write_all(contents).and_then(|_| file.sync_all()) {
        drop(file);
        let _ = fs::remove_file(path);
        return Err(error);
    }
    set_private_file_permissions(path)
}

#[cfg(not(windows))]
fn replace_file(source: &std::path::Path, destination: &std::path::Path) -> std::io::Result<()> {
    fs::rename(source, destination)
}

#[cfg(windows)]
fn replace_file(source: &std::path::Path, destination: &std::path::Path) -> std::io::Result<()> {
    if destination.exists() {
        fs::remove_file(destination)?;
    }
    fs::rename(source, destination)
}

fn safe_identifier(value: &str) -> String {
    let mut output = String::with_capacity(value.len().min(128));
    for character in value.chars().take(128) {
        if character.is_ascii_alphanumeric() || matches!(character, '-' | '_') {
            output.push(character);
        } else {
            output.push('_');
        }
    }
    if output.is_empty() || output == "." || output == ".." {
        "unknown-device".into()
    } else {
        output
    }
}

#[cfg(unix)]
fn set_private_dir_permissions(path: &std::path::Path) -> std::io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))
}

#[cfg(not(unix))]
fn set_private_dir_permissions(_path: &std::path::Path) -> std::io::Result<()> {
    Ok(())
}

#[cfg(unix)]
fn set_private_file_permissions(path: &std::path::Path) -> std::io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o600))
}

#[cfg(not(unix))]
fn set_private_file_permissions(_path: &std::path::Path) -> std::io::Result<()> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{
        PlaybridgeCredentials, SenderIdentity, normalized_hostname, safe_identifier,
        write_private_file,
    };

    #[test]
    fn credential_identifier_cannot_escape_directory() {
        assert_eq!(
            safe_identifier("../../receiver/name"),
            "______receiver_name"
        );
        assert_eq!(safe_identifier("receiver-123"), "receiver-123");
    }

    #[test]
    fn credential_write_replaces_contents() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("credential.json");
        write_private_file(&path, b"first").unwrap();
        write_private_file(&path, b"second").unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), b"second");

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                std::fs::metadata(path).unwrap().permissions().mode() & 0o777,
                0o600
            );
        }
    }

    #[test]
    fn credential_metadata_is_backward_compatible() {
        let credentials: PlaybridgeCredentials = serde_json::from_str(
            r#"{"token":"secret","cert_fingerprint":"sha256/test","players":[],"browsers":[]}"#,
        )
        .unwrap();
        assert_eq!(credentials.receiver_name, None);
        assert_eq!(credentials.last_used_at, None);
    }

    #[test]
    fn lists_metadata_without_serializing_secrets() {
        let directory = tempfile::tempdir().unwrap();
        let credentials = PlaybridgeCredentials {
            token: "secret-token".into(),
            cert_fingerprint: "sha256/secret-pin".into(),
            players: vec![],
            browsers: vec![],
            receiver_name: Some("Living Room".into()),
            last_used_at: Some(42),
        };
        write_private_file(
            &directory.path().join("receiver-1.json"),
            serde_json::to_string(&credentials).unwrap().as_bytes(),
        )
        .unwrap();

        let listed = PlaybridgeCredentials::list_from(directory.path()).unwrap();
        let output = serde_json::to_string(&listed).unwrap();
        assert_eq!(listed.len(), 1);
        assert!(output.contains("Living Room"));
        assert!(!output.contains("secret-token"));
        assert!(!output.contains("secret-pin"));
    }

    #[test]
    fn sender_identity_is_stable_on_disk() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("sender.json");
        let first = SenderIdentity::load_or_create_at(&path).unwrap();
        let second = SenderIdentity::load_or_create_at(&path).unwrap();
        assert_eq!(first.uuid, second.uuid);
        assert_eq!(first.name, second.name);
        assert_eq!(first.uuid.len(), 36);
    }

    #[test]
    fn malformed_sender_identity_is_not_replaced() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("sender.json");
        std::fs::write(&path, b"not-json").unwrap();

        assert_eq!(
            SenderIdentity::load_or_create_at(&path).unwrap_err(),
            "sender_identity_invalid",
        );
        assert_eq!(std::fs::read(&path).unwrap(), b"not-json");
    }

    #[test]
    fn hostname_is_safe_and_readable_for_sender_names() {
        assert_eq!(
            normalized_hostname("  Atul’s  MacBook.local  "),
            "Atul’s MacBook"
        );
        assert_eq!(normalized_hostname("Media\nServer\0"), "Media Server");
        assert_eq!(normalized_hostname("   "), "");
        assert_eq!(normalized_hostname(&"x".repeat(80)).chars().count(), 64);
    }

    #[test]
    fn forget_accepts_qualified_uuid_and_rejects_ambiguous_names() {
        let directory = tempfile::tempdir().unwrap();
        for uuid in ["receiver-1", "receiver-2"] {
            let credentials = PlaybridgeCredentials {
                token: format!("token-{uuid}"),
                cert_fingerprint: "sha256/pin".into(),
                players: vec![],
                browsers: vec![],
                receiver_name: Some("Living Room".into()),
                last_used_at: None,
            };
            write_private_file(
                &directory.path().join(format!("{uuid}.json")),
                serde_json::to_string(&credentials).unwrap().as_bytes(),
            )
            .unwrap();
        }
        assert_eq!(
            PlaybridgeCredentials::forget_from(directory.path(), "Living Room").unwrap_err(),
            "ambiguous_device",
        );
        let removed =
            PlaybridgeCredentials::forget_from(directory.path(), "playbridge:receiver-1").unwrap();
        assert_eq!(removed.uuid, "receiver-1");
        assert!(!directory.path().join("receiver-1.json").exists());
        assert!(directory.path().join("receiver-2.json").exists());
    }

    #[test]
    fn malformed_credentials_are_reported_and_can_be_forgotten() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("broken-receiver.json");
        std::fs::write(&path, b"not-json").unwrap();

        let listed = PlaybridgeCredentials::list_from(directory.path()).unwrap();
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].uuid, "broken-receiver");
        assert_eq!(listed[0].error.as_deref(), Some("credential_invalid"));

        PlaybridgeCredentials::forget_from(directory.path(), "broken-receiver").unwrap();
        assert!(!path.exists());
    }

    #[test]
    fn forget_all_removes_malformed_credential_files() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("broken-receiver.json");
        std::fs::write(&path, b"not-json").unwrap();

        let removed = PlaybridgeCredentials::forget_all_from(directory.path()).unwrap();
        assert_eq!(removed.len(), 1);
        assert!(!path.exists());
    }
}
