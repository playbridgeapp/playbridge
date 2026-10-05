use aes::Aes256;
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use cbc::{Decryptor, Encryptor};
use cipher::{block_padding::Pkcs7, BlockDecryptMut, BlockEncryptMut, KeyIvInit};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use rand::RngCore;
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use sha2::Sha256;
use std::collections::HashMap;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::upstream::NetworkPolicy;

type Aes256CbcEnc = Encryptor<Aes256>;
type Aes256CbcDec = Decryptor<Aes256>;
const INVALID_TOKEN: &str = "Invalid proxy capability";
const MAX_TOKEN_BYTES: usize = 32 * 1024;

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ProxyData {
    pub destination: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub request_headers: Option<HashMap<String, String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub exp: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ip: Option<String>,
    /// Original credential scope and network policy survive manifest-child issuance.
    pub credential_url: String,
    pub network_policy: NetworkPolicy,
}

#[derive(Debug, Serialize, Deserialize)]
pub(crate) struct ResourceGrant {
    pub session_id: String,
    pub destination: String,
}

#[derive(Clone)]
pub struct EncryptionHandler {
    key: [u8; 32],
    mac_key: [u8; 32],
}

impl EncryptionHandler {
    pub fn new(api_password: &[u8]) -> Self {
        let derivation =
            Hkdf::<Sha256>::new(Some(b"PlayBridge proxy capabilities v2"), api_password);
        let mut key = [0; 32];
        let mut mac_key = [0; 32];
        derivation
            .expand(b"encryption", &mut key)
            .expect("fixed key length");
        derivation
            .expand(b"authentication", &mut mac_key)
            .expect("fixed key length");
        Self { key, mac_key }
    }

    pub fn encrypt(&self, data: &ProxyData) -> Result<String, String> {
        self.seal("pb2", data)
    }

    pub fn decrypt(&self, token: &str, client_ip: Option<&str>) -> Result<ProxyData, String> {
        let data: ProxyData = self.open("pb2", token)?;
        if data.exp.is_some_and(|exp| {
            exp < SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs()
        }) || data.ip.as_deref().is_some_and(|ip| Some(ip) != client_ip)
        {
            return Err(INVALID_TOKEN.into());
        }
        Ok(data)
    }

    pub(crate) fn encrypt_resource(&self, data: &ResourceGrant) -> Result<String, String> {
        self.seal("pr2", data)
    }

    pub(crate) fn decrypt_resource(&self, token: &str) -> Result<ResourceGrant, String> {
        self.open("pr2", token)
    }

    fn seal<T: Serialize>(&self, version: &str, data: &T) -> Result<String, String> {
        let json = serde_json::to_vec(data).map_err(|_| INVALID_TOKEN.to_string())?;
        if json.len() > MAX_TOKEN_BYTES / 2 {
            return Err(INVALID_TOKEN.into());
        }
        let mut iv = [0; 16];
        rand::thread_rng().fill_bytes(&mut iv);
        let ciphertext =
            Aes256CbcEnc::new(&self.key.into(), &iv.into()).encrypt_padded_vec_mut::<Pkcs7>(&json);
        let mut body = iv.to_vec();
        body.extend(ciphertext);
        let encoded = format!("{version}.{}", URL_SAFE_NO_PAD.encode(body));
        let mut mac = Hmac::<Sha256>::new_from_slice(&self.mac_key).expect("fixed key length");
        mac.update(encoded.as_bytes());
        Ok(format!(
            "{encoded}.{}",
            URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes())
        ))
    }

    fn open<T: DeserializeOwned>(&self, version: &str, token: &str) -> Result<T, String> {
        let invalid = || INVALID_TOKEN.to_string();
        if token.len() > MAX_TOKEN_BYTES {
            return Err(invalid());
        }
        let (authenticated, tag) = token.rsplit_once('.').ok_or_else(invalid)?;
        let (actual_version, encoded) = authenticated.split_once('.').ok_or_else(invalid)?;
        if actual_version != version {
            return Err(invalid());
        }
        let tag = URL_SAFE_NO_PAD.decode(tag).map_err(|_| invalid())?;
        let mut mac = Hmac::<Sha256>::new_from_slice(&self.mac_key).expect("fixed key length");
        mac.update(authenticated.as_bytes());
        // Authenticate the version, IV and ciphertext before decoding/decrypting their contents.
        mac.verify_slice(&tag).map_err(|_| invalid())?;
        let body = URL_SAFE_NO_PAD.decode(encoded).map_err(|_| invalid())?;
        if body.len() < 32 || body.len() % 16 != 0 {
            return Err(invalid());
        }
        let (iv, ciphertext) = body.split_at(16);
        let plaintext = Aes256CbcDec::new(&self.key.into(), iv.into())
            .decrypt_padded_vec_mut::<Pkcs7>(ciphertext)
            .map_err(|_| invalid())?;
        serde_json::from_slice(&plaintext).map_err(|_| invalid())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn data() -> ProxyData {
        ProxyData {
            destination: "https://cdn.example/video.m3u8".into(),
            credential_url: "https://cdn.example/video.m3u8".into(),
            network_policy: NetworkPolicy::new(vec![]).unwrap(),
            request_headers: Some(HashMap::from([(
                "Authorization".into(),
                "secret_debrid_key".into(),
            )])),
            exp: None,
            ip: None,
        }
    }

    #[test]
    fn authenticated_tokens_roundtrip_without_exposing_credentials() {
        let handler = EncryptionHandler::new(b"testpassword123");
        let token = handler.encrypt(&data()).unwrap();
        assert!(token.starts_with("pb2."));
        assert!(!token.contains("secret_debrid_key"));
        assert_eq!(
            handler.decrypt(&token, None).unwrap().destination,
            data().destination
        );
    }

    #[test]
    fn every_single_byte_modification_and_legacy_token_is_rejected_uniformly() {
        let handler = EncryptionHandler::new(b"testpassword123");
        let token = handler.encrypt(&data()).unwrap();
        for index in 0..token.len() {
            let mut changed = token.as_bytes().to_vec();
            changed[index] = if changed[index] == b'A' { b'B' } else { b'A' };
            assert_eq!(
                handler
                    .decrypt(std::str::from_utf8(&changed).unwrap(), None)
                    .unwrap_err(),
                INVALID_TOKEN
            );
        }
        for invalid in [
            "",
            "%%%%",
            "v3.abc.def",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        ] {
            assert_eq!(handler.decrypt(invalid, None).unwrap_err(), INVALID_TOKEN);
        }
        assert_eq!(
            EncryptionHandler::new(b"differentpassword")
                .decrypt(&token, None)
                .unwrap_err(),
            INVALID_TOKEN
        );
    }

    #[test]
    fn expired_wrong_ip_and_cross_purpose_tokens_are_rejected() {
        let handler = EncryptionHandler::new(b"test");
        let mut payload = data();
        payload.exp = Some(1);
        assert_eq!(
            handler
                .decrypt(&handler.encrypt(&payload).unwrap(), None)
                .unwrap_err(),
            INVALID_TOKEN
        );
        payload.exp = None;
        payload.ip = Some("192.168.1.2".into());
        let token = handler.encrypt(&payload).unwrap();
        assert!(handler.decrypt(&token, Some("192.168.1.2")).is_ok());
        assert!(handler.decrypt(&token, None).is_err());
        let resource = ResourceGrant {
            session_id: "session".into(),
            destination: data().destination,
        };
        assert!(handler
            .decrypt(&handler.encrypt_resource(&resource).unwrap(), None)
            .is_err());
        assert!(handler.decrypt_resource(&token).is_err());
    }
}
