use std::{collections::BTreeMap, fs, io::Write, path::PathBuf};

use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default)]
pub(crate) struct UiPreferences {
    pub(crate) theme: String,
    pub(crate) mouse: bool,
    pub(crate) unicode: bool,
}

impl Default for UiPreferences {
    fn default() -> Self {
        Self {
            theme: "playbridge-dark".into(),
            mouse: true,
            unicode: true,
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default)]
pub(crate) struct ThemeOverrides {
    pub(crate) background: Option<String>,
    pub(crate) panel: Option<String>,
    pub(crate) text: Option<String>,
    pub(crate) muted: Option<String>,
    pub(crate) accent: Option<String>,
    pub(crate) selection: Option<String>,
    pub(crate) success: Option<String>,
    pub(crate) warning: Option<String>,
    pub(crate) error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default)]
pub(crate) struct UiConfig {
    pub(crate) ui: UiPreferences,
    pub(crate) theme: ThemeOverrides,
    pub(crate) keys: BTreeMap<String, Vec<String>>,
}

impl Default for UiConfig {
    fn default() -> Self {
        Self {
            ui: UiPreferences::default(),
            theme: ThemeOverrides::default(),
            keys: default_keys(),
        }
    }
}

impl UiConfig {
    pub(crate) fn load(theme_override: Option<&str>) -> Result<Self, String> {
        let path = config_path().ok_or("could not determine the PlayBridge config path")?;
        let mut config = if path.exists() {
            Self::load_from(&path)?
        } else {
            Self::default()
        };
        if let Some(theme) = theme_override {
            config.ui.theme = theme.to_owned();
        }
        config.validate()?;
        Ok(config)
    }

    pub(crate) fn load_from(path: &std::path::Path) -> Result<Self, String> {
        let text = fs::read_to_string(path)
            .map_err(|error| format!("could not read {}: {error}", path.display()))?;
        let parsed = toml::from_str::<Self>(&text)
            .map_err(|error| format!("invalid UI config {}: {error}", path.display()))?;

        let mut config = Self {
            ui: parsed.ui,
            theme: parsed.theme,
            ..Self::default()
        };
        config.keys.extend(parsed.keys);
        config.validate()?;
        Ok(config)
    }

    pub(crate) fn save(&self) -> Result<(), String> {
        let path = config_path().ok_or("could not determine the PlayBridge config path")?;
        let parent = path.parent().ok_or("invalid PlayBridge config path")?;
        fs::create_dir_all(parent).map_err(|error| error.to_string())?;
        let mut file =
            tempfile::NamedTempFile::new_in(parent).map_err(|error| error.to_string())?;
        file.write_all(
            toml::to_string_pretty(self)
                .map_err(|error| error.to_string())?
                .as_bytes(),
        )
        .map_err(|error| error.to_string())?;
        file.as_file()
            .sync_all()
            .map_err(|error| error.to_string())?;
        file.persist(&path)
            .map_err(|error| error.error.to_string())?;
        Ok(())
    }

    pub(crate) fn validate(&self) -> Result<(), String> {
        const VALID_THEMES: &[&str] = &[
            "playbridge-dark",
            "playbridge-light",
            "terminal",
            "monochrome",
        ];
        if !VALID_THEMES.contains(&self.ui.theme.as_str())
            && self.theme == ThemeOverrides::default()
        {
            return Err(format!(
                "unknown theme {:?}; use playbridge-dark, playbridge-light, terminal, or monochrome",
                self.ui.theme
            ));
        }

        let mut assigned = std::collections::HashMap::<String, &str>::new();
        for (action, bindings) in &self.keys {
            for binding in bindings {
                let normalized = binding.trim().to_ascii_lowercase();
                if normalized.is_empty() {
                    return Err(format!("key binding for {action} cannot be empty"));
                }
                if let Some(existing) = assigned.insert(normalized, action) {
                    return Err(format!(
                        "key binding {binding:?} is assigned to both {existing} and {action}"
                    ));
                }
            }
        }
        Ok(())
    }

    pub(crate) fn matches(&self, action: &str, event: KeyEvent) -> bool {
        let Some(bindings) = self.keys.get(action) else {
            return false;
        };
        let key = key_name(event);
        bindings
            .iter()
            .any(|binding| binding.eq_ignore_ascii_case(&key))
    }
}

