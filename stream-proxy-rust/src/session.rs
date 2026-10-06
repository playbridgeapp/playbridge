use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use dashmap::DashMap;
use rand::Rng;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::time::interval;
use tracing::info;

use crate::upstream::NetworkPolicy;

#[derive(Debug, Clone)]
pub struct ProxySession {
    pub id: String,
    pub original_url: String,
    pub headers: HashMap<String, String>,
    /// None is trusted application traffic. Presence marks page-controlled traffic and
    /// carries the exact private origins approved by the sender.
    pub network_policy: Option<NetworkPolicy>,
    pub created_at: Instant,
    pub last_accessed_at: Instant,
    expires_at: Instant,
    owner_managed: bool,
}

impl ProxySession {
    pub fn new(
        id: String,
        original_url: String,
        headers: HashMap<String, String>,
        network_policy: Option<NetworkPolicy>,
    ) -> Self {
        let now = Instant::now();
        Self {
            id,
            original_url,
            headers,
            network_policy,
            created_at: now,
            last_accessed_at: now,
            expires_at: now + Duration::from_secs(7200),
            owner_managed: false,
        }
    }
}

#[derive(Clone)]
pub struct SessionManager {
    sessions: Arc<DashMap<String, ProxySession>>,
    registration_lock: Arc<Mutex<()>>,
}

impl Default for SessionManager {
    fn default() -> Self {
        Self::new()
    }
}

impl SessionManager {
    pub fn new() -> Self {
        let manager = Self {
            sessions: Arc::new(DashMap::new()),
            registration_lock: Arc::new(Mutex::new(())),
        };
        manager.start_cleanup_task();
        manager
    }

    pub fn register(
        &self,
        original_url: String,
        headers: HashMap<String, String>,
        network_policy: Option<NetworkPolicy>,
    ) -> Result<ProxySession, String> {
        let _guard = self
            .registration_lock
            .lock()
            .map_err(|_| "proxy session registry is unavailable".to_string())?;
        if self.sessions.len() >= MAX_ACTIVE_SESSIONS {
            return Err("proxy session limit reached".into());
        }
        let id = Self::generate_id();
        let session = ProxySession::new(id.clone(), original_url, headers, network_policy);
        self.sessions.insert(id, session.clone());
        Ok(session)
    }

    pub fn get(&self, id: &str) -> Option<ProxySession> {
        if let Some(mut entry) = self.sessions.get_mut(id) {
            let now = Instant::now();
            if expired(&entry, now) {
                return None;
            }
            entry.last_accessed_at = now;
            return Some(entry.clone());
        }
        None
    }

    /// Only the registration owner may call this; HTTP playback cannot extend a lease.
    pub fn renew(&self, id: &str) -> bool {
        let Some(mut entry) = self.sessions.get_mut(id) else {
            return false;
        };
        let now = Instant::now();
        if expired(&entry, now) {
            return false;
        }
        entry.owner_managed = true;
        entry.expires_at = now + OWNER_ABANDONMENT_GRACE;
        true
    }

    pub fn clear(&self) {
        self.sessions.clear();
    }

    pub fn revoke(&self, id: &str) -> bool {
        self.sessions.remove(id).is_some()
    }

    fn generate_id() -> String {
        let mut bytes = [0u8; 24];
        rand::thread_rng().fill(&mut bytes);
        URL_SAFE_NO_PAD.encode(bytes)
    }

    fn start_cleanup_task(&self) {
        let sessions = Arc::downgrade(&self.sessions);
        tokio::spawn(async move {
            let mut timer = interval(Duration::from_secs(30));
            loop {
                timer.tick().await;
                let Some(sessions) = sessions.upgrade() else {
                    break;
                };
                let now = Instant::now();
                sessions.retain(|id, session| {
                    let inactive = now.saturating_duration_since(session.last_accessed_at);
                    let age = now.saturating_duration_since(session.created_at);
                    let expire = expired(session, now);
                    if expire {
                        info!(
                            "[stream-proxy] Expired session {} (inactive: {}s, age: {}s)",
                            id,
                            inactive.as_secs(),
                            age.as_secs()
                        );
                    }
                    !expire
                });
            }
        });
    }
}

