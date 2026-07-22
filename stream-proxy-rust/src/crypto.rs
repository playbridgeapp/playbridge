use aes::Aes256;
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use cbc::{Decryptor, Encryptor};
use cipher::{block_padding::Pkcs7, BlockDecryptMut, BlockEncryptMut, KeyIvInit};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::time::{SystemTime, UNIX_EPOCH};

type Aes256CbcEnc = Encryptor<Aes256>;
type Aes256CbcDec = Decryptor<Aes256>;

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ProxyData {
    pub destination: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub request_headers: Option<HashMap<String, String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub exp: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ip: Option<String>,
}

#[derive(Clone)]
pub struct EncryptionHandler {
    key: [u8; 32],
}

impl EncryptionHandler {
    pub fn new(api_password: &[u8]) -> Self {
        let mut key = [0x20u8; 32]; // Space-padded to 32 bytes (matches MediaFlow/Python)
        let copy_len = api_password.len().min(32);
        key[..copy_len].copy_from_slice(&api_password[..copy_len]);
        Self { key }
    }

    pub fn encrypt(&self, data: &ProxyData) -> Result<String, String> {
        let json_data = serde_json::to_vec(data)
            .map_err(|e| format!("Failed to serialize proxy data: {}", e))?;

        let mut iv = [0u8; 16];
        rand::thread_rng().fill_bytes(&mut iv);

        let enc = Aes256CbcEnc::new(&self.key.into(), &iv.into());
        let ciphertext = enc.encrypt_padded_vec_mut::<Pkcs7>(&json_data);

        let mut final_data = Vec::with_capacity(16 + ciphertext.len());
        final_data.extend_from_slice(&iv);
        final_data.extend_from_slice(&ciphertext);

        Ok(URL_SAFE_NO_PAD.encode(final_data))
    }

    pub fn decrypt(&self, token: &str, client_ip: Option<&str>) -> Result<ProxyData, String> {
        let encrypted_data = URL_SAFE_NO_PAD
            .decode(token)
            .map_err(|e| format!("Invalid base64url token: {}", e))?;

        if encrypted_data.len() < 17 {
            return Err("Token payload too short".to_string());
        }

        let (iv_bytes, ciphertext) = encrypted_data.split_at(16);

        let dec = Aes256CbcDec::new(&self.key.into(), iv_bytes.into());
        let plaintext = dec
            .decrypt_padded_vec_mut::<Pkcs7>(ciphertext)
            .map_err(|_| "Decryption failed: invalid password or corrupt token".to_string())?;

        let proxy_data: ProxyData = serde_json::from_slice(&plaintext)
            .map_err(|e| format!("Invalid JSON inside token: {}", e))?;

        if let Some(exp) = proxy_data.exp {
            let now = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs();
            if exp < now {
                return Err("Token has expired".to_string());
            }
        }

        if let (Some(token_ip), Some(client_ip)) = (proxy_data.ip.as_ref(), client_ip) {
            if token_ip != client_ip {
                return Err("IP address mismatch".to_string());
            }
        }

        Ok(proxy_data)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_mediaflow_aes_roundtrip() {
        let handler = EncryptionHandler::new(b"my_secure_password");
        let mut headers = HashMap::new();
        headers.insert("User-Agent".to_string(), "PlayBridge".to_string());
        headers.insert(
            "Authorization".to_string(),
            "Bearer secret_debrid_key".to_string(),
        );

        let data = ProxyData {
            destination: "https://cdn.example.com/video.m3u8".to_string(),
            request_headers: Some(headers.clone()),
            exp: None,
            ip: None,
        };

        let token = handler.encrypt(&data).unwrap();
        assert!(!token.contains("secret_debrid_key")); // Ensure token is encrypted

        let decrypted = handler.decrypt(&token, None).unwrap();
        assert_eq!(decrypted.destination, "https://cdn.example.com/video.m3u8");
        assert_eq!(
            decrypted
                .request_headers
                .unwrap()
                .get("Authorization")
                .unwrap(),
            "Bearer secret_debrid_key"
        );
    }
}
