use serde::{Deserialize, Serialize};
use std::{
    fs::{self, OpenOptions},
    io::Write,
    path::PathBuf,
};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlaybridgeCredentials {
    pub token: String,
    pub cert_fingerprint: String,
    pub players: Vec<String>,
    pub browsers: Vec<String>,
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
    use super::{safe_identifier, write_private_file};

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
}
