use serde::{Deserialize, Serialize};
use std::{fs, path::PathBuf};

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
        path.push(format!("{uuid}.json"));
        Some(path)
    }

    pub fn load(uuid: &str) -> Option<Self> {
        let path = Self::path_for(uuid)?;
        let data = fs::read_to_string(path).ok()?;
        serde_json::from_str(&data).ok()
    }

    pub fn save(&self, uuid: &str) -> Result<(), String> {
        let path =
            Self::path_for(uuid).ok_or_else(|| "Could not determine home directory".to_string())?;
        if let Some(parent) = path.parent() {
            let _ = fs::create_dir_all(parent);
        }
        let json = serde_json::to_string_pretty(self).map_err(|e| e.to_string())?;
        fs::write(&path, json).map_err(|e| e.to_string())?;
        Ok(())
    }
}
