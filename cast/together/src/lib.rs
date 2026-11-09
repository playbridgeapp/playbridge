//! PlayBridge Watch Together room engine.
//!
//! - [`engine::Engine`]: pure room state machine (no I/O), fed relay frames,
//!   ticks and commands; emits frames, player commands and UI events.
//! - [`client`]: async WebSocket loop that runs an engine against a relay and
//!   a [`client::Player`].
//! - [`pairing`]: receiver pairing codes and sealed invites.
//!
//! See `docs/design-watch-together.md`.

pub mod b64;
pub mod client;
pub mod clock;
pub mod crypto;
pub mod engine;
pub mod frames;
pub mod ids;
pub mod invite;
pub mod messages;
pub mod pairing;
pub mod sync;

pub use crypto::{MemberKeys, RoomKey};
pub use ids::{MemberId, RoomId};
pub use invite::Invite;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("invalid {0}")]
    Invalid(&'static str),
    #[error("decryption or signature check failed")]
    Crypto,
    #[error("relay error: {0}")]
    Relay(String),
    #[error("network error: {0}")]
    Network(String),
}

pub type Result<T> = std::result::Result<T, Error>;
