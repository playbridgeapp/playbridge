use crate::update::{self, AvailableUpdate};
use flate2::read::GzDecoder;
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::Write,
    path::{Component, Path, PathBuf},
    process::{Command, Stdio},
};
use tar::Archive;
use tokio::sync::mpsc;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct InstallProgress {
    pub downloaded: u64,
    pub total: Option<u64>,
}

#[derive(Debug)]
pub struct PreparedUpdate {
    executable: PathBuf,
    staged_binary: PathBuf,
    backup_binary: PathBuf,
    staging_root: PathBuf,
    archive_path: PathBuf,
    marker_path: PathBuf,
    cleanup_armed: bool,
}

impl Drop for PreparedUpdate {
    fn drop(&mut self) {
        if !self.cleanup_armed {
            return;
        }
        let _ = fs::remove_file(&self.staged_binary);
        let _ = fs::remove_file(&self.marker_path);
        let _ = fs::remove_file(&self.archive_path);
        let _ = fs::remove_dir_all(&self.staging_root);
    }
}

#[derive(Debug, Serialize, Deserialize)]
struct PendingUpdate {
    target_version: String,
    backup_binary: PathBuf,
    staged_binary: PathBuf,
}

pub fn manual_install_hint() -> &'static str {
    if cfg!(windows) {
        "Run in PowerShell: irm https://playbridge.app/install.ps1 | iex"
    } else {
        "Run: curl -fsSL https://playbridge.app/install.sh | sh"
    }
}

pub fn preflight() -> Result<PathBuf, String> {
    let executable = std::env::current_exe()
        .map_err(|error| format!("Could not locate the running executable: {error}"))?;
    let expected = if cfg!(windows) {
        "playbridge.exe"
    } else {
        "playbridge"
    };
    if executable.file_name().and_then(|value| value.to_str()) != Some(expected) {
        return Err(format!(
            "This development or renamed binary cannot update itself. {}",
            manual_install_hint()
        ));
    }
    let parent = executable
        .parent()
        .ok_or_else(|| "The executable has no install directory".to_owned())?;
    let probe = parent.join(format!(
        ".playbridge-update-write-test-{}",
        std::process::id()
    ));
    OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(&probe)
        .map_err(|_| {
            format!(
                "The install directory is not writable. {}",
                manual_install_hint()
            )
        })?;
    let _ = fs::remove_file(probe);
    Ok(executable)
}

pub async fn prepare(
    update: &AvailableUpdate,
    progress: mpsc::UnboundedSender<InstallProgress>,
) -> Result<PreparedUpdate, String> {
    let executable = preflight()?;
    let refreshed = update::check_for_update(true).await?;
    let refreshed = refreshed.ok_or_else(|| "The update is no longer available".to_owned())?;
    if refreshed.version != update.version
        || refreshed.manifest.asset.url != update.manifest.asset.url
        || !refreshed
            .manifest
            .asset
            .sha256
            .eq_ignore_ascii_case(&update.manifest.asset.sha256)
    {
        return Err(
            "The available release changed; review the new update before installing".into(),
        );
    }

    let temp = tempfile::Builder::new()
        .prefix("playbridge-cli-update-")
        .tempdir()
        .map_err(|error| format!("Could not create update staging directory: {error}"))?;
    let archive_path = temp.path().join(&update.manifest.asset.name);
    download_and_verify(update, &archive_path, progress).await?;

    let parent = executable
        .parent()
        .ok_or_else(|| "The executable has no install directory".to_owned())?;
    let staged_binary = parent.join(format!(
        ".playbridge-update-{}-{}{}",
        update.version,
        std::process::id(),
        if cfg!(windows) { ".exe" } else { "" }
    ));
    let backup_binary = parent.join(format!(
        ".playbridge-backup-{}-{}{}",
        update.version,
        std::process::id(),
        if cfg!(windows) { ".exe" } else { "" }
    ));
    if staged_binary.exists() || backup_binary.exists() {
        return Err("Conflicting update files already exist; retry the update".into());
    }
    let marker_path = update::state_directory()
        .ok_or_else(|| "Home directory is unavailable".to_owned())?
        .join("update-pending.json");
    if let Some(parent) = marker_path.parent() {
        fs::create_dir_all(parent)
            .map_err(|error| format!("Could not create update state directory: {error}"))?;
    }
    let pending = PendingUpdate {
        target_version: update.version.to_string(),
        backup_binary: backup_binary.clone(),
        staged_binary: staged_binary.clone(),
    };
    let pending_bytes = serde_json::to_vec(&pending)
        .map_err(|error| format!("Could not encode update handoff state: {error}"))?;
    if let Err(error) = extract_binary(&archive_path, &staged_binary)
        .and_then(|_| copy_permissions(&executable, &staged_binary))
    {
        let _ = fs::remove_file(&staged_binary);
        return Err(error);
    }
    if let Err(error) = fs::write(&marker_path, pending_bytes) {
        let _ = fs::remove_file(&staged_binary);
        return Err(format!("Could not save update handoff state: {error}"));
    }

    let staging_root = temp.keep();
    Ok(PreparedUpdate {
        executable,
        staged_binary,
        backup_binary,
        archive_path: staging_root.join(&update.manifest.asset.name),
        staging_root,
        marker_path,
        cleanup_armed: true,
    })
}

