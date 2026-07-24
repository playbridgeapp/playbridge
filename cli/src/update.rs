use semver::Version;
use serde::{Deserialize, Serialize};
use std::{
    fs,
    path::PathBuf,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

const RELEASES_URL: &str =
    "https://api.github.com/repos/playbridgeapp/playbridge/releases?per_page=30";
const SUCCESS_CACHE_AGE: Duration = Duration::from_secs(24 * 60 * 60);
const FAILURE_CACHE_AGE: Duration = Duration::from_secs(60 * 60);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(2);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AvailableUpdate {
    pub version: Version,
}

#[derive(Debug, Serialize, Deserialize)]
struct UpdateCache {
    checked_at: u64,
    latest_version: Option<String>,
    check_succeeded: bool,
}

#[derive(Debug, Deserialize)]
struct GithubRelease {
    tag_name: String,
    draft: bool,
    prerelease: bool,
}

pub async fn check_for_update() -> Option<AvailableUpdate> {
    let current = Version::parse(env!("CARGO_PKG_VERSION")).ok()?;
    let now = unix_timestamp();

    if let Some(cache) = load_cache()
        && cache_is_fresh(&cache, now)
    {
        return newer_version(&current, cache.latest_version.as_deref());
    }

    let result = fetch_latest_version().await;
    let cache = match &result {
        Ok(version) => UpdateCache {
            checked_at: now,
            latest_version: version.as_ref().map(ToString::to_string),
            check_succeeded: true,
        },
        Err(()) => UpdateCache {
            checked_at: now,
            latest_version: None,
            check_succeeded: false,
        },
    };
    let _ = save_cache(&cache);

    result
        .ok()
        .flatten()
        .filter(|latest| latest > &current)
        .map(|version| AvailableUpdate { version })
}

async fn fetch_latest_version() -> Result<Option<Version>, ()> {
    let client = reqwest::Client::builder()
        .timeout(REQUEST_TIMEOUT)
        .user_agent(concat!("playbridge-cli/", env!("CARGO_PKG_VERSION")))
        .build()
        .map_err(|_| ())?;
    let releases = client
        .get(RELEASES_URL)
        .send()
        .await
        .map_err(|_| ())?
        .error_for_status()
        .map_err(|_| ())?
        .json::<Vec<GithubRelease>>()
        .await
        .map_err(|_| ())?;
    Ok(latest_cli_version(&releases))
}

fn latest_cli_version(releases: &[GithubRelease]) -> Option<Version> {
    releases
        .iter()
        .filter(|release| !release.draft && !release.prerelease)
        .filter_map(|release| release.tag_name.strip_prefix("cli-v"))
        .filter_map(|version| Version::parse(version).ok())
        .max()
}

fn newer_version(current: &Version, latest: Option<&str>) -> Option<AvailableUpdate> {
    let latest = Version::parse(latest?).ok()?;
    (latest > *current).then_some(AvailableUpdate { version: latest })
}

fn cache_is_fresh(cache: &UpdateCache, now: u64) -> bool {
    let max_age = if cache.check_succeeded {
        SUCCESS_CACHE_AGE
    } else {
        FAILURE_CACHE_AGE
    };
    now.saturating_sub(cache.checked_at) < max_age.as_secs()
}

fn unix_timestamp() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

fn cache_path() -> Option<PathBuf> {
    let home = std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE"))?;
    Some(
        PathBuf::from(home)
            .join(".config")
            .join("playbridge")
            .join("update-check.json"),
    )
}

fn load_cache() -> Option<UpdateCache> {
    serde_json::from_slice(&fs::read(cache_path()?).ok()?).ok()
}

fn save_cache(cache: &UpdateCache) -> Result<(), std::io::Error> {
    let path = cache_path().ok_or_else(|| std::io::Error::other("home directory unavailable"))?;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(path, serde_json::to_vec(cache)?)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn release(tag_name: &str) -> GithubRelease {
        GithubRelease {
            tag_name: tag_name.into(),
            draft: false,
            prerelease: false,
        }
    }

    #[test]
    fn selects_highest_stable_cli_release_only() {
        let mut draft = release("cli-v9.0.0");
        draft.draft = true;
        let mut prerelease = release("cli-v8.0.0");
        prerelease.prerelease = true;
        let releases = [
            release("desktop-v7.0.0"),
            release("cli-v0.2.0"),
            release("cli-v1.1.0"),
            release("cli-vinvalid"),
            draft,
            prerelease,
        ];

        assert_eq!(latest_cli_version(&releases), Some(Version::new(1, 1, 0)));
    }

    #[test]
    fn reports_only_strictly_newer_versions() {
        let current = Version::new(1, 2, 3);
        assert!(newer_version(&current, Some("1.2.3")).is_none());
        assert!(newer_version(&current, Some("1.2.2")).is_none());
        assert_eq!(
            newer_version(&current, Some("2.0.0")).unwrap().version,
            Version::new(2, 0, 0)
        );
    }

    #[test]
    fn failed_checks_retry_sooner_than_successful_checks() {
        let now = 100_000;
        let success = UpdateCache {
            checked_at: now - FAILURE_CACHE_AGE.as_secs() - 1,
            latest_version: None,
            check_succeeded: true,
        };
        let failure = UpdateCache {
            checked_at: now - FAILURE_CACHE_AGE.as_secs() - 1,
            latest_version: None,
            check_succeeded: false,
        };

        assert!(cache_is_fresh(&success, now));
        assert!(!cache_is_fresh(&failure, now));
    }
}
