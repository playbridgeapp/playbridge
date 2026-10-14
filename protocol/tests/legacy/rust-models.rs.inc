#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type")]
pub enum ReceiverFrame {
    #[serde(rename = "pong")]
    Pong,
    #[serde(rename = "pairing_challenge", rename_all = "camelCase")]
    PairingChallenge { tv_eph_pub: String, nonce_t: String },
    #[serde(rename = "pairing_approved")]
    PairingApproved { nonce: String, ciphertext: String },
    #[serde(rename = "pairing_denied")]
    PairingDenied,
    #[serde(rename = "auth_response", rename_all = "camelCase")]
    AuthResponse {
        success: bool,
        #[serde(default)]
        cert_fingerprint: Option<String>,
        #[serde(default)]
        players: Vec<String>,
        #[serde(default)]
        browsers: Vec<String>,
        #[serde(default)]
        media_kinds: Vec<String>,
        #[serde(default)]
        screen_mirror_web_rtc: bool,
        #[serde(default)]
        features: Vec<String>,
    },
    #[serde(rename = "status")]
    Status {
        #[serde(default)]
        state: String,
        #[serde(default)]
        position: u64,
        #[serde(default)]
        duration: u64,
        #[serde(default)]
        title: Option<String>,
        #[serde(default)]
        media_kind: Option<String>,
        #[serde(default)]
        playback_id: Option<String>,
        #[serde(default)]
        current_item_id: Option<String>,
    },
    #[serde(rename = "playlist_status", rename_all = "camelCase")]
    PlaylistStatus {
        #[serde(default)]
        items: Vec<PlaylistStatusItem>,
        #[serde(default)]
        current_index: usize,
        #[serde(default)]
        total_count: usize,
        #[serde(default)]
        playback_id: Option<String>,
        #[serde(default)]
        queue_revision: u64,
        #[serde(default)]
        current_item_id: Option<String>,
    },
    #[serde(rename = "command_result", rename_all = "camelCase")]
    CommandResult {
        request_id: String,
        ok: bool,
        #[serde(default)]
        error: Option<String>,
        #[serde(default)]
        message: Option<String>,
        #[serde(default)]
        playback_id: Option<String>,
        #[serde(default)]
        queue_revision: Option<u64>,
    },
    #[serde(other)]
    Unknown,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistStatusItem {
    #[serde(default)]
    pub index: usize,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub item_id: Option<String>,
    #[serde(default)]
    pub season: Option<u32>,
    #[serde(default)]
    pub episode: Option<u32>,
    #[serde(default)]
    pub imdb_id: Option<String>,
    #[serde(default)]
    pub tmdb_id: Option<String>,
    #[serde(default)]
    pub binge_group: Option<String>,
    #[serde(default)]
    pub media_kind: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct CredentialBundle {
    pub token: String,
    #[serde(default)]
    pub cert_fingerprint: Option<String>,
    #[serde(default)]
    pub players: Vec<String>,
    #[serde(default)]
    pub browsers: Vec<String>,
    #[serde(default)]
    pub media_kinds: Vec<String>,
    #[serde(default)]
    pub screen_mirror_web_rtc: bool,
    #[serde(default)]
    pub features: Vec<String>,
}