async fn download_and_verify(
    update: &AvailableUpdate,
    destination: &Path,
    progress: mpsc::UnboundedSender<InstallProgress>,
) -> Result<(), String> {
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(120))
        .user_agent(concat!(
            "playbridge-cli-updater/",
            env!("CARGO_PKG_VERSION")
        ))
        .build()
        .map_err(|error| format!("Could not create update client: {error}"))?;
    let response = client
        .get(&update.manifest.asset.url)
        .send()
        .await
        .map_err(|error| format!("Update download failed: {error}"))?
        .error_for_status()
        .map_err(|error| format!("Update download failed: {error}"))?;
    let total = response.content_length().or(update.manifest.asset.size);
    let mut stream = response.bytes_stream();
    let mut file = File::create(destination)
        .map_err(|error| format!("Could not create update archive: {error}"))?;
    let mut hasher = Sha256::new();
    let mut downloaded = 0_u64;
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|error| format!("Update download failed: {error}"))?;
        downloaded = downloaded.saturating_add(chunk.len() as u64);
        if downloaded > update::MAX_UPDATE_ARCHIVE_BYTES {
            return Err("Downloaded update exceeds the maximum archive size".into());
        }
        hasher.update(&chunk);
        file.write_all(&chunk)
            .map_err(|error| format!("Could not write update archive: {error}"))?;
        let _ = progress.send(InstallProgress { downloaded, total });
    }
    file.flush()
        .map_err(|error| format!("Could not finish update archive: {error}"))?;
    let actual = format!("{:x}", hasher.finalize());
    if !actual.eq_ignore_ascii_case(&update.manifest.asset.sha256) {
        return Err("Downloaded update failed SHA-256 verification".into());
    }
    Ok(())
}

fn extract_binary(archive_path: &Path, destination: &Path) -> Result<(), String> {
    let archive_file = File::open(archive_path)
        .map_err(|error| format!("Could not open update archive: {error}"))?;
    let mut archive = Archive::new(GzDecoder::new(archive_file));
    let expected = if cfg!(windows) {
        Path::new("playbridge.exe")
    } else {
        Path::new("playbridge")
    };
    let mut found = false;
    for entry in archive
        .entries()
        .map_err(|error| format!("Could not read update archive: {error}"))?
    {
        let mut entry = entry.map_err(|error| format!("Invalid update archive: {error}"))?;
        let path = entry
            .path()
            .map_err(|error| format!("Invalid update archive path: {error}"))?;
        if path.components().any(|component| {
            matches!(
                component,
                Component::ParentDir | Component::RootDir | Component::Prefix(_)
            )
        }) {
            return Err("Update archive contains an unsafe path".into());
        }
        if path.as_ref() != expected {
            continue;
        }
        if !entry.header().entry_type().is_file() {
            return Err("Update archive CLI entry is not a regular file".into());
        }
        if found {
            return Err("Update archive contains duplicate CLI binaries".into());
        }
        let mut output = OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(destination)
            .map_err(|error| format!("Could not stage updated binary: {error}"))?;
        std::io::copy(&mut entry, &mut output)
            .map_err(|error| format!("Could not extract updated binary: {error}"))?;
        output
            .flush()
            .map_err(|error| format!("Could not finish updated binary: {error}"))?;
        found = true;
    }
    if !found {
        return Err(format!(
            "Update archive does not contain {}",
            expected.display()
        ));
    }
    Ok(())
}

