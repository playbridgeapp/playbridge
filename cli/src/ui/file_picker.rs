use std::{fs, path::PathBuf, time::SystemTime};

#[derive(Debug, Clone)]
pub(crate) struct FileEntry {
    pub(crate) path: PathBuf,
    pub(crate) name: String,
    pub(crate) is_dir: bool,
    pub(crate) size: u64,
    pub(crate) modified: Option<SystemTime>,
}

#[derive(Debug, Clone)]
pub(crate) struct FilePicker {
    pub(crate) directory: PathBuf,
    pub(crate) entries: Vec<FileEntry>,
    pub(crate) selected: usize,
    pub(crate) query: String,
    pub(crate) filtering: bool,
    pub(crate) media_only: bool,
    pub(crate) show_hidden: bool,
}

impl FilePicker {
    pub(crate) fn new() -> Self {
        let directory = std::env::current_dir().unwrap_or_else(|_| home_dir());
        let mut picker = Self {
            directory,
            entries: Vec::new(),
            selected: 0,
            query: String::new(),
            filtering: false,
            media_only: true,
            show_hidden: false,
        };
        let _ = picker.refresh();
        picker
    }

    pub(crate) fn refresh(&mut self) -> Result<(), String> {
        let mut entries = fs::read_dir(&self.directory)
            .map_err(|error| format!("could not read {}: {error}", self.directory.display()))?
            .filter_map(Result::ok)
            .filter_map(|entry| {
                let name = entry.file_name().to_string_lossy().into_owned();
                if !self.show_hidden && name.starts_with('.') {
                    return None;
                }
                let metadata = entry.metadata().ok()?;
                let is_dir = metadata.is_dir();
                if !is_dir && self.media_only && !is_media_name(&name) {
                    return None;
                }
                if !self.query.is_empty()
                    && !fuzzy_matches(&name.to_ascii_lowercase(), &self.query.to_ascii_lowercase())
                {
                    return None;
                }
                Some(FileEntry {
                    path: entry.path(),
                    name,
                    is_dir,
                    size: metadata.len(),
                    modified: metadata.modified().ok(),
                })
            })
            .collect::<Vec<_>>();
        entries.sort_by(|left, right| {
            right
                .is_dir
                .cmp(&left.is_dir)
                .then_with(|| left.name.to_lowercase().cmp(&right.name.to_lowercase()))
        });
        self.entries = entries;
        self.selected = self.selected.min(self.entries.len().saturating_sub(1));
        Ok(())
    }

    pub(crate) fn move_down(&mut self) {
        if self.selected + 1 < self.entries.len() {
            self.selected += 1;
        }
    }

    pub(crate) fn move_up(&mut self) {
        self.selected = self.selected.saturating_sub(1);
    }

    pub(crate) fn enter(&mut self) -> Result<Option<PathBuf>, String> {
        let Some(entry) = self.entries.get(self.selected).cloned() else {
            return Ok(None);
        };
        if entry.is_dir {
            self.directory = entry.path;
            self.selected = 0;
            self.query.clear();
            self.refresh()?;
            Ok(None)
        } else {
            Ok(Some(entry.path))
        }
    }

    pub(crate) fn parent(&mut self) -> Result<(), String> {
        if let Some(parent) = self.directory.parent() {
            self.directory = parent.to_path_buf();
            self.selected = 0;
            self.query.clear();
            self.refresh()?;
        }
        Ok(())
    }

    pub(crate) fn selected_entry(&self) -> Option<&FileEntry> {
        self.entries.get(self.selected)
    }
}

fn home_dir() -> PathBuf {
    std::env::var_os("HOME")
        .or_else(|| std::env::var_os("USERPROFILE"))
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
}

fn is_media_name(name: &str) -> bool {
    let extension = name
        .rsplit_once('.')
        .map(|(_, extension)| extension.to_ascii_lowercase());
    matches!(
        extension.as_deref(),
        Some(
            "mp4"
                | "m4v"
                | "mkv"
                | "webm"
                | "mov"
                | "avi"
                | "ts"
                | "m2ts"
                | "mp3"
                | "m4a"
                | "aac"
                | "flac"
                | "wav"
                | "ogg"
                | "m3u8"
                | "mpd"
        )
    )
}

fn fuzzy_matches(value: &str, query: &str) -> bool {
    let mut query = query.chars();
    let Some(mut expected) = query.next() else {
        return true;
    };
    for character in value.chars() {
        if character == expected {
            let Some(next) = query.next() else {
                return true;
            };
            expected = next;
        }
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn media_filter_is_case_insensitive() {
        assert!(is_media_name("Movie.MKV"));
        assert!(is_media_name("stream.m3u8"));
        assert!(!is_media_name("notes.txt"));
    }

    #[test]
    fn fuzzy_filter_matches_in_order() {
        assert!(fuzzy_matches("holiday-video.mp4", "hvm"));
        assert!(!fuzzy_matches("holiday-video.mp4", "vmh"));
    }
}
