use semver::Version;
use serde::{Deserialize, Serialize};
use std::{
    fs,
    path::PathBuf,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

const MANIFEST_ENDPOINT: &str = "https://playbridge.app/api/v1/updates/cli";
const SUCCESS_CACHE_AGE: Duration = Duration::from_secs(24 * 60 * 60);
const FAILURE_CACHE_AGE: Duration = Duration::from_secs(60 * 60);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(5);
pub(crate) const MAX_UPDATE_ARCHIVE_BYTES: u64 = 256 * 1024 * 1024;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateAsset {
    pub name: String,
    pub url: String,
    pub sha256: String,
    pub size: Option<u64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateManifest {
    pub schema_version: u32,
    pub product: String,
    pub channel: String,
    pub version: String,
    pub published_at: String,
    pub release_url: String,
    pub asset: UpdateAsset,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AvailableUpdate {
    pub version: Version,
    pub manifest: UpdateManifest,
}

#[derive(Debug, Serialize, Deserialize)]
struct UpdateCache {
    checked_at: u64,
    manifest: Option<UpdateManifest>,
    check_succeeded: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UpdateTarget {
    pub os: &'static str,
    pub arch: &'static str,
    pub asset_name: &'static str,
}

pub fn checks_disabled() -> bool {
    std::env::var_os("PLAYBRIDGE_NO_UPDATE_CHECK").is_some()
}

pub fn current_target() -> Result<UpdateTarget, String> {
    match (std::env::consts::OS, std::env::consts::ARCH) {
        ("macos", "x86_64") => Ok(UpdateTarget {
            os: "macos",
            arch: "x86_64",
            asset_name: "playbridge-cli-macos-x86_64.tar.gz",
        }),
        ("macos", "aarch64") => Ok(UpdateTarget {
            os: "macos",
            arch: "aarch64",
            asset_name: "playbridge-cli-macos-aarch64.tar.gz",
        }),
        ("linux", "x86_64") => Ok(UpdateTarget {
            os: "linux",
            arch: "x86_64",
            asset_name: "playbridge-cli-linux-x86_64.tar.gz",
        }),
        ("linux", "aarch64") => Ok(UpdateTarget {
            os: "linux",
            arch: "aarch64",
            asset_name: "playbridge-cli-linux-aarch64.tar.gz",
        }),
        ("windows", "x86_64") => Ok(UpdateTarget {
            os: "windows",
            arch: "x86_64",
            asset_name: "playbridge-cli-windows-x86_64.tar.gz",
        }),
        (os, arch) => Err(format!("CLI updates are not published for {os}/{arch}")),
    }
}

pub async fn check_for_update(force: bool) -> Result<Option<AvailableUpdate>, String> {
    if checks_disabled() {
        return Err("Update checks are disabled by PLAYBRIDGE_NO_UPDATE_CHECK".into());
    }
    check_for_update_at(force, MANIFEST_ENDPOINT).await
}

async fn check_for_update_at(
    force: bool,
    endpoint: &str,
) -> Result<Option<AvailableUpdate>, String> {
    let current = Version::parse(env!("CARGO_PKG_VERSION"))
        .map_err(|error| format!("Invalid installed CLI version: {error}"))?;
    let target = current_target()?;
    let now = unix_timestamp();

    if !force
        && let Some(cache) = load_cache()
        && cache_is_fresh(&cache, now)
    {
        return evaluate_manifest(&current, target, cache.manifest);
    }

    let result = fetch_manifest(endpoint, target).await.and_then(|manifest| {
        validate_manifest(&manifest, target)?;
        Ok(manifest)
    });
    let cache = match &result {
        Ok(manifest) => UpdateCache {
            checked_at: now,
            manifest: Some(manifest.clone()),
            check_succeeded: true,
        },
        Err(_) => UpdateCache {
            checked_at: now,
            manifest: None,
            check_succeeded: false,
        },
    };
    let _ = save_cache(&cache);

    evaluate_manifest(&current, target, Some(result?))
}

async fn fetch_manifest(endpoint: &str, target: UpdateTarget) -> Result<UpdateManifest, String> {
    let client = reqwest::Client::builder()
        .timeout(REQUEST_TIMEOUT)
        .user_agent(concat!("playbridge-cli/", env!("CARGO_PKG_VERSION")))
        .build()
        .map_err(|error| format!("Could not create update client: {error}"))?;
    let mut url = reqwest::Url::parse(endpoint)
        .map_err(|error| format!("Invalid update service endpoint: {error}"))?;
    url.query_pairs_mut()
        .append_pair("os", target.os)
        .append_pair("arch", target.arch);
    client
        .get(url)
        .send()
        .await
        .map_err(|error| format!("Update service request failed: {error}"))?
        .error_for_status()
        .map_err(|error| format!("Update service returned an error: {error}"))?
        .json::<UpdateManifest>()
        .await
        .map_err(|error| format!("Update service returned invalid metadata: {error}"))
}

fn evaluate_manifest(
    current: &Version,
    target: UpdateTarget,
    manifest: Option<UpdateManifest>,
) -> Result<Option<AvailableUpdate>, String> {
    let Some(manifest) = manifest else {
        return Ok(None);
    };
    validate_manifest(&manifest, target)?;
    let version = Version::parse(&manifest.version)
        .map_err(|error| format!("Update service returned an invalid version: {error}"))?;
    Ok((version > *current).then_some(AvailableUpdate { version, manifest }))
}

fn validate_manifest(manifest: &UpdateManifest, target: UpdateTarget) -> Result<(), String> {
    if manifest.schema_version != 1 || manifest.product != "cli" || manifest.channel != "stable" {
        return Err("Update service returned an unsupported manifest".into());
    }
    if manifest.asset.name != target.asset_name {
        return Err("Update service returned an asset for a different platform".into());
    }
    let url = reqwest::Url::parse(&manifest.asset.url)
        .map_err(|_| "Update service returned an invalid asset URL".to_owned())?;
    if url.scheme() != "https" {
        return Err("Update assets must use HTTPS".into());
    }
    if manifest.asset.sha256.len() != 64
        || !manifest
            .asset
            .sha256
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit())
    {
        return Err("Update service returned an invalid SHA-256 checksum".into());
    }
    if manifest
        .asset
        .size
        .is_some_and(|size| size == 0 || size > MAX_UPDATE_ARCHIVE_BYTES)
    {
        return Err("Update service returned an invalid archive size".into());
    }
    Ok(())
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

pub(crate) fn state_directory() -> Option<PathBuf> {
    let home = std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE"))?;
    Some(PathBuf::from(home).join(".config").join("playbridge"))
}

fn cache_path() -> Option<PathBuf> {
    Some(state_directory()?.join("update-check.json"))
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

    fn manifest(version: &str) -> UpdateManifest {
        let target = current_target().unwrap();
        UpdateManifest {
            schema_version: 1,
            product: "cli".into(),
            channel: "stable".into(),
            version: version.into(),
            published_at: "2026-08-01T00:00:00Z".into(),
            release_url: "https://github.com/playbridgeapp/playbridge/releases".into(),
            asset: UpdateAsset {
                name: target.asset_name.into(),
                url: "https://github.com/playbridgeapp/playbridge/releases/download/file".into(),
                sha256: "a".repeat(64),
                size: Some(42),
            },
        }
    }

    #[test]
    fn reports_only_strictly_newer_versions() {
        let current = Version::new(1, 2, 3);
        let target = current_target().unwrap();
        assert!(
            evaluate_manifest(&current, target, Some(manifest("1.2.3")))
                .unwrap()
                .is_none()
        );
        assert!(
            evaluate_manifest(&current, target, Some(manifest("1.2.2")))
                .unwrap()
                .is_none()
        );
        assert_eq!(
            evaluate_manifest(&current, target, Some(manifest("2.0.0")))
                .unwrap()
                .unwrap()
                .version,
            Version::new(2, 0, 0)
        );
    }

    #[test]
    fn rejects_wrong_target_and_insecure_assets() {
        let target = current_target().unwrap();
        let mut wrong = manifest("2.0.0");
        wrong.asset.name = "some-other-platform.tar.gz".into();
        assert!(validate_manifest(&wrong, target).is_err());
        let mut insecure = manifest("2.0.0");
        insecure.asset.url = "http://example.test/update.tar.gz".into();
        assert!(validate_manifest(&insecure, target).is_err());
        let mut oversized = manifest("2.0.0");
        oversized.asset.size = Some(MAX_UPDATE_ARCHIVE_BYTES + 1);
        assert!(validate_manifest(&oversized, target).is_err());
    }

    #[test]
    fn failed_checks_retry_sooner_than_successful_checks() {
        let now = 100_000;
        let success = UpdateCache {
            checked_at: now - FAILURE_CACHE_AGE.as_secs() - 1,
            manifest: None,
            check_succeeded: true,
        };
        let failure = UpdateCache {
            checked_at: now - FAILURE_CACHE_AGE.as_secs() - 1,
            manifest: None,
            check_succeeded: false,
        };

        assert!(cache_is_fresh(&success, now));
        assert!(!cache_is_fresh(&failure, now));
    }
}
