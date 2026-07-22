use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use dashmap::DashMap;
use rand::Rng;
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::time::interval;
use tracing::info;

#[derive(Debug, Clone)]
pub struct ProxySession {
    pub id: String,
    pub original_url: String,
    pub headers: HashMap<String, String>,
    pub created_at: Instant,
    pub last_accessed_at: Instant,
}

impl ProxySession {
    pub fn new(id: String, original_url: String, headers: HashMap<String, String>) -> Self {
        let now = Instant::now();
        Self {
            id,
            original_url,
            headers,
            created_at: now,
            last_accessed_at: now,
        }
    }
}

#[derive(Clone)]
pub struct SessionManager {
    sessions: Arc<DashMap<String, ProxySession>>,
}

impl SessionManager {
    pub fn new() -> Self {
        let manager = Self {
            sessions: Arc::new(DashMap::new()),
        };
        manager.start_cleanup_task();
        manager
    }

    pub fn register(&self, original_url: String, headers: HashMap<String, String>) -> ProxySession {
        let id = Self::generate_id();
        let session = ProxySession::new(id.clone(), original_url, headers);
        self.sessions.insert(id, session.clone());
        session
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

    fn generate_id() -> String {
        let mut bytes = [0u8; 16];
        rand::thread_rng().fill(&mut bytes);
        URL_SAFE_NO_PAD.encode(bytes)
    }

    fn start_cleanup_task(&self) {
        let sessions = self.sessions.clone();
        tokio::spawn(async move {
            let mut timer = interval(Duration::from_secs(30));
            loop {
                timer.tick().await;
                let max_inactive = Duration::from_secs(600); // 10 minutes
                let max_age = Duration::from_secs(7200);     // 2 hours

                sessions.retain(|id, session| {
                    let inactive = session.last_accessed_at.elapsed();
                    let age = session.created_at.elapsed();
                    let expire = inactive > max_inactive || age > max_age;
                    if expire {
                        info!("[stream-proxy] Expired session {} (inactive: {}s, age: {}s)", id, inactive.as_secs(), age.as_secs());
                    }
                    !expire
                });
            }
        });
    }
}
