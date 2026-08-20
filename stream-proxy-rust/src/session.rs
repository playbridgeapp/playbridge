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
            entry.last_accessed_at = Instant::now();
            return Some(entry.clone());
        }
        None
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
                let max_inactive = Duration::from_secs(600); // 10 minutes
                let max_age = Duration::from_secs(7200); // 2 hours

                sessions.retain(|id, session| {
                    let inactive = session.last_accessed_at.elapsed();
                    let age = session.created_at.elapsed();
                    let expire = inactive > max_inactive || age > max_age;
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

const MAX_ACTIVE_SESSIONS: usize = 4_096;

#[cfg(test)]
mod tests {
    use super::*;

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
