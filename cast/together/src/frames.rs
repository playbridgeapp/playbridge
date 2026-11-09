//! Relay wire frames (unencrypted envelope). Mirrors `together/shared/src/index.ts`.

use serde::{Deserialize, Serialize};

use crate::ids::MemberId;

pub const PROTOCOL_VERSION: u32 = 1;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "t", rename_all = "snake_case")]
pub enum ClientFrame {
    Ping {
        id: u64,
        t0: f64,
    },
    Msg {
        #[serde(skip_serializing_if = "Option::is_none")]
        to: Option<MemberId>,
        seq: u64,
        ct: String,
    },
    State {
        seq: u64,
        ct: String,
    },
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct StateFrame {
    pub from: MemberId,
    pub seq: u64,
    pub ct: String,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "t", rename_all = "snake_case")]
pub enum ServerFrame {
    Welcome {
        member: MemberId,
        members: Vec<MemberId>,
        room_time: f64,
        expires_at: f64,
        max_members: u32,
        #[serde(default)]
        state: Option<StateFrame>,
    },
    Pong {
        id: u64,
        t0: f64,
        room_time: f64,
    },
    Msg {
        from: MemberId,
        seq: u64,
        ct: String,
    },
    State {
        from: MemberId,
        seq: u64,
        ct: String,
    },
    Joined {
        member: MemberId,
    },
    Left {
        member: MemberId,
    },
    Error {
        code: String,
    },
}

/// Close codes the room uses (see `Close` in the Workers).
pub mod close {
    pub const UNAUTHORIZED: u16 = 4001;
    pub const ROOM_FULL: u16 = 4003;
    pub const REPLACED: u16 = 4004;
    pub const EXPIRED: u16 = 4008;
    pub const RATE_LIMITED: u16 = 4029;
    pub const BAD_MESSAGE: u16 = 4400;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frames_match_the_worker_shapes() {
        let to = MemberId::from_bytes([1; 16]);
        let json = serde_json::to_string(&ClientFrame::Msg {
            to: Some(to),
            seq: 3,
            ct: "x".into(),
        })
        .unwrap();
        assert_eq!(
            json,
            format!(r#"{{"t":"msg","to":"{to}","seq":3,"ct":"x"}}"#)
        );
        let json = serde_json::to_string(&ClientFrame::Msg {
            to: None,
            seq: 3,
            ct: "x".into(),
        })
        .unwrap();
        assert_eq!(json, r#"{"t":"msg","seq":3,"ct":"x"}"#);
        let welcome: ServerFrame = serde_json::from_str(&format!(
            r#"{{"t":"welcome","member":"{to}","members":["{to}"],"room_time":5,"expires_at":9,"max_members":4}}"#
        ))
        .unwrap();
        assert!(matches!(welcome, ServerFrame::Welcome { state: None, .. }));
    }
}
