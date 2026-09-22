use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{
    fs::{self, OpenOptions},
    io::Write,
    path::PathBuf,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

const STALE_AFTER: Duration = Duration::from_secs(4);
const ACK_TIMEOUT: Duration = Duration::from_secs(12);

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SessionInfo {
    pub session_id: String,
    pub pid: u32,
    pub device: String,
    pub protocol: String,
    pub id: String,
    pub media: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ControlRequest {
    pub id: String,
    pub command: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub seconds: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub delta: Option<f32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub value: Option<f32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub action: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub payload: Option<Value>,
}

pub struct JsonCastSession {
    root: PathBuf,
    dir: PathBuf,
    id: String,
}

impl JsonCastSession {
    pub(crate) fn root_path() -> Option<PathBuf> {
        let home = std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE"))?;
        let mut path = PathBuf::from(home);
        path.push(".config");
        path.push("playbridge");
        path.push("sessions");
        Some(path)
    }

    pub fn generate_id() -> String {
        new_request_id()
    }

    pub fn pair_code_path(session_id: &str) -> Result<PathBuf, String> {
        Ok(Self::session_dir(session_id)?.join("pair-code"))
    }

    pub fn write_pair_code(session_id: &str, code: &str) -> Result<PathBuf, String> {
        let path = Self::pair_code_path(session_id)?;
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|error| error.to_string())?;
            restrict_directory(parent)?;
        }
        write_sensitive_atomic(&path, &format!("{code}\n"))?;
        Ok(path)
    }

    pub fn cleanup(session_id: &str) {
        let Some(root) = Self::root_path() else {
            return;
        };
        let Ok(dir) = Self::session_dir(session_id) else {
            return;
        };
        cleanup_paths(&root, &dir, session_id);
    }

    pub fn claim(session_id: &str) -> Result<Self, String> {
        let root = Self::root_path().ok_or("could not determine home directory")?;
        let dir = Self::session_dir(session_id)?;
        fs::create_dir_all(&dir).map_err(|error| error.to_string())?;
        restrict_directory(&root)?;
        restrict_directory(&dir)?;
        let lock_path = dir.join("owner.lock");
        let mut lock = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&lock_path)
            .map_err(|error| format!("session {session_id} is already owned: {error}"))?;
        writeln!(lock, "{}", std::process::id()).map_err(|error| error.to_string())?;
        Ok(Self {
            root,
            dir,
            id: session_id.to_owned(),
        })
    }

    pub fn id(&self) -> &str {
        &self.id
    }

    pub fn activate(&self, info: &SessionInfo) -> Result<(), String> {
        write_atomic(
            &self.session_path(),
            &serde_json::to_string_pretty(info).map_err(|error| error.to_string())?,
        )?;
        write_atomic(&self.root.join("active-session"), &info.session_id)
    }

    pub fn read_status(session_id: Option<&str>) -> Result<Value, String> {
        let dir = Self::resolve_session_dir(session_id)?;
        let path = dir.join("status.json");
        let data = fs::read_to_string(&path).map_err(|_| "no_active_session".to_owned())?;
        let value: Value = serde_json::from_str(&data).map_err(|error| error.to_string())?;
        if status_is_stale(&value) {
            return Err("no_active_session".into());
        }
        Ok(value)
    }

    pub fn write_status(&self, status: &Value) -> Result<(), String> {
        write_atomic(
            &self.status_path(),
            &serde_json::to_string_pretty(status).map_err(|error| error.to_string())?,
        )
    }

    pub fn take_request(&self, last_id: &str) -> Option<ControlRequest> {
        let data = fs::read_to_string(self.control_path()).ok()?;
        let request: ControlRequest = serde_json::from_str(&data).ok()?;
        if request.id == last_id {
            return None;
        }
        Some(request)
    }

    pub fn write_ack(&self, ack: &Value) -> Result<(), String> {
        write_atomic(
            &self.ack_path(),
            &serde_json::to_string_pretty(ack).map_err(|error| error.to_string())?,
        )
    }

    pub async fn submit(
        session_id: Option<&str>,
        request: ControlRequest,
    ) -> Result<Value, String> {
        let dir = Self::resolve_session_dir(session_id)?;
        let status_path = dir.join("status.json");
        if !status_path.exists() {
            return Err("no_active_session".into());
        }
        let status: Value = serde_json::from_str(
            &fs::read_to_string(&status_path).map_err(|_| "no_active_session".to_owned())?,
        )
        .map_err(|error| error.to_string())?;
        if status_is_stale(&status) {
            return Err("no_active_session".into());
        }
        write_sensitive_atomic(
            &dir.join("control.json"),
            &serde_json::to_string_pretty(&request).map_err(|error| error.to_string())?,
        )?;
        let deadline = tokio::time::Instant::now() + ACK_TIMEOUT;
        let ack_path = dir.join("ack.json");
        while tokio::time::Instant::now() < deadline {
            if let Ok(data) = fs::read_to_string(&ack_path)
                && let Ok(ack) = serde_json::from_str::<Value>(&data)
                && ack.get("request_id").and_then(Value::as_str) == Some(request.id.as_str())
            {
                return Ok(ack);
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        Err("control_timeout".into())
    }

    pub fn clear(&self) {
        cleanup_paths(&self.root, &self.dir, &self.id);
    }

    fn session_path(&self) -> PathBuf {
        self.dir.join("session.json")
    }

    fn status_path(&self) -> PathBuf {
        self.dir.join("status.json")
    }

    fn control_path(&self) -> PathBuf {
        self.dir.join("control.json")
    }

    fn ack_path(&self) -> PathBuf {
        self.dir.join("ack.json")
    }

    fn session_dir(session_id: &str) -> Result<PathBuf, String> {
        validate_session_id(session_id)?;
        Ok(Self::root_path()
            .ok_or("could not determine home directory")?
            .join(session_id))
    }

    fn resolve_session_dir(session_id: Option<&str>) -> Result<PathBuf, String> {
        if let Some(session_id) = session_id {
            return Self::session_dir(session_id);
        }
        let root = Self::root_path().ok_or("could not determine home directory")?;
        let active = fs::read_to_string(root.join("active-session"))
            .map_err(|_| "no_active_session".to_owned())?;
        Self::session_dir(active.trim())
    }
}

fn cleanup_paths(root: &std::path::Path, dir: &std::path::Path, session_id: &str) {
    for name in [
        "session.json",
        "status.json",
        "control.json",
        "ack.json",
        "pair-code",
        "media-payload.json",
        "owner.lock",
    ] {
        let _ = fs::remove_file(dir.join(name));
    }
    if let Ok(entries) = fs::read_dir(dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path
                .file_name()
                .and_then(|name| name.to_str())
                .is_some_and(|name| name.ends_with(".tmp"))
            {
                let _ = fs::remove_file(path);
            }
        }
    }
    if fs::read_to_string(root.join("active-session"))
        .ok()
        .is_some_and(|active| active.trim() == session_id)
    {
        let _ = fs::remove_file(root.join("active-session"));
    }
    let _ = fs::remove_dir(dir);
}

