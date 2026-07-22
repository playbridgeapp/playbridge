use clap::Parser;
use std::env;

const DEFAULT_DOCKER_PASSWORD: &str = "CHANGEME";

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
        if trimmed == DEFAULT_DOCKER_PASSWORD {
            return Err(
                "The default Docker Compose password is not allowed; set a unique password"
                    .to_string(),
            );
        }
        Ok(trimmed.to_string())
    }
}
