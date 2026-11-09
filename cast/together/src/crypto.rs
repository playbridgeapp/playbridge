//! Room encryption, member keys and sealed boxes.
//!
//! - Room messages: AES-256-GCM with a key derived from the room key. The
//!   additional data binds each ciphertext to the room, its sender and its
//!   sequence number, so the relay cannot re-attribute or replay it.
//! - Member keys: X25519 for messages sealed to one member (credentials,
//!   pairing invites) and Ed25519 for signing host-only messages.
//! - Sealed box: ephemeral X25519 + HKDF + AES-256-GCM, readable only by the
//!   holder of the recipient's private key.

use aes_gcm::{
    Aes256Gcm, Nonce,
    aead::{Aead, KeyInit, Payload},
};
use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};
use hkdf::Hkdf;
use sha2::{Digest, Sha256};
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::{Zeroize, ZeroizeOnDrop};

use crate::b64;
use crate::ids::{MemberId, RoomId};
use crate::{Error, Result};

const NONCE_LEN: usize = 12;

pub(crate) fn random_bytes<const N: usize>() -> [u8; N] {
    let mut bytes = [0_u8; N];
    getrandom::fill(&mut bytes).expect("OS randomness unavailable");
    bytes
}

fn hkdf(salt: &[u8], ikm: &[u8], info: &[u8]) -> [u8; 32] {
    let mut out = [0_u8; 32];
    Hkdf::<Sha256>::new(Some(salt), ikm)
        .expand(info, &mut out)
        .expect("32 bytes is a valid HKDF-SHA256 length");
    out
}

fn seal_with(key: &[u8; 32], plaintext: &[u8], aad: &[u8]) -> Vec<u8> {
    let cipher = Aes256Gcm::new_from_slice(key).expect("32-byte key");
    let nonce = random_bytes::<NONCE_LEN>();
    let nonce_ref = Nonce::try_from(nonce.as_slice()).expect("12-byte nonce");
    let mut out = nonce.to_vec();
    out.extend(
        cipher
            .encrypt(
                &nonce_ref,
                Payload {
                    msg: plaintext,
                    aad,
                },
            )
            .expect("AES-GCM encryption cannot fail for in-memory buffers"),
    );
    out
}

fn open_with(key: &[u8; 32], sealed: &[u8], aad: &[u8]) -> Result<Vec<u8>> {
    if sealed.len() < NONCE_LEN + 16 {
        return Err(Error::Crypto);
    }
    let cipher = Aes256Gcm::new_from_slice(key).expect("32-byte key");
    let nonce = Nonce::try_from(&sealed[..NONCE_LEN]).map_err(|_| Error::Crypto)?;
    cipher
        .decrypt(
            &nonce,
            Payload {
                msg: &sealed[NONCE_LEN..],
                aad,
            },
        )
        .map_err(|_| Error::Crypto)
}

/// What a ciphertext carries, so a `msg` can never be replayed as a `state`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Channel {
    Message,
    State,
}

/// The shared secret from the invite fragment.
#[derive(Clone, Zeroize, ZeroizeOnDrop)]
pub struct RoomKey([u8; 32]);

impl RoomKey {
    pub fn generate() -> Self {
        Self(random_bytes())
    }

    pub fn from_bytes(bytes: [u8; 32]) -> Self {
        Self(bytes)
    }

    pub fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }

    /// Bearer token the relay checks; it learns nothing about the message key.
    pub fn auth_token(&self, room: &RoomId) -> String {
        b64::encode(hkdf(room.as_bytes(), &self.0, b"pb-together v1 auth"))
    }

    fn message_key(&self, room: &RoomId) -> [u8; 32] {
        hkdf(room.as_bytes(), &self.0, b"pb-together v1 msg")
    }

    pub fn seal(
        &self,
        room: &RoomId,
        channel: Channel,
        from: &MemberId,
        seq: u64,
        plaintext: &[u8],
    ) -> String {
        let aad = aad(room, channel, from, seq);
        b64::encode(seal_with(&self.message_key(room), plaintext, &aad))
    }

    pub fn open(
        &self,
        room: &RoomId,
        channel: Channel,
        from: &MemberId,
        seq: u64,
        ct: &str,
    ) -> Result<Vec<u8>> {
        let aad = aad(room, channel, from, seq);
        open_with(&self.message_key(room), &b64::decode(ct)?, &aad)
    }
}

impl std::fmt::Debug for RoomKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("RoomKey(..)")
    }
}

fn aad(room: &RoomId, channel: Channel, from: &MemberId, seq: u64) -> Vec<u8> {
    let mut aad = Vec::with_capacity(16 + 1 + 16 + 8);
    aad.extend_from_slice(room.as_bytes());
    aad.push(match channel {
        Channel::Message => b'm',
        Channel::State => b's',
    });
    aad.extend_from_slice(from.as_bytes());
    aad.extend_from_slice(&seq.to_be_bytes());
    aad
}

/// A member's long-lived identity within a room.
pub struct MemberKeys {
    box_secret: StaticSecret,
    signing: SigningKey,
}

impl MemberKeys {
    pub fn generate() -> Self {
        Self {
            box_secret: StaticSecret::random(),
            signing: SigningKey::from_bytes(&random_bytes()),
        }
    }

    /// Rebuilds keys saved with [`MemberKeys::to_bytes`].
    pub fn from_bytes(bytes: &[u8; 64]) -> Self {
        let mut box_bytes = [0_u8; 32];
        let mut sign_bytes = [0_u8; 32];
        box_bytes.copy_from_slice(&bytes[..32]);
        sign_bytes.copy_from_slice(&bytes[32..]);
        let keys = Self {
            box_secret: StaticSecret::from(box_bytes),
            signing: SigningKey::from_bytes(&sign_bytes),
        };
        box_bytes.zeroize();
        sign_bytes.zeroize();
        keys
    }

