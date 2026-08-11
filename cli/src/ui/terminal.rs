use std::io::{self, IsTerminal, Stdout};

use crossterm::{
    cursor::{Hide, Show},
    event::{DisableMouseCapture, EnableMouseCapture},
    execute,
    terminal::{EnterAlternateScreen, LeaveAlternateScreen, disable_raw_mode, enable_raw_mode},
};
use ratatui::{Terminal, backend::CrosstermBackend};

pub(crate) type PlayBridgeTerminal = Terminal<CrosstermBackend<Stdout>>;

pub(crate) struct TerminalSession {
    terminal: PlayBridgeTerminal,
    mouse: bool,
    restored: bool,
}

impl TerminalSession {
    pub(crate) fn start(mouse: bool) -> Result<Self, String> {
        if !io::stdin().is_terminal() || !io::stdout().is_terminal() {
            return Err("interactive dashboard requires a terminal".into());
        }
        enable_raw_mode().map_err(|error| error.to_string())?;
        let mut stdout = io::stdout();
        let setup = if mouse {
            execute!(stdout, EnterAlternateScreen, EnableMouseCapture, Hide)
        } else {
            execute!(stdout, EnterAlternateScreen, Hide)
        };
        if let Err(error) = setup {
            let _ = disable_raw_mode();
            return Err(error.to_string());
        }
        let backend = CrosstermBackend::new(stdout);
        let terminal = Terminal::new(backend).map_err(|error| {
            let _ = restore_terminal(mouse);
            error.to_string()
        })?;
        Ok(Self {
            terminal,
            mouse,
            restored: false,
        })
    }

    pub(crate) fn terminal(&mut self) -> &mut PlayBridgeTerminal {
        &mut self.terminal
    }

    pub(crate) fn restore(&mut self) -> Result<(), String> {
        if self.restored {
            return Ok(());
        }
        let cursor_result = self
            .terminal
            .show_cursor()
            .map_err(|error| error.to_string());
        let terminal_result = restore_terminal(self.mouse);
        self.restored = true;
        cursor_result.and(terminal_result)
    }
}

impl Drop for TerminalSession {
    fn drop(&mut self) {
        let _ = self.restore();
    }
}

fn restore_terminal(mouse: bool) -> Result<(), String> {
    let _ = disable_raw_mode();
    let mut stdout = io::stdout();
    if mouse {
        execute!(stdout, DisableMouseCapture, Show, LeaveAlternateScreen)
            .map_err(|error| error.to_string())
    } else {
        execute!(stdout, Show, LeaveAlternateScreen).map_err(|error| error.to_string())
    }
}
