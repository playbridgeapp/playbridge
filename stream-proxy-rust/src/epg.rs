use bytes::Bytes;
use std::sync::Arc;
use std::time::{Duration, Instant};
use dashmap::DashMap;

struct EpgCacheEntry {
    bytes: Bytes,
    cached_at: Instant,
}

#[derive(Clone)]
pub struct EpgCache {
    entries: Arc<DashMap<String, EpgCacheEntry>>,
    ttl: Duration,
}

impl EpgCache {
    pub fn new(ttl: Duration) -> Self {
        Self {
            entries: Arc::new(DashMap::new()),
            ttl,
        }
    }

    pub fn get(&self, uri: &str) -> Option<Bytes> {
        if let Some(entry) = self.entries.get(uri) {
            if entry.cached_at.elapsed() < self.ttl {
                return Some(entry.bytes.clone());
            }
        }
        None
    }

    pub fn insert(&self, uri: String, bytes: Bytes) {
        self.entries.insert(
            uri,
            EpgCacheEntry {
                bytes,
                cached_at: Instant::now(),
            },
        );
    }

    pub fn clear(&self) {
        self.entries.clear();
    }
}
