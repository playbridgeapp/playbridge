use aes_gcm::{
    Aes256Gcm, Nonce,
    aead::{Aead, KeyInit, Payload},
};
use base64::{Engine, engine::general_purpose::STANDARD as BASE64};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::{Zeroize, ZeroizeOnDrop};

use crate::{CastError, Result};

type HmacSha256 = Hmac<Sha256>;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type")]
pub enum SenderFrame {
    #[serde(rename = "ping")]
    Ping,
    #[serde(rename = "auth")]
    Auth { token: String },
    #[serde(rename = "pairing_commit", rename_all = "camelCase")]
    PairingCommit {
        commit: String,
        device_name: String,
        device_uuid: String,
    },
    #[serde(rename = "pairing_reveal", rename_all = "camelCase")]
    PairingReveal {
        sender_eph_pub: String,
        nonce_s: String,
    },
    #[serde(rename = "pairing_confirmation")]
    PairingConfirmation { mac: String },
    #[serde(rename = "command")]
    Command {
        action: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        payload: Option<Value>,
    },
}

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
    },
    #[serde(rename = "status")]
    Status {
        #[serde(default)]
        position: u64,
        #[serde(default)]
        duration: u64,
        #[serde(default)]
        title: Option<String>,
    },
    #[serde(other)]
    Unknown,
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
}

#[derive(Zeroize, ZeroizeOnDrop)]
pub struct PairingSession {
    private_key: [u8; 32],
    public_key: [u8; 32],
    nonce_s: [u8; 16],
    commit: [u8; 32],
    #[zeroize(skip)]
    transcript: Option<Vec<u8>>,
    shared_secret: Option<[u8; 32]>,
}

impl PairingSession {
    pub fn start(device_name: String, device_uuid: String) -> Result<(Self, SenderFrame)> {
        let secret = StaticSecret::random();
        let public_key = PublicKey::from(&secret).to_bytes();
        let private_key = secret.to_bytes();
        let mut nonce_s = [0_u8; 16];
        getrandom::fill(&mut nonce_s).map_err(|_| CastError::Crypto)?;
        let commit = sha256(&[public_key.as_slice(), nonce_s.as_slice()].concat());
        let frame = SenderFrame::PairingCommit {
            commit: BASE64.encode(commit),
            device_name,
            device_uuid,
        };
        Ok((
            Self {
                private_key,
                public_key,
                nonce_s,
                commit,
                transcript: None,
                shared_secret: None,
            },
            frame,
        ))
    }

    pub fn accept_challenge(
        &mut self,
        tv_eph_pub: &str,
        nonce_t: &str,
    ) -> Result<(String, SenderFrame)> {
        let tv_public = decode_array::<32>(tv_eph_pub, "tvEphPub")?;
        let nonce_t = decode_array::<16>(nonce_t, "nonceT")?;
        let secret = StaticSecret::from(self.private_key);
        let shared = secret
            .diffie_hellman(&PublicKey::from(tv_public))
            .to_bytes();
        let transcript = [
            self.commit.as_slice(),
            tv_public.as_slice(),
            nonce_t.as_slice(),
            self.public_key.as_slice(),
            self.nonce_s.as_slice(),
        ]
        .concat();
        let sas = generate_sas(&shared, &transcript);
        self.shared_secret = Some(shared);
        self.transcript = Some(transcript);
        Ok((
            sas,
            SenderFrame::PairingReveal {
                sender_eph_pub: BASE64.encode(self.public_key),
                nonce_s: BASE64.encode(self.nonce_s),
            },
        ))
    }

    pub fn confirmation(&self, entered_sas: &str, expected_sas: &str) -> Result<SenderFrame> {
        if entered_sas.replace(' ', "") != expected_sas {
            return Err(CastError::Protocol("SAS code does not match".into()));
        }
        let (shared, transcript) = self.secrets()?;
        let key = hkdf_expand(shared, b"confirmationKey")?;
        let mut mac = <HmacSha256 as Mac>::new_from_slice(&key).map_err(|_| CastError::Crypto)?;
        mac.update(transcript);
        Ok(SenderFrame::PairingConfirmation {
            mac: BASE64.encode(mac.finalize().into_bytes()),
        })
    }