impl Drop for JsonCastSession {
    fn drop(&mut self) {
        self.clear();
    }
}

fn validate_session_id(session_id: &str) -> Result<(), String> {
    if session_id.is_empty()
        || session_id.len() > 64
        || !session_id
            .chars()
            .all(|ch| ch.is_ascii_alphanumeric() || ch == '-' || ch == '_')
    {
        return Err("invalid_session_id".into());
    }
    Ok(())
}

#[cfg(unix)]
fn restrict_directory(path: &std::path::Path) -> Result<(), String> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o700)).map_err(|error| error.to_string())
}

#[cfg(not(unix))]
fn restrict_directory(_path: &std::path::Path) -> Result<(), String> {
    Ok(())
}

#[cfg(unix)]
fn restrict_file(path: &std::path::Path) -> Result<(), String> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o600)).map_err(|error| error.to_string())
}

#[cfg(not(unix))]
fn restrict_file(_path: &std::path::Path) -> Result<(), String> {
    Ok(())
}

pub fn new_request_id() -> String {
    let mut bytes = [0_u8; 8];
    let _ = getrandom::fill(&mut bytes);
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}

fn status_is_stale(status: &Value) -> bool {
    let Some(updated) = status.get("updated_ms").and_then(Value::as_u64) else {
        return true;
    };
    now_ms().saturating_sub(updated) > STALE_AFTER.as_millis() as u64
}

fn write_atomic(path: &PathBuf, contents: &str) -> Result<(), String> {
    let temporary = path.with_extension(format!("{}.tmp", new_request_id()));
    fs::write(&temporary, contents).map_err(|error| error.to_string())?;
    if let Err(first_error) = fs::rename(&temporary, path) {
        if path.exists() {
            fs::remove_file(path).map_err(|error| error.to_string())?;
            fs::rename(&temporary, path).map_err(|error| error.to_string())?;
        } else {
            let _ = fs::remove_file(&temporary);
            return Err(first_error.to_string());
        }
    }
    Ok(())
}

