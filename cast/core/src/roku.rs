use std::time::Duration;

use futures_util::StreamExt;
use reqwest::{Client, StatusCode, Url};

use crate::{CastError, Result};

pub const DEFAULT_ECP_PORT: u16 = 8060;
pub const PLAY_ON_ROKU_APP_ID: &str = "15985";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RokuStatus {
    pub state: String,
    pub position_ms: u64,
    pub duration_ms: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RokuApp {
    pub id: String,
    pub name: String,
}

#[derive(Debug, Clone)]
pub struct RokuClient {
    http: Client,
    base_url: Url,
}

impl RokuClient {
    pub fn new(address: &str, port: u16, timeout: Duration) -> Result<Self> {
        let base_url = Url::parse(&format!(
            "http://{}:{port}/",
            crate::net::host_for_url(address)
        ))?;
        let http = Client::builder().timeout(timeout).build()?;
        Ok(Self { http, base_url })
    }

    pub async fn launch_media(&self, media_url: &str, title: Option<&str>) -> Result<()> {
        let mut url = self
            .base_url
            .join(&format!("launch/{PLAY_ON_ROKU_APP_ID}"))?;
        url.query_pairs_mut()
            .append_pair("contentID", media_url)
            .append_pair("u", media_url)
            .append_pair("mediaType", "movie")
            .append_pair("videoFormat", video_format(media_url));
        if let Some(title) = title {
            url.query_pairs_mut()
                .append_pair("title", title)
                .append_pair("t", title);
        }
        self.expect_success(self.http.post(url).send().await?, "Roku media launch")
            .await
    }

    pub async fn keypress(&self, key: &str) -> Result<()> {
        if !key
            .chars()
            .all(|character| character.is_ascii_alphanumeric())
        {
            return Err(CastError::Protocol("invalid Roku keypress".into()));
        }
        let url = self.base_url.join(&format!("keypress/{key}"))?;
        self.expect_success(self.http.post(url).send().await?, "Roku keypress")
            .await
    }

    pub async fn status(&self) -> Result<RokuStatus> {
        let url = self.base_url.join("query/media-player")?;
        let response = self.http.get(url).send().await?;
        let status = response.status();
        if !status.is_success() {
            return Err(CastError::ReceiverHttp {
                operation: "Roku media status",
                status,
            });
        }
        parse_status(&response_text_limited(response).await?)
    }

    pub async fn device_name(&self) -> Result<String> {
        let url = self.base_url.join("query/device-info")?;
        let response = self.http.get(url).send().await?;
        let status = response.status();
        if !status.is_success() {
            return Err(CastError::ReceiverHttp {
                operation: "Roku device info",
                status,
            });
        }
        parse_device_name(&response_text_limited(response).await?)
    }

    pub async fn apps(&self) -> Result<Vec<RokuApp>> {
        let url = self.base_url.join("query/apps")?;
        let response = self.http.get(url).send().await?;
        let status = response.status();
        if !status.is_success() {
            return Err(CastError::ReceiverHttp {
                operation: "Roku installed apps",
                status,
            });
        }
        parse_apps(&response_text_limited(response).await?)
    }

    pub async fn has_play_on_roku(&self) -> Result<bool> {
        Ok(self
            .apps()
            .await?
            .iter()
            .any(|app| app.id == PLAY_ON_ROKU_APP_ID))
    }

    async fn expect_success(
        &self,
        response: reqwest::Response,
        operation: &'static str,
    ) -> Result<()> {
        let status = response.status();
        if status == StatusCode::OK
            || status == StatusCode::ACCEPTED
            || status == StatusCode::NO_CONTENT
        {
            Ok(())
        } else {
            Err(CastError::ReceiverHttp { operation, status })
        }
    }
}

async fn response_text_limited(response: reqwest::Response) -> Result<String> {
    const MAX_XML_BYTES: usize = 256 * 1024;
    if response
        .content_length()
        .is_some_and(|length| length > MAX_XML_BYTES as u64)
    {
        return Err(CastError::Protocol("Roku XML response is too large".into()));
    }
    let mut bytes = Vec::new();
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk?;
        if bytes.len().saturating_add(chunk.len()) > MAX_XML_BYTES {
            return Err(CastError::Protocol("Roku XML response is too large".into()));
        }
        bytes.extend_from_slice(&chunk);
    }
    String::from_utf8(bytes)
        .map_err(|error| CastError::Protocol(format!("invalid Roku XML encoding: {error}")))
}