    pub fn decrypt_credentials(
        &self,
        nonce: &str,
        ciphertext: &str,
        served_spki_pin: Option<&str>,
    ) -> Result<CredentialBundle> {
        let (shared, transcript) = self.secrets()?;
        let nonce = decode_array::<12>(nonce, "credential nonce")?;
        let ciphertext = BASE64
            .decode(ciphertext)
            .map_err(|_| CastError::Protocol("invalid credential ciphertext".into()))?;
        let key = hkdf_expand(shared, b"playbridgeCredentialKey-v1")?;
        let cipher = Aes256Gcm::new_from_slice(&key).map_err(|_| CastError::Crypto)?;
        let nonce = Nonce::try_from(nonce.as_slice()).map_err(|_| CastError::Crypto)?;
        let plaintext = cipher
            .decrypt(
                &nonce,
                Payload {
                    msg: &ciphertext,
                    aad: &sha256(transcript),
                },
            )
            .map_err(|_| CastError::Crypto)?;
        let bundle: CredentialBundle = serde_json::from_slice(&plaintext)
            .map_err(|error| CastError::Protocol(error.to_string()))?;
        if bundle.token.is_empty() {
            return Err(CastError::MissingField("credential token"));
        }
        if let (Some(expected), Some(served)) =
            (bundle.cert_fingerprint.as_deref(), served_spki_pin)
            && expected.as_bytes().ct_eq(served.as_bytes()).unwrap_u8() != 1
        {
            return Err(CastError::Protocol("credential SPKI pin mismatch".into()));
        }
        Ok(bundle)
    }

    fn secrets(&self) -> Result<(&[u8; 32], &[u8])> {
        Ok((
            self.shared_secret
                .as_ref()
                .ok_or(CastError::MissingField("pairing shared secret"))?,
            self.transcript
                .as_deref()
                .ok_or(CastError::MissingField("pairing transcript"))?,
        ))
    }
}

pub fn encode_text(frame: &SenderFrame) -> Result<String> {
    serde_json::to_string(frame).map_err(|error| CastError::Protocol(error.to_string()))
}

pub fn decode_receiver_text(text: &str) -> Result<ReceiverFrame> {
    serde_json::from_str(text).map_err(|error| CastError::Protocol(error.to_string()))
}

pub fn pointer_frame(event: u8, dx: f32, dy: f32) -> Result<[u8; 9]> {
    if event > 4 || !dx.is_finite() || !dy.is_finite() {
        return Err(CastError::Protocol("invalid pointer event".into()));
    }
    let mut frame = [0_u8; 9];
    frame[0] = event;
    frame[1..5].copy_from_slice(&dx.to_be_bytes());
    frame[5..9].copy_from_slice(&dy.to_be_bytes());
    Ok(frame)
}

fn generate_sas(shared: &[u8; 32], transcript: &[u8]) -> String {
    let hash = sha256(&[shared.as_slice(), transcript].concat());
    let value = u32::from_be_bytes(hash[..4].try_into().unwrap()) & 0x7fff_ffff;
    format!("{:06}", value % 1_000_000)
}

fn hkdf_expand(shared: &[u8; 32], info: &[u8]) -> Result<[u8; 32]> {
    let hkdf = Hkdf::<Sha256>::new(Some(&[0_u8; 32]), shared);
    let mut output = [0_u8; 32];
    hkdf.expand(info, &mut output)
        .map_err(|_| CastError::Crypto)?;
    Ok(output)
}

fn sha256(bytes: &[u8]) -> [u8; 32] {
    Sha256::digest(bytes).into()
}

