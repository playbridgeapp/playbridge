//! Base64url without padding, matching the Workers' `BASE64URL` check.

use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};

use crate::{Error, Result};

pub fn encode(bytes: impl AsRef<[u8]>) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}

pub fn decode(text: &str) -> Result<Vec<u8>> {
    URL_SAFE_NO_PAD
        .decode(text)
        .map_err(|_| Error::Invalid("base64"))
}

pub fn decode_32(text: &str) -> Result<[u8; 32]> {
    decode(text)?
        .try_into()
        .map_err(|_| Error::Invalid("key length"))
}
