use clap::Parser;
use std::env;

const FORBIDDEN_PASSWORDS: &[&str] = &["CHANGEME", "playbridge_token"];

#[derive(Parser, Debug, Clone)]
#[command(
    name = "pb-proxy-rust",
    about = "PlayBridge Stream Proxy Server in Rust"
)]
pub struct Config {
    #[arg(short = 'p', long, default_value = "8888", env = "PORT")]
    pub port: u16,

    #[arg(short = 'a', long, default_value = "0.0.0.0", env = "ADDRESS")]
    pub address: String,

    #[arg(short = 'k', long, env = "PB_PROXY_PASSWORD")]
    pub password: Option<String>,

    #[arg(short = 'f', long = "ffmpeg-path", env = "FFMPEG_PATH")]
    pub ffmpeg_path: Option<String>,
}

impl Config {
    pub fn get_validated_password(&self) -> Result<String, String> {
        let password = self
            .password
            .clone()
            .or_else(|| env::var("PB_PROXY_PASSWORD").ok())
            .unwrap_or_default();

        let trimmed = password.trim();
        if trimmed.is_empty() {
            return Err(
                "A non-empty API password is required. Provide one with --password <password> or set PB_PROXY_PASSWORD=<password> in the environment.".to_string(),
            );
        }
        if FORBIDDEN_PASSWORDS.contains(&trimmed) {
            return Err(
                "The default Docker Compose password is not allowed; set a unique password"
                    .to_string(),
            );
        }
        Ok(trimmed.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_config(password: Option<&str>) -> Config {
        Config {
            port: 8888,
            address: "0.0.0.0".to_string(),
            password: password.map(str::to_string),
            ffmpeg_path: None,
        }
    }

    #[test]
    fn test_valid_password() {
        let config = test_config(Some("my-custom-pass-123"));
        assert_eq!(
            config.get_validated_password().unwrap(),
            "my-custom-pass-123"
        );
    }

    #[test]
    fn test_trimmed_valid_password() {
        let config = test_config(Some("  my-custom-pass-123  "));
        assert_eq!(
            config.get_validated_password().unwrap(),
            "my-custom-pass-123"
        );
    }

    #[test]
    fn test_rejects_empty_or_whitespace_password() {
        let config = test_config(Some("   "));
        assert!(config.get_validated_password().is_err());
    }

    #[test]
    fn test_rejects_default_docker_passwords() {
        for forbidden in ["CHANGEME", "playbridge_token", "  playbridge_token  "] {
            let config = test_config(Some(forbidden));
            let err = config.get_validated_password().unwrap_err();
            assert!(
                err.contains("default Docker Compose password is not allowed"),
                "Expected rejection for '{forbidden}', got: {err}"
            );
        }
    }
}