fn copy_permissions(source: &Path, destination: &Path) -> Result<(), String> {
    let permissions = fs::metadata(source)
        .map_err(|error| format!("Could not read executable permissions: {error}"))?
        .permissions();
    fs::set_permissions(destination, permissions)
        .map_err(|error| format!("Could not set updated executable permissions: {error}"))
}

pub fn handoff(mut prepared: PreparedUpdate) -> Result<(), String> {
    let result = if cfg!(windows) {
        handoff_windows(&prepared)
    } else {
        handoff_posix(&prepared)
    };
    if result.is_ok() {
        prepared.cleanup_armed = false;
    }
    result
}

fn handoff_posix(prepared: &PreparedUpdate) -> Result<(), String> {
    let script = prepared.staging_root.join("swap.sh");
    fs::write(
        &script,
        r#"#!/bin/sh
pid="$1"
current="$2"
staged="$3"
backup="$4"
archive="$5"
root="$6"
script="$7"
i=0
while kill -0 "$pid" 2>/dev/null && [ "$i" -lt 120 ]; do
  sleep 0.5
  i=$((i + 1))
done
if mv "$current" "$backup" && mv "$staged" "$current"; then
  chmod +x "$current"
  nohup "$current" >/dev/null 2>&1 &
else
  [ -f "$backup" ] && mv "$backup" "$current"
  nohup "$current" >/dev/null 2>&1 &
fi
rm -f "$archive" "$script"
rmdir "$root" 2>/dev/null || true
"#,
    )
    .map_err(|error| format!("Could not write update helper: {error}"))?;
    let mut command = Command::new("/bin/sh");
    command
        .arg(&script)
        .arg(std::process::id().to_string())
        .arg(&prepared.executable)
        .arg(&prepared.staged_binary)
        .arg(&prepared.backup_binary)
        .arg(&prepared.archive_path)
        .arg(&prepared.staging_root)
        .arg(&script)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    command
        .spawn()
        .map_err(|error| format!("Could not launch update helper: {error}"))?;
    Ok(())
}

#[cfg(windows)]
fn handoff_windows(prepared: &PreparedUpdate) -> Result<(), String> {
    use std::os::windows::process::CommandExt;

    let script = prepared.staging_root.join("swap.ps1");
    fs::write(
        &script,
        r#"param(
  [int]$TargetPid,
  [string]$Current,
  [string]$Staged,
  [string]$Backup,
  [string]$Archive,
  [string]$Root
)
try { Wait-Process -Id $TargetPid -Timeout 60 -ErrorAction SilentlyContinue } catch {}
try {
  Move-Item -LiteralPath $Current -Destination $Backup -Force
  Move-Item -LiteralPath $Staged -Destination $Current -Force
  Start-Process -FilePath $Current
} catch {
  if (Test-Path -LiteralPath $Backup) {
    Move-Item -LiteralPath $Backup -Destination $Current -Force
    Start-Process -FilePath $Current
  }
}
Remove-Item -LiteralPath $Archive -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $PSCommandPath -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $Root -Force -ErrorAction SilentlyContinue
"#,
    )
    .map_err(|error| format!("Could not write update helper: {error}"))?;
    Command::new("powershell.exe")
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
        ])
        .arg(&script)
        .arg("-TargetPid")
        .arg(std::process::id().to_string())
        .arg("-Current")
        .arg(&prepared.executable)
        .arg("-Staged")
        .arg(&prepared.staged_binary)
        .arg("-Backup")
        .arg(&prepared.backup_binary)
        .arg("-Archive")
        .arg(&prepared.archive_path)
        .arg("-Root")
        .arg(&prepared.staging_root)
        .creation_flags(0x08000000)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|error| format!("Could not launch update helper: {error}"))?;
    Ok(())
}