fn parse_status(xml: &str) -> Result<RokuStatus> {
    let document = roxmltree::Document::parse(xml)
        .map_err(|error| CastError::Protocol(format!("invalid Roku status XML: {error}")))?;
    let root = document.root_element();
    let state = root.attribute("state").unwrap_or("none").to_owned();
    let value = |name: &str| {
        root.descendants()
            .find(|node| node.has_tag_name(name))
            .and_then(|node| node.text())
            .and_then(parse_roku_time_ms)
            .unwrap_or(0)
    };
    Ok(RokuStatus {
        state,
        position_ms: value("position"),
        duration_ms: value("duration"),
    })
}

fn parse_device_name(xml: &str) -> Result<String> {
    let document = roxmltree::Document::parse(xml)
        .map_err(|error| CastError::Protocol(format!("invalid Roku device XML: {error}")))?;
    for name in ["user-device-name", "friendly-device-name", "model-name"] {
        if let Some(value) = document
            .descendants()
            .find(|node| node.has_tag_name(name))
            .and_then(|node| node.text())
            .map(str::trim)
            .filter(|value| !value.is_empty())
        {
            return Ok(value.to_owned());
        }
    }
    Err(CastError::MissingField("Roku device name"))
}

fn parse_apps(xml: &str) -> Result<Vec<RokuApp>> {
    let document = roxmltree::Document::parse(xml)
        .map_err(|error| CastError::Protocol(format!("invalid Roku apps XML: {error}")))?;
    Ok(document
        .descendants()
        .filter(|node| node.has_tag_name("app"))
        .filter_map(|node| {
            let id = node.attribute("id")?.trim();
            if id.is_empty() {
                return None;
            }
            Some(RokuApp {
                id: id.to_owned(),
                name: node.text().unwrap_or("").trim().to_owned(),
            })
        })
        .collect())
}

fn parse_roku_time_ms(value: &str) -> Option<u64> {
    let trimmed = value.trim();
    let number = trimmed.split_whitespace().next()?.trim_end_matches("ms");
    let parsed = number.parse::<u64>().ok()?;
    if trimmed.contains("ms") {
        Some(parsed)
    } else {
        Some(parsed.saturating_mul(1000))
    }
}

fn video_format(url: &str) -> &'static str {
    let path = url
        .split(['?', '#'])
        .next()
        .unwrap_or(url)
        .to_ascii_lowercase();
    if path.ends_with(".m3u8") {
        "hls"
    } else if path.ends_with(".mkv") {
        "mkv"
    } else {
        "mp4"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_ipv6_base_urls() {
        let global = RokuClient::new("2001:db8::1", DEFAULT_ECP_PORT, Duration::from_secs(1));
        assert_eq!(
            global.unwrap().base_url.as_str(),
            "http://[2001:db8::1]:8060/"
        );
    }

    #[test]
    fn parses_roku_player_status_units() {
        let status = parse_status(
            r#"<player state="play"><position>450 s</position><duration>1800000 ms</duration></player>"#,
        )
        .unwrap();
        assert_eq!(status.state, "play");
        assert_eq!(status.position_ms, 450_000);
        assert_eq!(status.duration_ms, 1_800_000);
    }

    #[test]
    fn parses_roku_friendly_name() {
        assert_eq!(
            parse_device_name(
                "<device-info><user-device-name>Living Room Roku</user-device-name></device-info>"
            )
            .unwrap(),
            "Living Room Roku"
        );
    }

    #[test]
    fn parses_roku_apps_and_receiver_capability() {
        let apps = parse_apps(
            r#"<apps><app id="12">Netflix</app><app id="15985" version="2.0">Play on Roku</app></apps>"#,
        )
        .unwrap();
        assert_eq!(apps.len(), 2);
        assert_eq!(apps[1].id, PLAY_ON_ROKU_APP_ID);
        assert_eq!(apps[1].name, "Play on Roku");
    }
}