// Allows lengthy pauses/background periods without issuing a new receiver URL.
// Active native owners renew; shutdown/revoke invalidates immediately.
const OWNER_ABANDONMENT_GRACE: Duration = Duration::from_secs(6 * 60 * 60);
const MAX_ACTIVE_SESSIONS: usize = 4_096;

fn expired(session: &ProxySession, now: Instant) -> bool {
    now >= session.expires_at
        || (!session.owner_managed
            && now.saturating_duration_since(session.last_accessed_at) > Duration::from_secs(600))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn active_owner_supports_long_playback_and_pauses_without_receiver_url_rotation() {
        let manager = super::SessionManager::new();
        let session = manager
            .register(
                "https://media.example/movie".into(),
                Default::default(),
                None,
            )
            .unwrap();
        assert!(manager.renew(&session.id));
        {
            let mut entry = manager.sessions.get_mut(&session.id).unwrap();
            entry.created_at =
                std::time::Instant::now() - std::time::Duration::from_secs(24 * 3600);
            entry.last_accessed_at =
                std::time::Instant::now() - std::time::Duration::from_secs(3 * 3600);
        }
        assert!(
            manager.get(&session.id).is_some(),
            "active-owner pause must not use old two-hour/idle cutoff"
        );
        assert!(manager.renew(&session.id));
        assert!(manager.get(&session.id).is_some());
        assert!(manager.revoke(&session.id));
        assert!(
            !manager.renew(&session.id),
            "renewal cannot revive a revoked owner"
        );
    }
    #[tokio::test]
    async fn bearer_reads_cannot_extend_an_abandoned_owner_and_expiry_is_checked_inline() {
        let manager = super::SessionManager::new();
        let session = manager
            .register(
                "https://media.example/movie".into(),
                Default::default(),
                None,
            )
            .unwrap();
        assert!(manager.renew(&session.id));
        let deadline = manager.sessions.get(&session.id).unwrap().expires_at;
        assert!(manager.get(&session.id).is_some());
        assert_eq!(
            deadline,
            manager.sessions.get(&session.id).unwrap().expires_at
        );
        manager.sessions.get_mut(&session.id).unwrap().expires_at =
            std::time::Instant::now() - std::time::Duration::from_secs(1);
        assert!(manager.get(&session.id).is_none());
        assert!(!manager.renew(&session.id));
    }

    #[tokio::test]
    async fn unretained_registration_keeps_idle_cutoff_and_first_owner_activates_lease() {
        let manager = SessionManager::new();
        let unused = manager
            .register(
                "https://media.example/unused".into(),
                Default::default(),
                None,
            )
            .unwrap();
        assert!(!manager.sessions.get(&unused.id).unwrap().owner_managed);
        manager
            .sessions
            .get_mut(&unused.id)
            .unwrap()
            .last_accessed_at = Instant::now() - Duration::from_secs(601);
        assert!(manager.get(&unused.id).is_none());
        assert!(
            !manager.renew(&unused.id),
            "expired initial registrations cannot be revived"
        );
        let retained = manager
            .register(
                "https://media.example/retained".into(),
                Default::default(),
                None,
            )
            .unwrap();
        assert!(manager.renew(&retained.id));
        manager
            .sessions
            .get_mut(&retained.id)
            .unwrap()
            .last_accessed_at = Instant::now() - Duration::from_secs(601);
        assert!(
            manager.get(&retained.id).is_some(),
            "first retain, not registration, enables long pauses"
        );
    }

    #[tokio::test]
    async fn active_session_count_is_bounded() {
        let manager = SessionManager::new();
        let mut first_id = None;
        for index in 0..MAX_ACTIVE_SESSIONS {
            let session = manager
                .register(
                    format!("https://cdn.example/{index}.mp4"),
                    HashMap::new(),
                    None,
                )
                .unwrap();
            first_id.get_or_insert(session.id);
        }
        assert!(manager
            .register(
                "https://cdn.example/overflow.mp4".into(),
                HashMap::new(),
                None,
            )
            .is_err());
        assert!(manager.revoke(first_id.as_deref().unwrap()));
        assert!(manager
            .register(
                "https://cdn.example/replacement.mp4".into(),
                HashMap::new(),
                None,
            )
            .is_ok());
    }
}
