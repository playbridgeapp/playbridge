use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use dashmap::DashMap;
use rand::Rng;
use std::{
    path::{Path, PathBuf},
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::time::interval;

#[derive(Debug, Clone)]
pub struct FileGrant {
    pub id: String,
    pub path: PathBuf,
    pub filename: String,
    pub content_type: String,
    pub created_at: Instant,
    pub expires_at: Instant,
}

#[derive(Clone, Default)]
pub struct FileGrantManager {
    grants: Arc<DashMap<String, FileGrant>>,
}

impl FileGrantManager {
    pub fn new() -> Self {
        let manager = Self::default();
        manager.start_cleanup_task();
        manager
    }

    pub fn register(
        &self,
        path: impl AsRef<Path>,
        content_type: Option<String>,
        ttl: Duration,
    ) -> Result<FileGrant, String> {
        let path = path.as_ref();
        let metadata = std::fs::metadata(path)
            .map_err(|error| format!("cannot access local media file: {error}"))?;
        if !metadata.is_file() {
            return Err("local media path is not a file".into());
        }
        let filename = path
            .file_name()
            .and_then(|value| value.to_str())
            .filter(|value| !value.is_empty())
            .unwrap_or("media")
            .to_owned();
        let content_type = content_type.unwrap_or_else(|| {
            mime_guess::from_path(path)
                .first_or_octet_stream()
                .essence_str()
                .to_owned()
        });
        let now = Instant::now();
        let grant = FileGrant {
            id: random_id(),
            path: path.to_path_buf(),
            filename,
            content_type,
            created_at: now,
            expires_at: now + ttl,
        };
        self.grants.insert(grant.id.clone(), grant.clone());
        Ok(grant)
    }

    pub fn get(&self, id: &str) -> Option<FileGrant> {
        let grant = self.grants.get(id)?.clone();
        if Instant::now() >= grant.expires_at {
            self.grants.remove(id);
            return None;
        }
        Some(grant)
    }

    pub fn revoke(&self, id: &str) -> bool {
        self.grants.remove(id).is_some()
    }

    pub fn clear(&self) {
        self.grants.clear();
    }

    fn start_cleanup_task(&self) {
        let grants = Arc::downgrade(&self.grants);
        tokio::spawn(async move {
            let mut timer = interval(Duration::from_secs(30));
            loop {
                timer.tick().await;
                let Some(grants) = grants.upgrade() else {
                    break;
                };
                let now = Instant::now();
                grants.retain(|_, grant| now < grant.expires_at);
            }
        });
    }
}

fn random_id() -> String {
    let mut bytes = [0_u8; 24];
    rand::thread_rng().fill(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

#[cfg(test)]
mod tests {
    use std::{fs, time::Duration};

    use super::FileGrantManager;

    #[tokio::test]
    async fn file_grants_are_scoped_and_revocable() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("video.mp4");
        fs::write(&path, b"media").unwrap();
        let grants = FileGrantManager::new();
        let grant = grants
            .register(&path, None, Duration::from_secs(60))
            .unwrap();
        assert_eq!(grant.content_type, "video/mp4");
        assert!(grant.id.len() >= 32);
        assert_eq!(grants.get(&grant.id).unwrap().path, path);
        assert!(grants.revoke(&grant.id));
        assert!(grants.get(&grant.id).is_none());
    }

    #[tokio::test]
    async fn expired_file_grants_are_rejected() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("video.bin");
        fs::write(&path, b"media").unwrap();
        let grants = FileGrantManager::new();
        let grant = grants
            .register(&path, None, Duration::from_millis(1))
            .unwrap();
        tokio::time::sleep(Duration::from_millis(5)).await;
        assert!(grants.get(&grant.id).is_none());
    }
}
