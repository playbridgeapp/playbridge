use serde::{Deserialize, Serialize};
use std::{
    fs,
    path::PathBuf,
};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PreferredDevice {
    pub uuid: String,
    pub name: String,
    pub protocol: String,
    pub address: String,
    pub port: Option<u16>,
    pub location: Option<String>,
}

impl PreferredDevice {
    pub fn config_path() -> Option<PathBuf> {
        let home = std::env::var_os("HOME")
            .or_else(|| std::env::var_os("USERPROFILE"))?;
        let mut path = PathBuf::from(home);
        path.push(".config");
        path.push("playbridge");
        path.push("cast.json");
        Some(path)
    }

    pub fn load() -> Option<Self> {
        let path = Self::config_path()?;
        let data = fs::read_to_string(path).ok()?;
        serde_json::from_str(&data).ok()
    }

    pub fn save(&self) -> Result<(), String> {
        let path = Self::config_path().ok_or("Could not determine home directory")?;
        if let Some(parent) = path.parent() {
            let _ = fs::create_dir_all(parent);
        }
        let json = serde_json::to_string_pretty(self).map_err(|e| e.to_string())?;
        fs::write(&path, json).map_err(|e| e.to_string())?;
        Ok(())
    }

    pub fn clear() -> Result<(), String> {
        if let Some(path) = Self::config_path() {
            if path.exists() {
                fs::remove_file(path).map_err(|e| e.to_string())?;
            }
        }
        Ok(())
    }
}