pub(crate) fn config_path() -> Option<PathBuf> {
    let home = std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE"))?;
    Some(
        PathBuf::from(home)
            .join(".config")
            .join("playbridge")
            .join("config.toml"),
    )
}

fn key_name(event: KeyEvent) -> String {
    let mut parts = Vec::new();
    if event.modifiers.contains(KeyModifiers::CONTROL) {
        parts.push("ctrl".to_owned());
    }
    if event.modifiers.contains(KeyModifiers::ALT) {
        parts.push("alt".to_owned());
    }
    if event.modifiers.contains(KeyModifiers::SHIFT) && !matches!(event.code, KeyCode::Char(_)) {
        parts.push("shift".to_owned());
    }
    let code = match event.code {
        KeyCode::Backspace => "backspace".into(),
        KeyCode::Enter => "enter".into(),
        KeyCode::Left => "left".into(),
        KeyCode::Right => "right".into(),
        KeyCode::Up => "up".into(),
        KeyCode::Down => "down".into(),
        KeyCode::Home => "home".into(),
        KeyCode::End => "end".into(),
        KeyCode::PageUp => "pageup".into(),
        KeyCode::PageDown => "pagedown".into(),
        KeyCode::Tab => "tab".into(),
        KeyCode::BackTab => "shift+tab".into(),
        KeyCode::Delete => "delete".into(),
        KeyCode::Insert => "insert".into(),
        KeyCode::Esc => "esc".into(),
        KeyCode::Char(value) => value.to_lowercase().to_string(),
        KeyCode::F(value) => format!("f{value}"),
        KeyCode::Null
        | KeyCode::CapsLock
        | KeyCode::ScrollLock
        | KeyCode::NumLock
        | KeyCode::PrintScreen
        | KeyCode::Pause
        | KeyCode::Menu
        | KeyCode::KeypadBegin
        | KeyCode::Media(_)
        | KeyCode::Modifier(_) => return String::new(),
    };
    if parts.is_empty() || code.contains('+') {
        code
    } else {
        parts.push(code);
        parts.join("+")
    }
}

fn default_keys() -> BTreeMap<String, Vec<String>> {
    BTreeMap::from([
        ("down".into(), vec!["down".into(), "j".into()]),
        ("up".into(), vec!["up".into(), "k".into()]),
        ("left".into(), vec!["left".into(), "h".into()]),
        ("right".into(), vec!["right".into(), "l".into()]),
        ("select".into(), vec!["enter".into()]),
        ("back".into(), vec!["esc".into(), "backspace".into()]),
        ("help".into(), vec!["?".into(), "f1".into()]),
        ("palette".into(), vec!["ctrl+p".into(), ":".into()]),
        ("rescan".into(), vec!["r".into()]),
        ("preferred".into(), vec!["p".into()]),
        ("quit".into(), vec!["q".into()]),
    ])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn partial_config_keeps_default_keys() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("config.toml");
        fs::write(
            &path,
            "[ui]\ntheme = \"monochrome\"\n[keys]\ndown = [\"n\"]\n",
        )
        .unwrap();
        let config = UiConfig::load_from(&path).unwrap();
        assert_eq!(config.keys["down"], ["n"]);
        assert_eq!(config.keys["up"], ["up", "k"]);
    }

    #[test]
    fn duplicate_bindings_are_rejected() {
        let mut config = UiConfig::default();
        config
            .keys
            .insert("down".into(), vec!["j".into(), "J".into()]);
        assert!(config.validate().is_err());
    }

    #[test]
    fn conflicting_actions_are_rejected() {
        let mut config = UiConfig::default();
        config.keys.insert("quit".into(), vec!["enter".into()]);
        assert!(config.validate().is_err());
    }

    #[test]
    fn shifted_character_bindings_match_the_character() {
        let config = UiConfig::default();
        assert!(config.matches(
            "help",
            KeyEvent::new(KeyCode::Char('?'), KeyModifiers::SHIFT)
        ));
        assert!(config.matches(
            "palette",
            KeyEvent::new(KeyCode::Char(':'), KeyModifiers::SHIFT)
        ));
    }
}
