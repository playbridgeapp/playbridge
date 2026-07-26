//! Wire types shared by PlayBridge senders and the lightweight web-browser
//! receiver. The browser connects to a sender-hosted WebSocket, so these frames
//! are intentionally separate from the native receiver WSS protocol.

use serde::{Deserialize, Serialize};

pub const BROWSER_PROTOCOL_VERSION: u16 = 1;
pub const BROWSER_WEBSOCKET_PATH: &str = "/v1/browser/ws";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct BrowserMedia {
    pub url: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub content_type: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub poster_url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub subtitle_url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub start_position_ms: Option<u64>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "snake_case")]
pub enum BrowserPlaybackState {
    Idle,
    Buffering,
    Playing,
    Paused,
    Stopped,
    Ended,
    Error,
    AutoplayBlocked,
    #[default]
    Unknown,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct BrowserCapabilities {
    #[serde(default)]
    pub native_hls: bool,
    #[serde(default)]
    pub media_source: bool,
    #[serde(default)]
    pub hls_js: bool,
    #[serde(default)]
    pub dash_js: bool,
    #[serde(default)]
    pub web_vtt: bool,
    #[serde(default)]
    pub volume_control: bool,
    #[serde(default)]
    pub mime_types: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum HostToBrowserFrame {
    PairingRequired {
        #[serde(rename = "sessionId")]
        session_id: String,
        code: String,
        #[serde(rename = "expiresInMs")]
        expires_in_ms: u64,
    },
    PairingApproved {
        #[serde(rename = "sessionId")]
        session_id: String,
    },
    PairingDenied {
        #[serde(rename = "sessionId")]
        session_id: String,
        reason: String,
    },
    Disconnect {
        reason: String,
    },
    Load {
        #[serde(rename = "requestId")]
        request_id: String,
        media: BrowserMedia,
    },
    Command {
        #[serde(rename = "requestId")]
        request_id: String,
        action: BrowserCommand,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        value: Option<f64>,
    },
    Ping {
        #[serde(rename = "requestId")]
        request_id: String,
    },
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum BrowserCommand {
    Play,
    Pause,
    Stop,
    Seek,
    SetVolume,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum BrowserToHostFrame {
    Hello {
        #[serde(rename = "protocolVersion")]
        protocol_version: u16,
        #[serde(rename = "receiverId")]
        receiver_id: String,
        name: String,
        #[serde(default, skip_serializing_if = "Option::is_none", rename = "sessionId")]
        session_id: Option<String>,
    },
    Capabilities {
        capabilities: BrowserCapabilities,
    },
    Ready {
        #[serde(rename = "requestId")]
        request_id: String,
    },
    Status {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        #[serde(rename = "requestId")]
        request_id: Option<String>,
        state: BrowserPlaybackState,
        #[serde(rename = "positionMs")]
        position_ms: u64,
        #[serde(rename = "durationMs")]
        duration_ms: u64,
        volume: f64,
        muted: bool,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        title: Option<String>,
    },
    Ended,
    Error {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        #[serde(rename = "requestId")]
        request_id: Option<String>,
        message: String,
    },
    Pong {
        #[serde(rename = "requestId")]
        request_id: String,
    },
}

#[cfg(test)]
mod tests {
    use super::{
        BROWSER_PROTOCOL_VERSION, BrowserCommand, BrowserMedia, BrowserPlaybackState,
        BrowserToHostFrame, HostToBrowserFrame,
    };

    #[test]
    fn browser_frames_use_stable_tag_and_camel_case_fields() {
        let frame = HostToBrowserFrame::Load {
            request_id: "7".into(),
            media: BrowserMedia {
                url: "http://sender/media/token/video.mp4".into(),
                title: Some("Example".into()),
                content_type: Some("video/mp4".into()),
                poster_url: None,
                subtitle_url: None,
                start_position_ms: Some(1_500),
            },
        };
        let json = serde_json::to_value(frame).unwrap();
        assert_eq!(json["type"], "load");
        assert_eq!(json["requestId"], "7");
        assert_eq!(json["media"]["startPositionMs"], 1_500);

        let command = serde_json::to_value(HostToBrowserFrame::Command {
            request_id: "8".into(),
            action: BrowserCommand::SetVolume,
            value: Some(0.5),
        })
        .unwrap();
        assert_eq!(command["action"], "set_volume");
    }

    #[test]
    fn browser_status_tolerates_future_fields() {
        let frame: BrowserToHostFrame = serde_json::from_str(
            r#"{"type":"status","requestId":"9","state":"playing","positionMs":1000,"durationMs":9000,"volume":0.5,"muted":false,"future":true}"#,
        )
        .unwrap();
        assert_eq!(
            frame,
            BrowserToHostFrame::Status {
                request_id: Some("9".into()),
                state: BrowserPlaybackState::Playing,
                position_ms: 1_000,
                duration_ms: 9_000,
                volume: 0.5,
                muted: false,
                title: None,
            }
        );
    }

    #[test]
    fn hello_carries_protocol_version() {
        let frame = BrowserToHostFrame::Hello {
            protocol_version: BROWSER_PROTOCOL_VERSION,
            receiver_id: "browser-1".into(),
            name: "Living Room Browser".into(),
            session_id: None,
        };
        assert_eq!(
            serde_json::to_value(frame).unwrap()["protocolVersion"],
            BROWSER_PROTOCOL_VERSION
        );
    }
}