fn decode_array<const N: usize>(value: &str, field: &str) -> Result<[u8; N]> {
    BASE64
        .decode(value)
        .map_err(|_| CastError::Protocol(format!("invalid Base64 {field}")))?
        .try_into()
        .map_err(|_| CastError::Protocol(format!("invalid {field} length")))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn wire_names_match_asyncapi_contract() {
        let auth = encode_text(&SenderFrame::Auth {
            token: "secret".into(),
        })
        .unwrap();
        assert_eq!(auth, r#"{"type":"auth","token":"secret"}"#);
        let query = encode_text(&SenderFrame::Command {
            action: "context_query".into(),
            payload: None,
        })
        .unwrap();
        assert_eq!(query, r#"{"type":"command","action":"context_query"}"#);
        let playlist = encode_text(&SenderFrame::Command {
            action: "playlist".into(),
            payload: Some(json!({"items": [{"url": "https://example.test/video.mp4"}]})),
        })
        .unwrap();
        assert!(!playlist.contains(r#""startIndex""#));
    }

    #[test]
    fn receiver_parser_tolerates_unknown_fields_and_types() {
        let response = decode_receiver_text(
            r#"{"type":"auth_response","success":true,"players":["mpv"],"future":1}"#,
        )
        .unwrap();
        assert!(matches!(
            response,
            ReceiverFrame::AuthResponse { success: true, .. }
        ));
        assert_eq!(
            decode_receiver_text(r#"{"type":"future_event","value":1}"#).unwrap(),
            ReceiverFrame::Unknown
        );
    }

    #[test]
    fn pointer_frame_is_nine_bytes_big_endian() {
        let frame = pointer_frame(2, 1.5, -2.0).unwrap();
        assert_eq!(frame, [2, 0x3f, 0xc0, 0, 0, 0xc0, 0, 0, 0]);
    }

    #[test]
    fn deterministic_pairing_math_matches_protocol_formula() {
        let private = [7_u8; 32];
        let public = PublicKey::from(&StaticSecret::from(private)).to_bytes();
        let nonce_s = [3_u8; 16];
        let commit = sha256(&[public.as_slice(), nonce_s.as_slice()].concat());
        let mut session = PairingSession {
            private_key: private,
            public_key: public,
            nonce_s,
            commit,
            transcript: None,
            shared_secret: None,
        };
        let tv_private = StaticSecret::from([11_u8; 32]);
        let tv_public = PublicKey::from(&tv_private).to_bytes();
        let (sas, reveal) = session
            .accept_challenge(&BASE64.encode(tv_public), &BASE64.encode([5_u8; 16]))
            .unwrap();
        assert_eq!(sas.len(), 6);
        assert!(sas.bytes().all(|byte| byte.is_ascii_digit()));
        assert!(matches!(reveal, SenderFrame::PairingReveal { .. }));
        assert!(matches!(
            session.confirmation(&sas, &sas).unwrap(),
            SenderFrame::PairingConfirmation { .. }
        ));

        let (shared, transcript) = session.secrets().unwrap();
        let credential_key = hkdf_expand(shared, b"playbridgeCredentialKey-v1").unwrap();
        let nonce = [9_u8; 12];
        let cipher = Aes256Gcm::new_from_slice(&credential_key).unwrap();
        let encrypted = cipher
            .encrypt(
                &Nonce::try_from(nonce.as_slice()).unwrap(),
                Payload {
                    msg: br#"{"token":"issued-token","certFingerprint":"sha256/test","players":["mpv"],"browsers":[]}"#,
                    aad: &sha256(transcript),
                },
            )
            .unwrap();
        let credentials = session
            .decrypt_credentials(
                &BASE64.encode(nonce),
                &BASE64.encode(encrypted),
                Some("sha256/test"),
            )
            .unwrap();
        assert_eq!(credentials.token, "issued-token");
        assert!(session
            .decrypt_credentials(
                &BASE64.encode(nonce),
                &BASE64.encode(cipher.encrypt(
                    &Nonce::try_from(nonce.as_slice()).unwrap(),
                    Payload { msg: br#"{"token":"issued-token","certFingerprint":"sha256/test"}"#, aad: &sha256(transcript) }
                ).unwrap()),
                Some("sha256/other"),
            )
            .is_err());
    }
}