    pub fn to_bytes(&self) -> [u8; 64] {
        let mut out = [0_u8; 64];
        out[..32].copy_from_slice(self.box_secret.as_bytes());
        out[32..].copy_from_slice(&self.signing.to_bytes());
        out
    }

    pub fn box_public(&self) -> [u8; 32] {
        PublicKey::from(&self.box_secret).to_bytes()
    }

    pub fn signing_public(&self) -> [u8; 32] {
        self.signing.verifying_key().to_bytes()
    }

    pub fn sign(&self, message: &[u8]) -> String {
        b64::encode(self.signing.sign(message).to_bytes())
    }

    /// Opens a [`seal_to`] box addressed to this member.
    pub fn open_sealed(&self, sealed: &str, context: &[u8]) -> Result<Vec<u8>> {
        open_sealed(&self.box_secret, sealed, context)
    }
}

pub fn verify(signing_public: &[u8; 32], message: &[u8], signature: &str) -> bool {
    let Ok(key) = VerifyingKey::from_bytes(signing_public) else {
        return false;
    };
    let Ok(bytes) = b64::decode(signature) else {
        return false;
    };
    let Ok(signature) = Signature::from_slice(&bytes) else {
        return false;
    };
    key.verify_strict(message, &signature).is_ok()
}

/// Seals `plaintext` so only the holder of `recipient`'s X25519 secret can
/// open it. `context` is bound in as additional data (for example the pairing
/// code), so a box cannot be replayed into another context.
pub fn seal_to(recipient: &[u8; 32], plaintext: &[u8], context: &[u8]) -> String {
    let ephemeral = StaticSecret::random();
    let ephemeral_public = PublicKey::from(&ephemeral).to_bytes();
    let shared = ephemeral.diffie_hellman(&PublicKey::from(*recipient));
    let key = box_key(shared.as_bytes(), &ephemeral_public, recipient);
    let mut out = ephemeral_public.to_vec();
    out.extend(seal_with(&key, plaintext, context));
    b64::encode(out)
}

fn open_sealed(secret: &StaticSecret, sealed: &str, context: &[u8]) -> Result<Vec<u8>> {
    let bytes = b64::decode(sealed)?;
    if bytes.len() < 32 {
        return Err(Error::Crypto);
    }
    let mut ephemeral_public = [0_u8; 32];
    ephemeral_public.copy_from_slice(&bytes[..32]);
    let shared = secret.diffie_hellman(&PublicKey::from(ephemeral_public));
    if !shared.was_contributory() {
        return Err(Error::Crypto);
    }
    let recipient = PublicKey::from(secret).to_bytes();
    let key = box_key(shared.as_bytes(), &ephemeral_public, &recipient);
    open_with(&key, &bytes[32..], context)
}

fn box_key(shared: &[u8; 32], ephemeral_public: &[u8; 32], recipient: &[u8; 32]) -> [u8; 32] {
    let mut info = b"pb-together v1 box".to_vec();
    info.extend_from_slice(ephemeral_public);
    info.extend_from_slice(recipient);
    hkdf(&[], shared, &info)
}

/// Short fingerprint of a public key, carried in pairing QR codes.
pub fn fingerprint(public: &[u8; 32]) -> String {
    b64::encode(&Sha256::digest(public)[..12])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn room_messages_round_trip_and_bind_sender_and_seq() {
        let key = RoomKey::generate();
        let room = RoomId::generate();
        let a = MemberId::generate();
        let b = MemberId::generate();
        let ct = key.seal(&room, Channel::Message, &a, 7, b"hello");
        assert_eq!(
            key.open(&room, Channel::Message, &a, 7, &ct).unwrap(),
            b"hello"
        );
        assert!(key.open(&room, Channel::Message, &b, 7, &ct).is_err());
        assert!(key.open(&room, Channel::Message, &a, 8, &ct).is_err());
        assert!(key.open(&room, Channel::State, &a, 7, &ct).is_err());
        assert!(
            RoomKey::generate()
                .open(&room, Channel::Message, &a, 7, &ct)
                .is_err()
        );
    }

    #[test]
    fn auth_token_is_stable_and_room_specific() {
        let key = RoomKey::from_bytes([3; 32]);
        let room = RoomId::from_bytes([1; 16]);
        assert_eq!(key.auth_token(&room), key.auth_token(&room));
        assert_ne!(
            key.auth_token(&room),
            key.auth_token(&RoomId::from_bytes([2; 16]))
        );
    }

    #[test]
    fn sealed_boxes_open_only_for_the_recipient_and_context() {
        let tv = MemberKeys::generate();
        let sealed = seal_to(&tv.box_public(), b"invite", b"ABC234");
        assert_eq!(tv.open_sealed(&sealed, b"ABC234").unwrap(), b"invite");
        assert!(tv.open_sealed(&sealed, b"XYZ999").is_err());
        assert!(
            MemberKeys::generate()
                .open_sealed(&sealed, b"ABC234")
                .is_err()
        );
    }

    #[test]
    fn signatures_verify_and_reject_tampering() {
        let host = MemberKeys::generate();
        let sig = host.sign(b"load");
        assert!(verify(&host.signing_public(), b"load", &sig));
        assert!(!verify(&host.signing_public(), b"lock", &sig));
        assert!(!verify(
            &MemberKeys::generate().signing_public(),
            b"load",
            &sig
        ));
    }

    #[test]
    fn member_keys_survive_a_round_trip() {
        let keys = MemberKeys::generate();
        let again = MemberKeys::from_bytes(&keys.to_bytes());
        assert_eq!(keys.box_public(), again.box_public());
        assert_eq!(keys.signing_public(), again.signing_public());
    }
}