#[cfg(not(windows))]
fn handoff_windows(_prepared: &PreparedUpdate) -> Result<(), String> {
    Err("Windows update helper is unavailable on this platform".into())
}

pub fn take_restart_notice() -> Option<Result<String, String>> {
    let marker_path = update::state_directory()?.join("update-pending.json");
    let bytes = fs::read(&marker_path).ok()?;
    let pending: PendingUpdate = match serde_json::from_slice(&bytes) {
        Ok(pending) => pending,
        Err(_) => {
            let _ = fs::remove_file(&marker_path);
            return Some(Err(
                "A previous CLI update left invalid handoff state; retry the update".into(),
            ));
        }
    };
    let current = env!("CARGO_PKG_VERSION");
    let _ = fs::remove_file(&marker_path);
    let _ = fs::remove_file(&pending.staged_binary);
    if current == pending.target_version {
        let _ = fs::remove_file(&pending.backup_binary);
        Some(Ok(format!(
            "PlayBridge CLI updated successfully to v{}",
            pending.target_version
        )))
    } else {
        Some(Err(format!(
            "The update to v{} did not complete; the previous CLI is still running",
            pending.target_version
        )))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn manual_install_hint_uses_the_playbridge_distribution_endpoint() {
        let hint = manual_install_hint();
        assert!(hint.contains("https://playbridge.app/install."));
        assert!(!hint.contains("raw.githubusercontent.com"));
    }

    #[test]
    fn archive_requires_the_expected_binary() {
        let temp = tempfile::tempdir().unwrap();
        let valid = temp.path().join("valid.tar.gz");
        write_archive(&valid, "playbridge", b"binary");
        let output = temp.path().join("output");
        extract_binary(&valid, &output).unwrap();
        assert_eq!(fs::read(output).unwrap(), b"binary");

        let missing_archive = temp.path().join("missing.tar.gz");
        write_archive(&missing_archive, "LICENSE", b"license");
        assert!(extract_binary(&missing_archive, &temp.path().join("missing")).is_err());
    }

    #[test]
    fn dropping_an_unhanded_update_cleans_staged_state() {
        let staging = tempfile::tempdir().unwrap();
        let staging_root = staging.keep();
        let archive_path = staging_root.join("archive.tar.gz");
        fs::write(&archive_path, b"archive").unwrap();
        let install = tempfile::tempdir().unwrap();
        let staged_binary = install.path().join("staged");
        let marker_path = install.path().join("marker.json");
        fs::write(&staged_binary, b"binary").unwrap();
        fs::write(&marker_path, b"marker").unwrap();

        drop(PreparedUpdate {
            executable: install.path().join("playbridge"),
            staged_binary: staged_binary.clone(),
            backup_binary: install.path().join("backup"),
            staging_root: staging_root.clone(),
            archive_path,
            marker_path: marker_path.clone(),
            cleanup_armed: true,
        });

        assert!(!staged_binary.exists());
        assert!(!marker_path.exists());
        assert!(!staging_root.exists());
    }

    fn write_archive(path: &Path, name: &str, contents: &[u8]) {
        use flate2::{Compression, write::GzEncoder};
        let file = File::create(path).unwrap();
        let encoder = GzEncoder::new(file, Compression::default());
        let mut builder = tar::Builder::new(encoder);
        let mut header = tar::Header::new_gnu();
        header.set_size(contents.len() as u64);
        header.set_mode(0o755);
        header.set_cksum();
        builder.append_data(&mut header, name, contents).unwrap();
        builder.finish().unwrap();
    }
}
