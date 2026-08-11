use ratatui::style::{Color, Modifier, Style};

use super::config::{ThemeOverrides, UiConfig};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct Theme {
    pub(crate) background: Color,
    pub(crate) panel: Color,
    pub(crate) text: Color,
    pub(crate) muted: Color,
    pub(crate) accent: Color,
    pub(crate) selection: Color,
    pub(crate) success: Color,
    pub(crate) warning: Color,
    pub(crate) error: Color,
}

impl Theme {
    pub(crate) fn from_config(config: &UiConfig) -> Result<Self, String> {
        let no_color = std::env::var_os("NO_COLOR").is_some();
        let mut theme = if no_color || config.ui.theme == "monochrome" {
            Self::monochrome()
        } else {
            match config.ui.theme.as_str() {
                "playbridge-light" => Self::playbridge_light(),
                "terminal" => Self::terminal(),
                "playbridge-dark" => Self::playbridge_dark(),
                _ if config.theme != ThemeOverrides::default() => Self::playbridge_dark(),
                unknown => return Err(format!("unsupported theme: {unknown}")),
            }
        };
        theme.apply(&config.theme)?;
        Ok(theme)
    }

    pub(crate) fn base(self) -> Style {
        Style::default().fg(self.text).bg(self.background)
    }

    pub(crate) fn title(self) -> Style {
        self.base().fg(self.accent).add_modifier(Modifier::BOLD)
    }

    pub(crate) fn muted(self) -> Style {
        self.base().fg(self.muted)
    }

    pub(crate) fn selected(self) -> Style {
        Style::default()
            .fg(self.background)
            .bg(self.selection)
            .add_modifier(Modifier::BOLD)
    }

    fn apply(&mut self, overrides: &ThemeOverrides) -> Result<(), String> {
        for (target, value, name) in [
            (&mut self.background, &overrides.background, "background"),
            (&mut self.panel, &overrides.panel, "panel"),
            (&mut self.text, &overrides.text, "text"),
            (&mut self.muted, &overrides.muted, "muted"),
            (&mut self.accent, &overrides.accent, "accent"),
            (&mut self.selection, &overrides.selection, "selection"),
            (&mut self.success, &overrides.success, "success"),
            (&mut self.warning, &overrides.warning, "warning"),
            (&mut self.error, &overrides.error, "error"),
        ] {
            if let Some(value) = value {
                *target = parse_color(value)
                    .ok_or_else(|| format!("invalid {name} theme color: {value}"))?;
            }
        }
        Ok(())
    }

    const fn playbridge_dark() -> Self {
        Self {
            background: Color::Rgb(13, 17, 27),
            panel: Color::Rgb(24, 30, 44),
            text: Color::Rgb(226, 232, 240),
            muted: Color::Rgb(139, 151, 170),
            accent: Color::Rgb(67, 211, 238),
            selection: Color::Rgb(92, 124, 250),
            success: Color::Rgb(74, 222, 128),
            warning: Color::Rgb(251, 191, 36),
            error: Color::Rgb(248, 113, 113),
        }
    }

    const fn playbridge_light() -> Self {
        Self {
            background: Color::Rgb(247, 249, 252),
            panel: Color::Rgb(232, 237, 245),
            text: Color::Rgb(30, 41, 59),
            muted: Color::Rgb(100, 116, 139),
            accent: Color::Rgb(8, 145, 178),
            selection: Color::Rgb(96, 125, 239),
            success: Color::Rgb(22, 163, 74),
            warning: Color::Rgb(202, 138, 4),
            error: Color::Rgb(220, 38, 38),
        }
    }

    const fn terminal() -> Self {
        Self {
            background: Color::Reset,
            panel: Color::Reset,
            text: Color::Reset,
            muted: Color::DarkGray,
            accent: Color::Cyan,
            selection: Color::Blue,
            success: Color::Green,
            warning: Color::Yellow,
            error: Color::Red,
        }
    }

    const fn monochrome() -> Self {
        Self {
            background: Color::Reset,
            panel: Color::Reset,
            text: Color::Reset,
            muted: Color::DarkGray,
            accent: Color::White,
            selection: Color::White,
            success: Color::White,
            warning: Color::White,
            error: Color::White,
        }
    }
}

fn parse_color(value: &str) -> Option<Color> {
    let normalized = value.trim().to_ascii_lowercase();
    if let Some(hex) = normalized.strip_prefix('#')
        && hex.len() == 6
    {
        return Some(Color::Rgb(
            u8::from_str_radix(&hex[0..2], 16).ok()?,
            u8::from_str_radix(&hex[2..4], 16).ok()?,
            u8::from_str_radix(&hex[4..6], 16).ok()?,
        ));
    }
    Some(match normalized.as_str() {
        "reset" | "default" => Color::Reset,
        "black" => Color::Black,
        "red" => Color::Red,
        "green" => Color::Green,
        "yellow" => Color::Yellow,
        "blue" => Color::Blue,
        "magenta" => Color::Magenta,
        "cyan" => Color::Cyan,
        "gray" | "grey" => Color::Gray,
        "dark-gray" | "dark-grey" => Color::DarkGray,
        "white" => Color::White,
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_hex_and_named_colors() {
        assert_eq!(parse_color("#43d3ee"), Some(Color::Rgb(67, 211, 238)));
        assert_eq!(parse_color("cyan"), Some(Color::Cyan));
        assert_eq!(parse_color("not-a-color"), None);
    }
}