fn write_sensitive_atomic(path: &PathBuf, contents: &str) -> Result<(), String> {
    let temporary = path.with_extension(format!("{}.tmp", new_request_id()));
    let result = (|| {
        let mut options = OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options
            .open(&temporary)
            .map_err(|error| error.to_string())?;
        file.write_all(contents.as_bytes())
            .map_err(|error| error.to_string())?;
        file.sync_all().map_err(|error| error.to_string())?;
        if let Err(first_error) = fs::rename(&temporary, path) {
            if path.exists() {
                fs::remove_file(path).map_err(|error| error.to_string())?;
                fs::rename(&temporary, path).map_err(|error| error.to_string())?;
            } else {
                return Err(first_error.to_string());
            }
        }
        restrict_file(path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

pub fn parse_control_args(
    arguments: &[String],
) -> Result<(Option<String>, ControlRequest), String> {
    let mut command = None;
    let mut value: Option<String> = None;
    let mut session_id = None;
    let mut index = 0;
    while index < arguments.len() {
        match arguments[index].as_str() {
            "--json" | "--help" | "-h" | "--" => {}
            "--session-id" => {
                index += 1;
                session_id = Some(
                    arguments
                        .get(index)
                        .ok_or("--session-id requires a value")?
                        .clone(),
                );
            }
            flag if flag.starts_with("--session-id=") => {
                session_id = Some(flag["--session-id=".len()..].to_owned());
            }
            flag if command.is_none() && flag.starts_with('-') => {
                return Err(format!("unknown control option: {flag}"));
            }
            token => {
                if command.is_none() {
                    command = Some(token.to_ascii_lowercase());
                } else if value.is_none() {
                    value = Some(token.to_owned());
                } else {
                    return Err("control accepts a single command and optional value".into());
                }
            }
        }
        index += 1;
    }
    let command = command.ok_or_else(|| "missing control command".to_owned())?;
    let request = match command.as_str() {
        "pause" | "play" | "toggle" | "stop" | "mute" | "loop" | "audio_boost" => ControlRequest {
            id: new_request_id(),
            command,
            seconds: None,
            delta: None,
            value: None,
            action: None,
            payload: None,
        },
        "seek" => {
            let seconds = value
                .ok_or_else(|| {
                    "control seek requires seconds, e.g. seek 10 or seek -- -10".to_owned()
                })?
                .parse::<i64>()
                .map_err(|_| "control seek seconds must be an integer".to_owned())?;
            ControlRequest {
                id: new_request_id(),
                command,
                seconds: Some(seconds),
                delta: None,
                value: None,
                action: None,
                payload: None,
            }
        }
        "volume" => {
            let delta = value
                .ok_or_else(|| "control volume requires a delta, e.g. volume 0.1".to_owned())?
                .parse::<f32>()
                .map_err(|_| "control volume delta must be a number".to_owned())?;
            if !delta.is_finite() || !(-1.0..=1.0).contains(&delta) {
                return Err("control volume delta must be between -1 and 1".into());
            }
            ControlRequest {
                id: new_request_id(),
                command,
                seconds: None,
                delta: Some(delta),
                value: None,
                action: None,
                payload: None,
            }
        }
        "speed" => {
            let speed = value
                .ok_or_else(|| "control speed requires a value, e.g. speed 1.5".to_owned())?
                .parse::<f32>()
                .map_err(|_| "control speed must be a number".to_owned())?;
            if !speed.is_finite() || !(0.25..=4.0).contains(&speed) {
                return Err("control speed must be between 0.25 and 4".into());
            }
            ControlRequest {
                id: new_request_id(),
                command,
                seconds: None,
                delta: None,
                value: Some(speed),
                action: None,
                payload: None,
            }
        }
        other => return Err(format!("unknown control command: {other}")),
    };
    if let Some(id) = session_id.as_deref() {
        validate_session_id(id)?;
    }
    Ok((session_id, request))
}

pub fn parse_status_args(arguments: &[String]) -> Result<Option<String>, String> {
    let mut session_id = None;
    let mut index = 0;
    while index < arguments.len() {
        match arguments[index].as_str() {
            "--json" | "--help" | "-h" => {}
            "--session-id" => {
                index += 1;
                session_id = Some(
                    arguments
                        .get(index)
                        .ok_or("--session-id requires a value")?
                        .clone(),
                );
            }
            value if value.starts_with("--session-id=") => {
                session_id = Some(value["--session-id=".len()..].to_owned());
            }
            other => return Err(format!("unknown status option: {other}")),
        }
        index += 1;
    }
    if let Some(id) = session_id.as_deref() {
        validate_session_id(id)?;
    }
    Ok(session_id)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn parse_control_args_reads_commands_and_values() {
        let (_, pause) = parse_control_args(&["pause".into(), "--json".into()]).unwrap();
        assert_eq!(pause.command, "pause");
        let (_, seek) = parse_control_args(&["seek".into(), "--".into(), "-10".into()]).unwrap();
        assert_eq!(seek.command, "seek");
        assert_eq!(seek.seconds, Some(-10));
        let (_, volume) = parse_control_args(&["volume".into(), "0.1".into()]).unwrap();
        assert_eq!(volume.delta, Some(0.1));
        assert_eq!(
            parse_control_args(&["audio_boost".into()])
                .unwrap()
                .1
                .command,
            "audio_boost"
        );
        assert!(parse_control_args(&["volume".into(), "2".into()]).is_err());
        assert!(parse_control_args(&["speed".into(), "NaN".into()]).is_err());
        assert!(parse_control_args(&[]).is_err());
        assert!(parse_control_args(&["rewind".into()]).is_err());
    }

    #[test]
    fn session_ids_are_scoped_and_cannot_escape_the_session_root() {
        assert!(JsonCastSession::pair_code_path("agent-123").is_ok());
        assert!(JsonCastSession::pair_code_path("../other").is_err());
        assert!(parse_status_args(&["--session-id=agent-123".into()]).is_ok());
        assert!(parse_status_args(&["--session-id=../other".into()]).is_err());
    }

    #[test]
    fn clearing_an_old_session_preserves_the_new_active_pointer() {
        let temp = TempDir::new().unwrap();
        let root = temp.path().to_path_buf();
        let old_dir = root.join("old");
        fs::create_dir_all(&old_dir).unwrap();
        fs::write(root.join("active-session"), "new").unwrap();
        let old = JsonCastSession {
            root: root.clone(),
            dir: old_dir,
            id: "old".into(),
        };

        old.clear();

        assert_eq!(
            fs::read_to_string(root.join("active-session")).unwrap(),
            "new"
        );
    }

    #[test]
    fn session_files_are_isolated() {
        let temp = TempDir::new().unwrap();
        let root = temp.path().to_path_buf();
        let first_dir = root.join("first");
        let second_dir = root.join("second");
        fs::create_dir_all(&first_dir).unwrap();
        fs::create_dir_all(&second_dir).unwrap();
        let first = JsonCastSession {
            root: root.clone(),
            dir: first_dir,
            id: "first".into(),
        };
        let second = JsonCastSession {
            root,
            dir: second_dir,
            id: "second".into(),
        };

        first
            .write_status(&serde_json::json!({"updated_ms": now_ms(), "device": "one"}))
            .unwrap();
        second
            .write_status(&serde_json::json!({"updated_ms": now_ms(), "device": "two"}))
            .unwrap();

        assert_eq!(
            serde_json::from_str::<Value>(&fs::read_to_string(first.status_path()).unwrap())
                .unwrap()["device"],
            "one"
        );
        assert_eq!(
            serde_json::from_str::<Value>(&fs::read_to_string(second.status_path()).unwrap())
                .unwrap()["device"],
            "two"
        );
    }

    #[test]
    fn sensitive_payload_files_are_private_at_creation() {
        let temp = TempDir::new().unwrap();
        let path = temp.path().join("media-payload.json");
        write_sensitive_atomic(&path, r#"{"headers":{"Authorization":"secret"}}"#).unwrap();
        write_sensitive_atomic(&path, r#"{"headers":{"Cookie":"new-secret"}}"#).unwrap();
        assert!(fs::read_to_string(&path).unwrap().contains("secret"));

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                fs::metadata(path).unwrap().permissions().mode() & 0o777,
                0o600
            );
        }
    }

    #[test]
    fn sensitive_atomic_write_removes_temporary_file_after_failure() {
        let temp = TempDir::new().unwrap();
        let path = temp.path().join("control.json");
        fs::create_dir(&path).unwrap();

        assert!(write_sensitive_atomic(&path, r#"{"pairCode":"secret"}"#).is_err());
        assert!(
            fs::read_dir(temp.path())
                .unwrap()
                .flatten()
                .all(|entry| !entry.file_name().to_string_lossy().ends_with(".tmp"))
        );
    }
}
