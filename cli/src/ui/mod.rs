mod config;
mod file_picker;
mod terminal;
mod theme;

use std::{
    collections::HashSet,
    future::pending,
    io::IsTerminal,
    path::Path,
    pin::Pin,
    time::{Duration, Instant},
};

use crossterm::event::{
    self, Event, KeyCode, KeyEvent, KeyEventKind, KeyModifiers, MouseEvent, MouseEventKind,
};
use playbridge_cast_core::discovery::{
    DiscoveryConfig, DiscoveryEvent, DiscoveryStream, Receiver, ReceiverId, ReceiverProtocol,
};
use ratatui::{
    Frame,
    layout::{Alignment, Constraint, Layout, Rect},
    style::Style,
    text::{Line, Span, Text},
    widgets::{
        Block, BorderType, Borders, Clear, List, ListItem, ListState, Padding, Paragraph, Row,
        Table, Wrap,
    },
};
use tokio::time::sleep;
use unicode_width::{UnicodeWidthChar, UnicodeWidthStr};

use crate::preferred::PreferredDevice;

use self::{config::UiConfig, file_picker::FilePicker, terminal::TerminalSession, theme::Theme};

pub(crate) use config::config_path;

pub(crate) fn validate_config(theme_override: Option<&str>) -> Result<(), String> {
    UiConfig::load(theme_override).map(|_| ())
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum DashboardLaunch {
    Home,
    Cast {
        source: Option<String>,
        browser: bool,
    },
    Discover {
        protocols: HashSet<ReceiverProtocol>,
        timeout: Duration,
    },
    Receiver {
        arguments: Vec<String>,
        auto_start: bool,
    },
    Settings {
        clear_preferred: bool,
    },
}

#[derive(Debug, Clone)]
enum DashboardAction {
    StartCast { source: String, receiver: Receiver },
    StartBrowserCast { source: String },
    CastCommand(crate::send::CastCommand),
    StartReceiver,
    ReceiverCommand(crate::receive::ReceiverDashboardCommand),
    CheckUpdate,
    InstallUpdate,
    CancelUpdate,
    Exit,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Section {
    Home,
    Cast,
    Remote,
    Discover,
    Receiver,
    Settings,
    Help,
}

impl Section {
    const ALL: [Self; 7] = [
        Self::Home,
        Self::Cast,
        Self::Remote,
        Self::Discover,
        Self::Receiver,
        Self::Settings,
        Self::Help,
    ];

    const fn label(self) -> &'static str {
        match self {
            Self::Home => "Home",
            Self::Cast => "Cast",
            Self::Remote => "Remote",
            Self::Discover => "Discover",
            Self::Receiver => "Receiver",
            Self::Settings => "Settings",
            Self::Help => "Help",
        }
    }

    const fn description(self) -> &'static str {
        match self {
            Self::Home => "Choose a PlayBridge workflow",
            Self::Cast => "Send a local file or remote URL",
            Self::Remote => "Status and controls for the outgoing cast",
            Self::Discover => "Inspect receivers on your local network",
            Self::Receiver => "Host a PlayBridge receiver through mpv",
            Self::Settings => "Theme, input, and saved-device preferences",
            Self::Help => "Keyboard and interaction reference",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Focus {
    Navigation,
    Content,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Overlay {
    None,
    UrlInput,
    Files,
    Help,
    Palette,
    ReceiverPicker,
    Pairing,
    BrowserPairing,
    BrowserHost,
    ManualReceiver,
    UpdateConfirm,
    UpdateProgress,
    UpdateManual,
    Quit,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum NoticeLevel {
    Info,
    Success,
    Warning,
    Error,
}

#[derive(Debug, Clone)]
struct Notice {
    level: NoticeLevel,
    message: String,
    created_at: Instant,
}

#[derive(Default)]
struct RemoteUiState {
    capabilities: crate::send::CastCapabilities,
    snapshot: Option<crate::send::CastSnapshot>,
}

#[derive(Default)]
struct ReceiverUiState {
    name: Option<String>,
    port: Option<u16>,
    clients: usize,
    authenticated_clients: usize,
    pairing: Option<(String, String)>,
    playback: Option<crate::receive::ReceiverPlaybackSnapshot>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum UpdateStatus {
    Disabled,
    Idle,
    Checking,
    UpToDate,
    Available,
    Downloading,
    Error,
}

struct App {
    config: UiConfig,
    theme: Theme,
    section: Section,
    focus: Focus,
    content_selection: usize,
    overlay: Overlay,
    palette_selection: usize,
    input: String,
    source: Option<String>,
    devices: Vec<Receiver>,
    device_selection: usize,
    scanning: bool,
    rescan_requested: bool,
    notice: Option<Notice>,
    file_picker: FilePicker,
    cast_active: bool,
    cast_generation: u64,
    cast_target: Option<String>,
    remote: RemoteUiState,
    pairing_device: Option<String>,
    receiver_active: bool,
    receiver_state: ReceiverUiState,
    receiver_arguments: Vec<String>,
    receiver_start_requested: bool,
    browser_start_requested: bool,
    browser_urls: Vec<String>,
    discovery_config: DiscoveryConfig,
    preferred_auto_selected: bool,
    update_status: UpdateStatus,
    available_update: Option<crate::update::AvailableUpdate>,
    update_progress: Option<crate::update_installer::InstallProgress>,
    update_error: Option<String>,
    update_generation: u64,
}

impl App {
    fn new(config: UiConfig) -> Result<Self, String> {
        let theme = Theme::from_config(&config)?;
        Ok(Self {
            config,
            theme,
            section: Section::Home,
            focus: Focus::Navigation,
            content_selection: 0,
            overlay: Overlay::None,
            palette_selection: 0,
            input: String::new(),
            source: None,
            devices: Vec::new(),
            device_selection: 0,
            scanning: true,
            rescan_requested: false,
            notice: None,
            file_picker: FilePicker::new(),
            cast_active: false,
            cast_generation: 0,
            cast_target: None,
            remote: RemoteUiState::default(),
            pairing_device: None,
            receiver_active: false,
            receiver_state: ReceiverUiState::default(),
            receiver_arguments: Vec::new(),
            receiver_start_requested: false,
            browser_start_requested: false,
            browser_urls: Vec::new(),
            discovery_config: DiscoveryConfig::default(),
            preferred_auto_selected: false,
            update_status: if crate::update::checks_disabled() {
                UpdateStatus::Disabled
            } else {
                UpdateStatus::Idle
            },
            available_update: None,
            update_progress: None,
            update_error: None,
            update_generation: 0,
        })
    }

    fn apply_launch(&mut self, launch: DashboardLaunch) {
        match launch {
            DashboardLaunch::Home => {}
            DashboardLaunch::Cast { source, browser } => {
                self.source = source;
                self.navigate_to(Section::Cast);
                if browser {
                    self.browser_start_requested = self.source.is_some();
                } else if self.source.is_some() {
                    self.overlay = Overlay::ReceiverPicker;
                }
            }
            DashboardLaunch::Discover { protocols, timeout } => {
                self.discovery_config = DiscoveryConfig::selected(protocols, timeout);
                self.navigate_to(Section::Discover);
            }
            DashboardLaunch::Receiver {
                arguments,
                auto_start,
            } => {
                self.navigate_to(Section::Receiver);
                self.receiver_arguments = arguments;
                self.receiver_start_requested = auto_start;
            }
            DashboardLaunch::Settings { clear_preferred } => {
                self.navigate_to(Section::Settings);
                if clear_preferred {
                    match PreferredDevice::clear() {
                        Ok(()) => self.notice(NoticeLevel::Success, "Preferred receiver cleared"),
                        Err(error) => self.notice(NoticeLevel::Error, error),
                    }
                }
            }
        }
    }

    fn navigate_to(&mut self, section: Section) {
        self.section = section;
        self.content_selection = 0;
        self.focus = if matches!(section, Section::Home | Section::Help) {
            Focus::Navigation
        } else {
            Focus::Content
        };
    }

    fn navigation_index(&self) -> usize {
        Section::ALL
            .iter()
            .position(|item| *item == self.section)
            .unwrap_or(0)
    }

    fn move_down(&mut self) {
        match self.overlay {
            Overlay::Files => self.file_picker.move_down(),
            Overlay::Palette => {
                self.palette_selection = (self.palette_selection + 1).min(Section::ALL.len() - 1);
            }
            Overlay::None if self.focus == Focus::Navigation => {
                let next = (self.navigation_index() + 1).min(Section::ALL.len() - 1);
                self.navigate_to(Section::ALL[next]);
                self.focus = Focus::Navigation;
            }
            Overlay::None if self.section == Section::Discover => {
                if self.device_selection + 1 < self.devices.len() {
                    self.device_selection += 1;
                }
            }
            Overlay::None => {
                self.content_selection = self.content_selection.saturating_add(1);
            }
            _ => {}
        }
    }

    fn move_up(&mut self) {
        match self.overlay {
            Overlay::Files => self.file_picker.move_up(),
            Overlay::Palette => {
                self.palette_selection = self.palette_selection.saturating_sub(1);
            }
            Overlay::None if self.focus == Focus::Navigation => {
                let previous = self.navigation_index().saturating_sub(1);
                self.navigate_to(Section::ALL[previous]);
                self.focus = Focus::Navigation;
            }
            Overlay::None if self.section == Section::Discover => {
                self.device_selection = self.device_selection.saturating_sub(1);
            }
            Overlay::None => {
                self.content_selection = self.content_selection.saturating_sub(1);
            }
            _ => {}
        }
    }

    fn notice(&mut self, level: NoticeLevel, message: impl Into<String>) {
        self.notice = Some(Notice {
            level,
            message: message.into(),
            created_at: Instant::now(),
        });
    }

    fn rescan(&mut self) {
        self.rescan_requested = true;
        self.devices.clear();
        self.device_selection = 0;
        self.preferred_auto_selected = false;
        self.scanning = true;
        self.notice(NoticeLevel::Info, "Scanning selected receiver protocols…");
    }

    fn upsert_device(&mut self, receiver: Receiver) {
        let selected_id = self
            .devices
            .get(self.device_selection)
            .map(|item| item.id.clone());
        if let Some(existing) = self.devices.iter_mut().find(|item| item.id == receiver.id) {
            *existing = receiver;
        } else {
            self.devices.push(receiver);
        }
        self.devices.sort_by(|left, right| {
            protocol_rank(left.protocol)
                .cmp(&protocol_rank(right.protocol))
                .then_with(|| left.name.to_lowercase().cmp(&right.name.to_lowercase()))
                .then_with(|| left.id.0.cmp(&right.id.0))
        });
        if !self.preferred_auto_selected
            && let Some(preferred) = PreferredDevice::load()
            && let Some(index) = self
                .devices
                .iter()
                .position(|item| receiver_matches_preferred(item, &preferred))
        {
            self.device_selection = index;
            self.preferred_auto_selected = true;
            return;
        }
        self.device_selection = selected_id
            .and_then(|id| self.devices.iter().position(|item| item.id == id))
            .unwrap_or_else(|| {
                self.device_selection
                    .min(self.devices.len().saturating_sub(1))
            });
    }

    fn save_selected_preferred(&mut self) {
        let Some(target) = self.devices.get(self.device_selection) else {
            self.notice(NoticeLevel::Warning, "Select a receiver first");
            return;
        };
        let address = preferred_address(target).unwrap_or_default().to_owned();
        let preferred = PreferredDevice {
            uuid: target.uuid.clone().unwrap_or_else(|| target.id.0.clone()),
            name: target.name.clone(),
            protocol: target.protocol.as_str().into(),
            address,
            port: target.port,
            wss_port: target.wss_port,
            location: target.location.clone(),
        };
        match preferred.save() {
            Ok(()) => self.notice(
                NoticeLevel::Success,
                format!("{} is now the preferred receiver", target.name),
            ),
            Err(error) => self.notice(NoticeLevel::Error, error),
        }
    }

    fn cycle_theme(&mut self) {
        const THEMES: &[&str] = &[
            "playbridge-dark",
            "playbridge-light",
            "terminal",
            "monochrome",
        ];
        let current = THEMES
            .iter()
            .position(|theme| *theme == self.config.ui.theme)
            .unwrap_or(0);
        self.config.ui.theme = THEMES[(current + 1) % THEMES.len()].into();
        match Theme::from_config(&self.config) {
            Ok(theme) => {
                self.theme = theme;
                if let Err(error) = self.config.save() {
                    self.notice(NoticeLevel::Error, error);
                } else {
                    self.notice(
                        NoticeLevel::Success,
                        format!("Theme changed to {}", self.config.ui.theme),
                    );
                }
            }
            Err(error) => self.notice(NoticeLevel::Error, error),
        }
    }
}

pub(crate) fn dashboard_available() -> bool {
    std::io::stdin().is_terminal()
        && std::io::stdout().is_terminal()
        && std::env::var("TERM").map_or(true, |term| term != "dumb")
}

type DashboardCastFuture = Pin<Box<dyn std::future::Future<Output = Result<(), String>>>>;
type UpdateCheckFuture = Pin<
    Box<
        dyn std::future::Future<
                Output = (
                    u64,
                    bool,
                    Result<Option<crate::update::AvailableUpdate>, String>,
                ),
            >,
    >,
>;
type UpdatePrepareFuture = Pin<
    Box<
        dyn std::future::Future<
                Output = (u64, Result<crate::update_installer::PreparedUpdate, String>),
            >,
    >,
>;

pub(crate) async fn run_dashboard(
    theme_override: Option<&str>,
    launch: DashboardLaunch,
) -> Result<(), String> {
    let config = UiConfig::load(theme_override)?;
    let mut app = App::new(config)?;
    app.apply_launch(launch);
    if let Some(result) = crate::update_installer::take_restart_notice() {
        match result {
            Ok(message) => app.notice(NoticeLevel::Success, message),
            Err(message) => app.notice(NoticeLevel::Error, message),
        }
    }
    let mut terminal = TerminalSession::start(app.config.ui.mouse)?;
    let mut discovery = DiscoveryStream::start(app.discovery_config.clone());
    let mut cast_future: Option<DashboardCastFuture> = None;
    let mut cast_commands: Option<tokio::sync::mpsc::Sender<crate::send::CastCommand>> = None;
    let mut cast_events: Option<tokio::sync::mpsc::Receiver<crate::send::CastEvent>> = None;
    let mut receiver_future: Option<DashboardCastFuture> = None;
    let mut receiver_commands: Option<
        tokio::sync::mpsc::Sender<crate::receive::ReceiverDashboardCommand>,
    > = None;
    let mut receiver_events: Option<tokio::sync::mpsc::Receiver<crate::receive::ReceiverUiEvent>> =
        None;
    let mut update_check_future: Option<UpdateCheckFuture> = None;
    let mut update_prepare_future: Option<UpdatePrepareFuture> = None;
    let mut update_progress_rx: Option<
        tokio::sync::mpsc::UnboundedReceiver<crate::update_installer::InstallProgress>,
    > = None;

    if app.update_status != UpdateStatus::Disabled {
        app.update_status = UpdateStatus::Checking;
        app.update_generation = app.update_generation.wrapping_add(1);
        let generation = app.update_generation;
        update_check_future = Some(Box::pin(async move {
            (
                generation,
                false,
                crate::update::check_for_update(false).await,
            )
        }));
    }

    if app.receiver_start_requested {
        let (future, commands, events) = receiver_dashboard_task(app.receiver_arguments.clone());
        receiver_future = Some(future);
        receiver_commands = Some(commands);
        receiver_events = Some(events);
        app.receiver_active = true;
        app.receiver_start_requested = false;
        app.notice(NoticeLevel::Info, "Starting local receiver…");
    }

    if app.browser_start_requested
        && let Some(source) = app.source.clone()
    {
        let (command_tx, command_rx) = tokio::sync::mpsc::channel(16);
        let (event_tx, event_rx) = tokio::sync::mpsc::channel(32);
        app.cast_generation = app.cast_generation.wrapping_add(1);
        let generation = app.cast_generation;
        cast_future = Some(Box::pin(crate::send::run_dashboard_browser_cast(
            source, generation, command_rx, event_tx,
        )));
        cast_commands = Some(command_tx);
        cast_events = Some(event_rx);
        app.cast_active = true;
        app.cast_target = Some("Web Browser".into());
        app.browser_start_requested = false;
        app.notice(NoticeLevel::Info, "Starting browser receiver host…");
    }

    loop {
        if app.notice.as_ref().is_some_and(|notice| {
            notice.created_at.elapsed()
                >= if notice.level == NoticeLevel::Error {
                    Duration::from_secs(12)
                } else {
                    Duration::from_secs(6)
                }
        }) {
            app.notice = None;
        }
        terminal
            .terminal()
            .draw(|frame| render(frame, &mut app))
            .map_err(|error| error.to_string())?;

        while event::poll(Duration::ZERO).map_err(|error| error.to_string())? {
            let event = event::read().map_err(|error| error.to_string())?;
            if let Some(action) = handle_event(&mut app, event)? {
                match action {
                    DashboardAction::StartCast { source, receiver } => {
                        if cast_future.is_some() {
                            app.notice(
                                NoticeLevel::Warning,
                                "A cast is already active; stop it before starting another",
                            );
                        } else {
                            let target_name = receiver.name.clone();
                            app.cast_generation = app.cast_generation.wrapping_add(1);
                            app.remote = RemoteUiState::default();
                            app.pairing_device = None;
                            let generation = app.cast_generation;
                            let (command_tx, command_rx) = tokio::sync::mpsc::channel(16);
                            let (event_tx, event_rx) = tokio::sync::mpsc::channel(32);
                            cast_future = Some(Box::pin(crate::send::run_dashboard_cast(
                                source, receiver, generation, command_rx, event_tx,
                            )));
                            cast_commands = Some(command_tx);
                            cast_events = Some(event_rx);
                            app.cast_active = true;
                            app.cast_target = Some(target_name.clone());
                            app.notice(NoticeLevel::Info, format!("Connecting to {target_name}…"));
                        }
                    }
                    DashboardAction::StartBrowserCast { source } => {
                        if cast_future.is_some() {
                            app.notice(
                                NoticeLevel::Warning,
                                "A cast is already active; stop it before starting another",
                            );
                        } else {
                            app.cast_generation = app.cast_generation.wrapping_add(1);
                            app.remote = RemoteUiState::default();
                            app.browser_urls.clear();
                            let generation = app.cast_generation;
                            let (command_tx, command_rx) = tokio::sync::mpsc::channel(16);
                            let (event_tx, event_rx) = tokio::sync::mpsc::channel(32);
                            cast_future = Some(Box::pin(crate::send::run_dashboard_browser_cast(
                                source, generation, command_rx, event_tx,
                            )));
                            cast_commands = Some(command_tx);
                            cast_events = Some(event_rx);
                            app.cast_active = true;
                            app.cast_target = Some("Web Browser".into());
                            app.notice(NoticeLevel::Info, "Starting browser receiver host…");
                        }
                    }
                    DashboardAction::CastCommand(command) => {
                        if let Some(commands) = cast_commands.as_ref() {
                            let stopping = matches!(&command, crate::send::CastCommand::Stop);
                            let _ = commands.try_send(command);
                            if stopping {
                                app.notice(NoticeLevel::Info, "Stopping cast…");
                            }
                        } else {
                            app.notice(NoticeLevel::Warning, "No outgoing cast is active");
                        }
                    }
                    DashboardAction::StartReceiver => {
                        if receiver_future.is_some() {
                            app.notice(NoticeLevel::Warning, "Receiver is already running");
                        } else {
                            let (future, commands, events) =
                                receiver_dashboard_task(app.receiver_arguments.clone());
                            receiver_future = Some(future);
                            receiver_commands = Some(commands);
                            receiver_events = Some(events);
                            app.receiver_active = true;
                            app.notice(NoticeLevel::Info, "Starting local receiver…");
                        }
                    }
                    DashboardAction::ReceiverCommand(command) => {
                        if let Some(commands) = receiver_commands.as_ref() {
                            let _ = commands.try_send(command);
                            if matches!(command, crate::receive::ReceiverDashboardCommand::StopHost)
                            {
                                app.notice(NoticeLevel::Info, "Stopping local receiver…");
                            }
                        } else {
                            app.notice(NoticeLevel::Warning, "The local receiver is not running");
                        }
                    }
                    DashboardAction::CheckUpdate => {
                        if app.update_status == UpdateStatus::Disabled {
                            app.notice(
                                NoticeLevel::Warning,
                                "Update checks are disabled by PLAYBRIDGE_NO_UPDATE_CHECK",
                            );
                        } else if update_check_future.is_some() || update_prepare_future.is_some() {
                            app.notice(NoticeLevel::Info, "An update operation is already running");
                        } else {
                            app.update_generation = app.update_generation.wrapping_add(1);
                            let generation = app.update_generation;
                            app.update_status = UpdateStatus::Checking;
                            app.update_error = None;
                            update_check_future = Some(Box::pin(async move {
                                (
                                    generation,
                                    true,
                                    crate::update::check_for_update(true).await,
                                )
                            }));
                            app.notice(NoticeLevel::Info, "Checking for CLI updates…");
                        }
                    }
                    DashboardAction::InstallUpdate => {
                        if app.cast_active || app.receiver_active {
                            app.overlay = Overlay::None;
                            app.notice(
                                NoticeLevel::Warning,
                                "Stop the active cast and local receiver before updating",
                            );
                        } else if update_prepare_future.is_some() {
                            app.notice(NoticeLevel::Info, "The update is already downloading");
                        } else if let Some(update) = app.available_update.clone() {
                            app.update_generation = app.update_generation.wrapping_add(1);
                            let generation = app.update_generation;
                            let (progress_tx, progress_rx) = tokio::sync::mpsc::unbounded_channel();
                            app.update_status = UpdateStatus::Downloading;
                            app.update_progress = None;
                            app.update_error = None;
                            app.overlay = Overlay::UpdateProgress;
                            update_progress_rx = Some(progress_rx);
                            update_prepare_future = Some(Box::pin(async move {
                                let result =
                                    crate::update_installer::prepare(&update, progress_tx).await;
                                (generation, result)
                            }));
                        } else {
                            app.overlay = Overlay::None;
                            app.notice(NoticeLevel::Warning, "No CLI update is available");
                        }
                    }
                    DashboardAction::CancelUpdate => {
                        update_prepare_future = None;
                        update_progress_rx = None;
                        app.update_generation = app.update_generation.wrapping_add(1);
                        app.update_status = if app.available_update.is_some() {
                            UpdateStatus::Available
                        } else {
                            UpdateStatus::Idle
                        };
                        app.update_progress = None;
                        app.overlay = Overlay::None;
                        app.notice(NoticeLevel::Info, "Update download cancelled");
                    }
                    DashboardAction::Exit => {
                        if let Some(commands) = cast_commands.take() {
                            let _ = commands.send(crate::send::CastCommand::Stop).await;
                        }
                        if let Some(future) = cast_future.take() {
                            let _ = future.await;
                        }
                        if let Some(commands) = receiver_commands.take() {
                            let _ = commands
                                .send(crate::receive::ReceiverDashboardCommand::StopHost)
                                .await;
                        }
                        if let Some(future) = receiver_future.take() {
                            let _ = future.await;
                        }
                        return Ok(());
                    }
                }
            }
        }

        tokio::select! {
            result = async {
                match cast_future.as_mut() {
                    Some(future) => Some(future.as_mut().await),
                    None => pending().await,
                }
            } => {
                if let Some(result) = result {
                    cast_future = None;
                    cast_commands = None;
                    cast_events = None;
                    app.cast_active = false;
                    app.cast_target = None;
                    app.remote = RemoteUiState::default();
                    app.pairing_device = None;
                    app.browser_urls.clear();
                    if matches!(
                        app.overlay,
                        Overlay::Pairing | Overlay::BrowserPairing | Overlay::BrowserHost
                    ) {
                        app.overlay = Overlay::None;
                    }
                    match result {
                        Ok(()) => app.notice(NoticeLevel::Success, "Cast stopped; dashboard is still ready"),
                        Err(error) => app.notice(NoticeLevel::Error, format!("Cast failed: {error}")),
                    }
                }
            }
            result = async {
                match receiver_future.as_mut() {
                    Some(future) => Some(future.as_mut().await),
                    None => pending().await,
                }
            } => {
                if let Some(result) = result {
                    receiver_future = None;
                    receiver_commands = None;
                    receiver_events = None;
                    app.receiver_active = false;
                    app.receiver_state = ReceiverUiState::default();
                    match result {
                        Ok(()) => app.notice(NoticeLevel::Success, "Receiver stopped; dashboard is still ready"),
                        Err(error) => app.notice(NoticeLevel::Error, format!("Receiver failed: {error}")),
                    }
                }
            }
            event = async {
                match cast_events.as_mut() {
                    Some(events) => events.recv().await,
                    None => pending().await,
                }
            } => {
                if let Some(event) = event { apply_cast_event(&mut app, event); }
            }
            event = async {
                match receiver_events.as_mut() {
                    Some(events) => events.recv().await,
                    None => pending().await,
                }
            } => {
                if let Some(event) = event { apply_receiver_event(&mut app, event); }
            }
            result = async {
                match update_check_future.as_mut() {
                    Some(future) => Some(future.as_mut().await),
                    None => pending().await,
                }
            } => {
                if let Some((generation, manual, result)) = result {
                    update_check_future = None;
                    if generation == app.update_generation {
                        match result {
                            Ok(Some(update)) => {
                                let version = update.version.clone();
                                app.available_update = Some(update);
                                app.update_status = UpdateStatus::Available;
                                app.update_error = None;
                                if manual {
                                    app.notice(
                                        NoticeLevel::Success,
                                        format!("PlayBridge CLI v{version} is available"),
                                    );
                                }
                            }
                            Ok(None) => {
                                app.available_update = None;
                                app.update_status = UpdateStatus::UpToDate;
                                app.update_error = None;
                                if manual {
                                    app.notice(
                                        NoticeLevel::Success,
                                        format!(
                                            "PlayBridge CLI v{} is up to date",
                                            env!("CARGO_PKG_VERSION")
                                        ),
                                    );
                                }
                            }
                            Err(error) => {
                                app.update_status = UpdateStatus::Error;
                                app.update_error = Some(error.clone());
                                if manual {
                                    app.notice(NoticeLevel::Error, error);
                                }
                            }
                        }
                    }
                }
            }
            progress = async {
                match update_progress_rx.as_mut() {
                    Some(progress) => progress.recv().await,
                    None => pending().await,
                }
            } => {
                if let Some(progress) = progress {
                    app.update_progress = Some(progress);
                } else {
                    update_progress_rx = None;
                }
            }
            result = async {
                match update_prepare_future.as_mut() {
                    Some(future) => Some(future.as_mut().await),
                    None => pending().await,
                }
            } => {
                if let Some((generation, result)) = result {
                    update_prepare_future = None;
                    update_progress_rx = None;
                    if generation == app.update_generation {
                        match result {
                            Ok(prepared) => {
                                drop(terminal);
                                crate::update_installer::handoff(prepared)?;
                                return Ok(());
                            }
                            Err(error) => {
                                app.update_status = if app.available_update.is_some() {
                                    UpdateStatus::Available
                                } else {
                                    UpdateStatus::Error
                                };
                                app.update_error = Some(error.clone());
                                app.update_progress = None;
                                app.overlay = Overlay::None;
                                app.notice(NoticeLevel::Error, error);
                            }
                        }
                    }
                }
            }
            event = discovery.next() => {
                match event {
                    Some(DiscoveryEvent::Found(receiver) | DiscoveryEvent::Updated(receiver)) => {
                        app.upsert_device(receiver);
                    }
                    Some(DiscoveryEvent::Error { protocol, message }) => {
                        app.notice(NoticeLevel::Warning, format!("{protocol}: {message}"));
                    }
                    Some(DiscoveryEvent::Finished(_)) => app.scanning = false,
                    Some(DiscoveryEvent::Started(_)) => app.scanning = true,
                    None => app.scanning = false,
                }
            }
            _ = sleep(Duration::from_millis(100)) => {}
        }

        if app.rescan_requested {
            discovery = DiscoveryStream::start(app.discovery_config.clone());
            app.scanning = true;
            app.rescan_requested = false;
        }
    }
}

fn receiver_dashboard_task(
    arguments: Vec<String>,
) -> (
    DashboardCastFuture,
    tokio::sync::mpsc::Sender<crate::receive::ReceiverDashboardCommand>,
    tokio::sync::mpsc::Receiver<crate::receive::ReceiverUiEvent>,
) {
    let (command_tx, command_rx) = tokio::sync::mpsc::channel(16);
    let (event_tx, event_rx) = tokio::sync::mpsc::channel(32);
    (
        Box::pin(crate::receive::run_receiver_dashboard(
            arguments, command_rx, event_tx,
        )),
        command_tx,
        event_rx,
    )
}

fn apply_cast_event(app: &mut App, event: crate::send::CastEvent) {
    match event {
        crate::send::CastEvent::BrowserHosting { generation, urls }
            if generation == app.cast_generation =>
        {
            app.browser_urls = urls;
            app.navigate_to(Section::Cast);
            app.overlay = Overlay::BrowserHost;
            app.notice(
                NoticeLevel::Info,
                "Open a browser receiver URL to begin pairing",
            );
        }
        crate::send::CastEvent::BrowserPairingRequested {
            generation,
            device_name,
        } if generation == app.cast_generation => {
            app.pairing_device = Some(device_name);
            app.input.clear();
            app.overlay = Overlay::BrowserPairing;
            app.notice(
                NoticeLevel::Info,
                "Enter the six-digit code shown by the browser receiver",
            );
        }
        crate::send::CastEvent::PairingCodeRequested {
            generation,
            device_name,
        } if generation == app.cast_generation => {
            app.pairing_device = Some(device_name);
            app.input.clear();
            app.overlay = Overlay::Pairing;
            app.notice(
                NoticeLevel::Info,
                "Enter the six-digit code shown by the receiver",
            );
        }
        crate::send::CastEvent::PairingCompleted {
            generation,
            device_name,
        } if generation == app.cast_generation => {
            app.pairing_device = None;
            app.input.clear();
            if app.overlay == Overlay::BrowserPairing {
                app.overlay = Overlay::None;
            }
            app.overlay = Overlay::None;
            app.notice(
                NoticeLevel::Success,
                format!("Paired with {device_name}; starting playback…"),
            );
        }
        crate::send::CastEvent::Connected {
            generation,
            capabilities,
            snapshot,
        } if generation == app.cast_generation => {
            app.remote.capabilities = capabilities;
            app.remote.snapshot = Some(snapshot);
            app.pairing_device = None;
            app.input.clear();
            app.overlay = Overlay::None;
            app.navigate_to(Section::Remote);
            app.notice(
                NoticeLevel::Success,
                "Cast connected; remote controls are ready",
            );
        }
        crate::send::CastEvent::Snapshot {
            generation,
            snapshot,
        } if generation == app.cast_generation => {
            app.remote.snapshot = Some(snapshot);
        }
        crate::send::CastEvent::Warning {
            generation,
            message,
        } if generation == app.cast_generation => {
            app.notice(NoticeLevel::Warning, message);
        }
        _ => {}
    }
}

fn apply_receiver_event(app: &mut App, event: crate::receive::ReceiverUiEvent) {
    match event {
        crate::receive::ReceiverUiEvent::HostStarted { name, port } => {
            app.receiver_state.name = Some(name);
            app.receiver_state.port = Some(port);
        }
        crate::receive::ReceiverUiEvent::ClientCount {
            total,
            authenticated,
        } => {
            app.receiver_state.clients = total;
            app.receiver_state.authenticated_clients = authenticated;
        }
        crate::receive::ReceiverUiEvent::PairingRequested {
            device_name,
            sas_code,
        } => {
            app.receiver_state.pairing = Some((device_name, sas_code));
        }
        crate::receive::ReceiverUiEvent::Paired { device_name } => {
            app.receiver_state.pairing = None;
            app.notice(
                NoticeLevel::Success,
                format!("Sender \"{device_name}\" paired"),
            );
        }
        crate::receive::ReceiverUiEvent::Playback(snapshot) => {
            app.receiver_state.playback = Some(snapshot);
        }
        crate::receive::ReceiverUiEvent::Warning(message) => {
            app.notice(NoticeLevel::Warning, message);
        }
    }
}

fn handle_event(app: &mut App, event: Event) -> Result<Option<DashboardAction>, String> {
    match event {
        Event::Key(key) if key.kind != KeyEventKind::Release => handle_key(app, key),
        Event::Paste(text) if matches!(app.overlay, Overlay::UrlInput) => {
            app.input.push_str(text.trim());
            Ok(None)
        }
        Event::Paste(text) if matches!(app.overlay, Overlay::Pairing | Overlay::BrowserPairing) => {
            for character in text.chars().filter(|character| character.is_ascii_digit()) {
                if app.input.len() < 6 {
                    app.input.push(character);
                }
            }
            Ok(None)
        }
        Event::Paste(text) if matches!(app.overlay, Overlay::ManualReceiver) => {
            app.input.push_str(text.trim());
            Ok(None)
        }
        Event::Mouse(mouse) if app.config.ui.mouse => {
            handle_mouse(app, mouse);
            Ok(None)
        }
        Event::Resize(_, _)
        | Event::FocusGained
        | Event::FocusLost
        | Event::Key(_)
        | Event::Paste(_)
        | Event::Mouse(_) => Ok(None),
    }
}

fn handle_key(app: &mut App, key: KeyEvent) -> Result<Option<DashboardAction>, String> {
    if key.code == KeyCode::Char('c') && key.modifiers.contains(KeyModifiers::CONTROL) {
        return Ok(Some(DashboardAction::Exit));
    }

    if app.section == Section::Remote && app.cast_active {
        let command = match key.code {
            KeyCode::Char(' ') => Some(crate::send::CastCommand::PlayPause),
            KeyCode::Char('a' | 'A') => Some(crate::send::CastCommand::SeekRelative(-10)),
            KeyCode::Char('d' | 'D') => Some(crate::send::CastCommand::SeekRelative(10)),
            KeyCode::Char('+') => Some(crate::send::CastCommand::VolumeDelta(0.05)),
            KeyCode::Char('-') => Some(crate::send::CastCommand::VolumeDelta(-0.05)),
            KeyCode::Char('m' | 'M') => Some(crate::send::CastCommand::ToggleMute),
            KeyCode::Char('o' | 'O') => Some(crate::send::CastCommand::ToggleLoop),
            KeyCode::Char('b' | 'B') => Some(crate::send::CastCommand::ToggleAudioBoost),
            KeyCode::Char('1') => Some(crate::send::CastCommand::SetSpeed(1.0)),
            KeyCode::Char('2') => Some(crate::send::CastCommand::SetSpeed(1.25)),
            KeyCode::Char('3') => Some(crate::send::CastCommand::SetSpeed(1.5)),
            KeyCode::Char('4') => Some(crate::send::CastCommand::SetSpeed(2.0)),
            KeyCode::Char('x' | 'X') => Some(crate::send::CastCommand::Stop),
            _ => None,
        };
        if let Some(command) = command {
            return Ok(Some(DashboardAction::CastCommand(command)));
        }
    }
    if app.section == Section::Receiver && app.receiver_active {
        let command = match key.code {
            KeyCode::Char(' ') => Some(crate::receive::ReceiverDashboardCommand::PlayPause),
            KeyCode::Char('a' | 'A') => {
                Some(crate::receive::ReceiverDashboardCommand::SeekRelative(-10))
            }
            KeyCode::Char('d' | 'D') => {
                Some(crate::receive::ReceiverDashboardCommand::SeekRelative(10))
            }
            KeyCode::Char('+') => Some(crate::receive::ReceiverDashboardCommand::VolumeDelta(0.05)),
            KeyCode::Char('-') => {
                Some(crate::receive::ReceiverDashboardCommand::VolumeDelta(-0.05))
            }
            KeyCode::Char('m' | 'M') => Some(crate::receive::ReceiverDashboardCommand::ToggleMute),
            KeyCode::Char('o' | 'O') => Some(crate::receive::ReceiverDashboardCommand::ToggleLoop),
            KeyCode::Char('1') => Some(crate::receive::ReceiverDashboardCommand::SetSpeed(1.0)),
            KeyCode::Char('2') => Some(crate::receive::ReceiverDashboardCommand::SetSpeed(1.25)),
            KeyCode::Char('3') => Some(crate::receive::ReceiverDashboardCommand::SetSpeed(1.5)),
            KeyCode::Char('4') => Some(crate::receive::ReceiverDashboardCommand::SetSpeed(2.0)),
            KeyCode::Char('[') => Some(crate::receive::ReceiverDashboardCommand::Previous),
            KeyCode::Char(']') => Some(crate::receive::ReceiverDashboardCommand::Next),
            KeyCode::Char('x' | 'X') => {
                Some(crate::receive::ReceiverDashboardCommand::StopPlayback)
            }
            _ => None,
        };
        if let Some(command) = command {
            return Ok(Some(DashboardAction::ReceiverCommand(command)));
        }
    }

    match app.overlay {
        Overlay::UrlInput => return handle_url_input(app, key),
        Overlay::Files => return handle_file_picker(app, key),
        Overlay::Palette => return handle_palette(app, key),
        Overlay::ReceiverPicker => return handle_receiver_picker(app, key),
        Overlay::ManualReceiver => {
            return match key.code {
                KeyCode::Enter => match parse_manual_receiver(&app.input) {
                    Ok(receiver) => {
                        let Some(source) = app.source.clone() else {
                            app.overlay = Overlay::None;
                            app.notice(NoticeLevel::Warning, "Select media first");
                            return Ok(None);
                        };
                        app.overlay = Overlay::None;
                        app.input.clear();
                        Ok(Some(DashboardAction::StartCast { source, receiver }))
                    }
                    Err(error) => {
                        app.notice(NoticeLevel::Warning, error);
                        Ok(None)
                    }
                },
                KeyCode::Backspace => {
                    app.input.pop();
                    Ok(None)
                }
                KeyCode::Char(character) => {
                    app.input.push(character);
                    Ok(None)
                }
                KeyCode::Esc => {
                    app.overlay = Overlay::None;
                    app.input.clear();
                    Ok(None)
                }
                _ => Ok(None),
            };
        }
        Overlay::BrowserHost => {
            if matches!(key.code, KeyCode::Enter | KeyCode::Esc) {
                app.overlay = Overlay::None;
            }
            return Ok(None);
        }
        Overlay::Pairing => {
            return match key.code {
                KeyCode::Char(character) if character.is_ascii_digit() && app.input.len() < 6 => {
                    app.input.push(character);
                    Ok(None)
                }
                KeyCode::Backspace => {
                    app.input.pop();
                    Ok(None)
                }
                KeyCode::Enter if app.input.len() == 6 => {
                    let code = std::mem::take(&mut app.input);
                    Ok(Some(DashboardAction::CastCommand(
                        crate::send::CastCommand::SubmitPairingCode(code),
                    )))
                }
                KeyCode::Enter => {
                    app.notice(NoticeLevel::Warning, "Enter the complete six-digit code");
                    Ok(None)
                }
                KeyCode::Esc => {
                    app.overlay = Overlay::None;
                    app.pairing_device = None;
                    app.input.clear();
                    Ok(Some(DashboardAction::CastCommand(
                        crate::send::CastCommand::CancelPairing,
                    )))
                }
                _ => Ok(None),
            };
        }
        Overlay::BrowserPairing => {
            return match key.code {
                KeyCode::Char(character) if character.is_ascii_digit() && app.input.len() < 6 => {
                    app.input.push(character);
                    Ok(None)
                }
                KeyCode::Backspace => {
                    app.input.pop();
                    Ok(None)
                }
                KeyCode::Enter if app.input.len() == 6 => {
                    let code = std::mem::take(&mut app.input);
                    Ok(Some(DashboardAction::CastCommand(
                        crate::send::CastCommand::SubmitBrowserPairing(code),
                    )))
                }
                KeyCode::Enter => {
                    app.notice(NoticeLevel::Warning, "Enter the complete six-digit code");
                    Ok(None)
                }
                KeyCode::Esc => {
                    app.overlay = Overlay::None;
                    app.input.clear();
                    Ok(Some(DashboardAction::CastCommand(
                        crate::send::CastCommand::CancelPairing,
                    )))
                }
                _ => Ok(None),
            };
        }
        Overlay::UpdateConfirm => {
            return match key.code {
                KeyCode::Enter | KeyCode::Char('y' | 'Y') => {
                    Ok(Some(DashboardAction::InstallUpdate))
                }
                KeyCode::Esc | KeyCode::Char('n' | 'N') => {
                    app.overlay = Overlay::None;
                    Ok(None)
                }
                _ => Ok(None),
            };
        }
        Overlay::UpdateProgress => {
            return match key.code {
                KeyCode::Esc => Ok(Some(DashboardAction::CancelUpdate)),
                _ => Ok(None),
            };
        }
        Overlay::UpdateManual => {
            if matches!(key.code, KeyCode::Enter | KeyCode::Esc) {
                app.overlay = Overlay::None;
            }
            return Ok(None);
        }
        Overlay::Help => {
            if app.config.matches("back", key) || app.config.matches("help", key) {
                app.overlay = Overlay::None;
            }
            return Ok(None);
        }
        Overlay::Quit => {
            match key.code {
                KeyCode::Char('y' | 'Y') | KeyCode::Enter => {
                    return Ok(Some(DashboardAction::Exit));
                }
                KeyCode::Char('n' | 'N') | KeyCode::Esc => app.overlay = Overlay::None,
                _ => {}
            }
            return Ok(None);
        }
        Overlay::None => {}
    }

    if app.config.matches("help", key) {
        app.overlay = Overlay::Help;
    } else if app.config.matches("palette", key) {
        app.overlay = Overlay::Palette;
        app.palette_selection = app.navigation_index();
    } else if app.config.matches("down", key) {
        app.move_down();
    } else if app.config.matches("up", key) {
        app.move_up();
    } else if app.config.matches("left", key) || app.config.matches("back", key) {
        app.focus = Focus::Navigation;
    } else if app.config.matches("right", key) || key.code == KeyCode::Tab {
        app.focus = Focus::Content;
    } else if app.config.matches("rescan", key) && app.section == Section::Discover {
        app.rescan();
    } else if app.config.matches("preferred", key) && app.section == Section::Discover {
        app.save_selected_preferred();
    } else if app.config.matches("quit", key) {
        app.overlay = Overlay::Quit;
    } else if app.config.matches("select", key) {
        return activate(app);
    } else if matches!(key.code, KeyCode::Char('d' | 'D')) {
        app.navigate_to(Section::Discover);
    } else if app.section == Section::Cast {
        match key.code {
            KeyCode::Char('u' | 'U') => {
                app.overlay = Overlay::UrlInput;
                app.input.clear();
            }
            KeyCode::Char('f' | 'F') => app.overlay = Overlay::Files,
            KeyCode::Char('b' | 'B') if app.source.is_some() => {
                return Ok(Some(DashboardAction::StartBrowserCast {
                    source: app.source.clone().expect("source checked"),
                }));
            }
            _ => {}
        }
    } else if app.section == Section::Settings {
        match key.code {
            KeyCode::Char('t' | 'T') => app.cycle_theme(),
            KeyCode::Char('m' | 'M') => {
                app.config.ui.mouse = !app.config.ui.mouse;
                match app.config.save() {
                    Ok(()) => app.notice(
                        NoticeLevel::Success,
                        format!(
                            "Mouse support: {} (applies next dashboard)",
                            on_off(app.config.ui.mouse)
                        ),
                    ),
                    Err(error) => app.notice(NoticeLevel::Error, error),
                }
            }
            KeyCode::Char('u' | 'U') => {
                app.config.ui.unicode = !app.config.ui.unicode;
                match app.config.save() {
                    Ok(()) => app.notice(
                        NoticeLevel::Success,
                        format!("Unicode UI: {}", on_off(app.config.ui.unicode)),
                    ),
                    Err(error) => app.notice(NoticeLevel::Error, error),
                }
            }
            KeyCode::Char('c' | 'C') => match PreferredDevice::clear() {
                Ok(()) => app.notice(NoticeLevel::Success, "Preferred receiver cleared"),
                Err(error) => app.notice(NoticeLevel::Error, error),
            },
            KeyCode::Char('r' | 'R') => return Ok(Some(DashboardAction::CheckUpdate)),
            KeyCode::Char('i' | 'I') => {
                if app.cast_active || app.receiver_active {
                    app.notice(
                        NoticeLevel::Warning,
                        "Stop the active cast and local receiver before updating",
                    );
                } else if app.available_update.is_none() {
                    app.notice(NoticeLevel::Info, "No CLI update is available");
                } else {
                    match crate::update_installer::preflight() {
                        Ok(_) => app.overlay = Overlay::UpdateConfirm,
                        Err(error) => {
                            app.update_error = Some(error);
                            app.overlay = Overlay::UpdateManual;
                        }
                    }
                }
            }
            _ => {}
        }
    }
    Ok(None)
}

fn activate(app: &mut App) -> Result<Option<DashboardAction>, String> {
    match app.section {
        Section::Home => {
            app.navigate_to(Section::Cast);
            Ok(None)
        }
        Section::Cast => match app.content_selection.min(3) {
            0 => {
                app.overlay = Overlay::UrlInput;
                app.input.clear();
                Ok(None)
            }
            1 => {
                app.overlay = Overlay::Files;
                Ok(None)
            }
            2 if app.cast_active => Ok(Some(DashboardAction::CastCommand(
                crate::send::CastCommand::Stop,
            ))),
            2 => {
                if app.source.is_none() {
                    app.notice(NoticeLevel::Warning, "Select media first");
                } else if app.devices.is_empty() {
                    app.notice(
                        NoticeLevel::Warning,
                        "No receivers found yet; wait for discovery or rescan",
                    );
                } else {
                    app.overlay = Overlay::ReceiverPicker;
                }
                Ok(None)
            }
            _ => {
                if let Some(source) = app.source.clone() {
                    Ok(Some(DashboardAction::StartBrowserCast { source }))
                } else {
                    app.notice(NoticeLevel::Warning, "Select media first");
                    Ok(None)
                }
            }
        },
        Section::Remote => {
            if remote_control_enabled(app, app.content_selection) {
                Ok(Some(remote_action(app.content_selection)))
            } else {
                app.notice(
                    NoticeLevel::Info,
                    "This receiver does not support the selected control",
                );
                Ok(None)
            }
        }
        Section::Discover => {
            if let Some(device) = app.devices.get(app.device_selection) {
                app.notice(
                    NoticeLevel::Info,
                    format!("{} selected; press P to make it preferred", device.name),
                );
            }
            Ok(None)
        }
        Section::Receiver => Ok(Some(if app.receiver_active {
            DashboardAction::ReceiverCommand(crate::receive::ReceiverDashboardCommand::StopHost)
        } else {
            DashboardAction::StartReceiver
        })),
        Section::Settings => {
            app.cycle_theme();
            Ok(None)
        }
        Section::Help => {
            app.overlay = Overlay::Help;
            Ok(None)
        }
    }
}

fn remote_action(selection: usize) -> DashboardAction {
    let command = match selection.min(9) {
        0 => crate::send::CastCommand::PlayPause,
        1 => crate::send::CastCommand::SeekRelative(-10),
        2 => crate::send::CastCommand::SeekRelative(10),
        3 => crate::send::CastCommand::VolumeDelta(-0.05),
        4 => crate::send::CastCommand::VolumeDelta(0.05),
        5 => crate::send::CastCommand::ToggleMute,
        6 => crate::send::CastCommand::ToggleLoop,
        7 => crate::send::CastCommand::SetSpeed(1.25),
        8 => crate::send::CastCommand::ToggleAudioBoost,
        _ => crate::send::CastCommand::Stop,
    };
    DashboardAction::CastCommand(command)
}

fn remote_control_enabled(app: &App, selection: usize) -> bool {
    match selection.min(9) {
        0 => app.remote.capabilities.play_pause,
        1 | 2 => app.remote.capabilities.seek,
        3 | 4 => app.remote.capabilities.volume,
        5 => app.remote.capabilities.mute,
        6 => app.remote.capabilities.looping,
        7 => app.remote.capabilities.speed,
        8 => app.remote.capabilities.audio_boost,
        _ => app.cast_active,
    }
}

fn handle_url_input(app: &mut App, key: KeyEvent) -> Result<Option<DashboardAction>, String> {
    match key.code {
        KeyCode::Esc => app.overlay = Overlay::None,
        KeyCode::Enter => match crate::send::validate_media_target(&app.input) {
            Ok(()) => {
                app.source = Some(app.input.trim().to_owned());
                app.overlay = Overlay::None;
                app.notice(NoticeLevel::Success, "Media source is ready");
            }
            Err(error) => app.notice(NoticeLevel::Error, error),
        },
        KeyCode::Backspace => {
            app.input.pop();
        }
        KeyCode::Char(character)
            if !key
                .modifiers
                .intersects(KeyModifiers::CONTROL | KeyModifiers::ALT) =>
        {
            app.input.push(character);
        }
        _ => {}
    }
    Ok(None)
}

fn handle_file_picker(app: &mut App, key: KeyEvent) -> Result<Option<DashboardAction>, String> {
    if app.file_picker.filtering {
        match key.code {
            KeyCode::Esc | KeyCode::Enter => app.file_picker.filtering = false,
            KeyCode::Backspace => {
                app.file_picker.query.pop();
                if let Err(error) = app.file_picker.refresh() {
                    app.notice(NoticeLevel::Error, error);
                }
            }
            KeyCode::Char(character)
                if !key
                    .modifiers
                    .intersects(KeyModifiers::CONTROL | KeyModifiers::ALT) =>
            {
                app.file_picker.query.push(character);
                if let Err(error) = app.file_picker.refresh() {
                    app.notice(NoticeLevel::Error, error);
                }
            }
            _ => {}
        }
        return Ok(None);
    }

    if app.config.matches("down", key) {
        app.file_picker.move_down();
    } else if app.config.matches("up", key) {
        app.file_picker.move_up();
    } else {
        match key.code {
            KeyCode::Esc => app.overlay = Overlay::None,
            KeyCode::Backspace | KeyCode::Left | KeyCode::Char('h') => {
                if let Err(error) = app.file_picker.parent() {
                    app.notice(NoticeLevel::Error, error);
                }
            }
            KeyCode::Enter | KeyCode::Right | KeyCode::Char('l') => match app.file_picker.enter() {
                Ok(Some(path)) => {
                    app.source = Some(path.to_string_lossy().into_owned());
                    app.overlay = Overlay::None;
                    app.notice(NoticeLevel::Success, "Local media selected");
                }
                Ok(None) => {}
                Err(error) => app.notice(NoticeLevel::Error, error),
            },
            KeyCode::Char('/') => app.file_picker.filtering = true,
            KeyCode::Char('.') => {
                app.file_picker.show_hidden = !app.file_picker.show_hidden;
                let _ = app.file_picker.refresh();
            }
            KeyCode::Char('a' | 'A') => {
                app.file_picker.media_only = !app.file_picker.media_only;
                let _ = app.file_picker.refresh();
            }
            _ => {}
        }
    }
    Ok(None)
}

fn handle_receiver_picker(app: &mut App, key: KeyEvent) -> Result<Option<DashboardAction>, String> {
    if app.config.matches("down", key) {
        if app.device_selection + 1 < app.devices.len() {
            app.device_selection += 1;
        }
        return Ok(None);
    }
    if app.config.matches("up", key) {
        app.device_selection = app.device_selection.saturating_sub(1);
        return Ok(None);
    }
    if app.config.matches("rescan", key) {
        app.rescan();
        return Ok(None);
    }
    if app.config.matches("preferred", key) {
        app.save_selected_preferred();
        return Ok(None);
    }
    if matches!(key.code, KeyCode::Char('m' | 'M')) {
        app.overlay = Overlay::ManualReceiver;
        app.input.clear();
        return Ok(None);
    }
    if app.config.matches("back", key) || app.config.matches("quit", key) {
        app.overlay = Overlay::None;
        return Ok(None);
    }
    if app.config.matches("select", key) {
        let Some(source) = app.source.clone() else {
            app.overlay = Overlay::None;
            app.notice(NoticeLevel::Warning, "Select media first");
            return Ok(None);
        };
        let Some(receiver) = app.devices.get(app.device_selection).cloned() else {
            app.notice(NoticeLevel::Warning, "No receiver selected");
            return Ok(None);
        };
        if !receiver_cast_supported(receiver.protocol) {
            app.notice(
                NoticeLevel::Warning,
                format!(
                    "{} discovery is available, but casting is not supported",
                    protocol_label(receiver.protocol)
                ),
            );
            return Ok(None);
        }
        app.overlay = Overlay::None;
        return Ok(Some(DashboardAction::StartCast { source, receiver }));
    }
    Ok(None)
}

fn handle_palette(app: &mut App, key: KeyEvent) -> Result<Option<DashboardAction>, String> {
    if app.config.matches("down", key) {
        app.move_down();
    } else if app.config.matches("up", key) {
        app.move_up();
    } else if app.config.matches("back", key) || app.config.matches("palette", key) {
        app.overlay = Overlay::None;
    } else if app.config.matches("select", key) {
        let section = Section::ALL[app.palette_selection];
        app.overlay = Overlay::None;
        app.navigate_to(section);
    }
    Ok(None)
}

fn handle_mouse(app: &mut App, mouse: MouseEvent) {
    match mouse.kind {
        MouseEventKind::ScrollDown => app.move_down(),
        MouseEventKind::ScrollUp => app.move_up(),
        MouseEventKind::Down(_) if mouse.column < 24 && mouse.row >= 4 => {
            let index = mouse.row.saturating_sub(4) as usize;
            if let Some(section) = Section::ALL.get(index).copied() {
                app.navigate_to(section);
                app.focus = Focus::Navigation;
            }
        }
        _ => {}
    }
}

fn render_receiver_picker(frame: &mut Frame<'_>, app: &App) {
    let area = centered(frame.area(), 84, 66);
    frame.render_widget(Clear, area);
    let items = if app.devices.is_empty() {
        vec![ListItem::new(" Searching for receivers…")]
    } else {
        let preferred = PreferredDevice::load();
        app.devices
            .iter()
            .map(|receiver| {
                ListItem::new(Line::from(vec![
                    Span::styled(
                        if preferred
                            .as_ref()
                            .is_some_and(|value| receiver_matches_preferred(receiver, value))
                        {
                            " *"
                        } else {
                            "  "
                        },
                        Style::default().fg(app.theme.success),
                    ),
                    Span::styled(
                        format!("{:<12}", protocol_label(receiver.protocol)),
                        app.theme.accent,
                    ),
                    Span::raw(truncate(&receiver.name, 34)),
                    Span::styled(
                        format!("  {}", address_summary(receiver)),
                        app.theme.muted(),
                    ),
                ]))
            })
            .collect()
    };
    let selected = (!app.devices.is_empty()).then_some(app.device_selection);
    let mut state = ListState::default().with_selected(selected);
    frame.render_stateful_widget(
        List::new(items)
            .block(panel_block(app, "Cast to receiver"))
            .style(app.theme.base())
            .highlight_style(app.theme.selected()),
        area,
        &mut state,
    );
    let hint = Rect {
        x: area.x.saturating_add(2),
        y: area.bottom().saturating_sub(2),
        width: area.width.saturating_sub(4),
        height: 1,
    };
    frame.render_widget(
        Paragraph::new("Enter cast   M manual address   P preferred   R rescan   Esc cancel")
            .style(app.theme.muted()),
        hint,
    );
}

fn render(frame: &mut Frame<'_>, app: &mut App) {
    frame.render_widget(Block::default().style(app.theme.base()), frame.area());
    let area = frame.area();
    if area.width < 40 || area.height < 12 {
        render_too_small(frame, app);
        return;
    }

    let vertical = Layout::vertical([
        Constraint::Length(3),
        Constraint::Min(6),
        Constraint::Length(2),
    ])
    .split(area);
    render_header(frame, vertical[0], app);
    render_body(frame, vertical[1], app);
    render_footer(frame, vertical[2], app);

    match app.overlay {
        Overlay::UrlInput => render_url_input(frame, app),
        Overlay::Files => render_file_picker(frame, app),
        Overlay::Help => render_help_overlay(frame, app),
        Overlay::Palette => render_palette(frame, app),
        Overlay::ReceiverPicker => render_receiver_picker(frame, app),
        Overlay::Pairing => render_pairing(frame, app),
        Overlay::BrowserPairing => render_browser_pairing(frame, app),
        Overlay::BrowserHost => render_browser_host(frame, app),
        Overlay::ManualReceiver => render_manual_receiver(frame, app),
        Overlay::UpdateConfirm => render_update_confirm(frame, app),
        Overlay::UpdateProgress => render_update_progress(frame, app),
        Overlay::UpdateManual => render_update_manual(frame, app),
        Overlay::Quit => render_quit(frame, app),
        Overlay::None => {}
    }
}

fn render_header(frame: &mut Frame<'_>, area: Rect, app: &App) {
    let status = if app.cast_active {
        "casting"
    } else if app.scanning {
        "scanning"
    } else {
        "ready"
    };
    let mut spans = vec![
        Span::styled(" PlayBridge ", app.theme.title()),
        Span::styled(
            format!("{} · {} receivers", status, app.devices.len()),
            app.theme.muted(),
        ),
    ];
    if let Some(update) = &app.available_update {
        spans.push(Span::styled(
            format!(" · Update v{}", update.version),
            Style::default().fg(app.theme.warning),
        ));
    }
    let title = Line::from(spans);
    frame.render_widget(
        Paragraph::new(title)
            .block(panel_block(app, ""))
            .alignment(Alignment::Left),
        area,
    );
}

fn render_body(frame: &mut Frame<'_>, area: Rect, app: &mut App) {
    if area.width < 70 {
        render_section(frame, area, app, false);
        return;
    }
    let navigation_width = if area.width >= 100 { 23 } else { 19 };
    let columns =
        Layout::horizontal([Constraint::Length(navigation_width), Constraint::Min(30)]).split(area);
    render_navigation(frame, columns[0], app);
    render_section(frame, columns[1], app, area.width >= 100);
}

fn render_navigation(frame: &mut Frame<'_>, area: Rect, app: &App) {
    let items = Section::ALL
        .iter()
        .map(|section| {
            let marker = if app.config.ui.unicode { "›" } else { ">" };
            let prefix = if *section == app.section { marker } else { " " };
            let badge = match section {
                Section::Remote if app.cast_active => "  •",
                Section::Receiver
                    if app
                        .receiver_state
                        .playback
                        .as_ref()
                        .is_some_and(|state| state.state != "idle") =>
                {
                    "  •"
                }
                _ => "",
            };
            ListItem::new(format!(" {prefix} {}{badge}", section.label()))
        })
        .collect::<Vec<_>>();
    let mut state = ListState::default().with_selected(Some(app.navigation_index()));
    let list = List::new(items)
        .block(panel_block(app, "Navigate"))
        .style(app.theme.base())
        .highlight_style(if app.focus == Focus::Navigation {
            app.theme.selected()
        } else {
            app.theme.base().fg(app.theme.accent)
        });
    frame.render_stateful_widget(list, area, &mut state);
}

fn render_section(frame: &mut Frame<'_>, area: Rect, app: &mut App, wide: bool) {
    match app.section {
        Section::Home => render_home(frame, area, app),
        Section::Cast => render_cast(frame, area, app, wide),
        Section::Remote => render_remote(frame, area, app),
        Section::Discover => render_discover(frame, area, app, wide),
        Section::Receiver => render_receiver(frame, area, app),
        Section::Settings => render_settings(frame, area, app),
        Section::Help => render_help(frame, area, app),
    }
}

fn render_remote(frame: &mut Frame<'_>, area: Rect, app: &mut App) {
    let rows = Layout::vertical([Constraint::Length(9), Constraint::Min(8)]).split(area);
    let snapshot = app.remote.snapshot.as_ref();
    let position = snapshot.map_or(0, |value| value.position_ms);
    let duration = snapshot.map_or(0, |value| value.duration_ms);
    let progress_width = rows[0].width.saturating_sub(8) as usize;
    let filled = if duration == 0 {
        0
    } else {
        ((position as f64 / duration as f64) * progress_width as f64) as usize
    };
    let progress = format!(
        "{}{}",
        "━".repeat(filled.min(progress_width)),
        "─".repeat(progress_width.saturating_sub(filled))
    );
    let details = vec![
        Line::styled(
            snapshot.map_or("No active cast", |value| value.title.as_str()),
            app.theme.title(),
        ),
        Line::raw(format!(
            "Receiver  {}",
            app.cast_target.as_deref().unwrap_or("—")
        )),
        Line::raw(format!(
            "State     {}",
            snapshot.map_or("disconnected", |value| value.state.as_str())
        )),
        Line::raw(progress),
        Line::styled(
            format!("{} / {}", format_millis(position), format_millis(duration)),
            app.theme.muted(),
        ),
    ];
    frame.render_widget(
        Paragraph::new(details).block(panel_block(app, "Now playing")),
        rows[0],
    );

    let controls = [
        ("Play / pause", app.remote.capabilities.play_pause),
        ("Seek -10 seconds", app.remote.capabilities.seek),
        ("Seek +10 seconds", app.remote.capabilities.seek),
        ("Volume down", app.remote.capabilities.volume),
        ("Volume up", app.remote.capabilities.volume),
        ("Mute", app.remote.capabilities.mute),
        ("Loop", app.remote.capabilities.looping),
        ("Speed 1.25×", app.remote.capabilities.speed),
        ("Audio boost", app.remote.capabilities.audio_boost),
        ("Stop playback", app.cast_active),
    ];
    app.content_selection = app.content_selection.min(controls.len() - 1);
    let items = controls
        .iter()
        .map(|(label, enabled)| {
            ListItem::new(format!(
                " {label}{}",
                if *enabled { "" } else { "  (unsupported)" }
            ))
            .style(if *enabled {
                app.theme.base()
            } else {
                app.theme.muted()
            })
        })
        .collect::<Vec<_>>();
    let mut state = ListState::default().with_selected(Some(app.content_selection));
    frame.render_stateful_widget(
        List::new(items)
            .block(panel_block(
                app,
                "Remote   Space play/pause  A/D seek  +/- volume  X stop",
            ))
            .highlight_style(content_highlight_style(app)),
        rows[1],
        &mut state,
    );
}

fn render_home(frame: &mut Frame<'_>, area: Rect, app: &App) {
    let preferred = PreferredDevice::load()
        .map(|device| format!("Preferred receiver: {} ({})", device.name, device.protocol))
        .unwrap_or_else(|| "No preferred receiver saved".into());
    let source = app
        .source
        .as_deref()
        .map(display_source)
        .unwrap_or_else(|| "No media source selected".into());
    let text = Text::from(vec![
        Line::styled("Cast without leaving your terminal", app.theme.title()),
        Line::raw(""),
        Line::raw("Enter  Open Cast and choose a source"),
        Line::raw("  d    Inspect discovered receivers"),
        Line::raw("  ?    Open contextual help"),
        Line::raw(""),
        Line::styled(preferred, app.theme.muted()),
        Line::styled(source, app.theme.muted()),
    ]);
    frame.render_widget(
        Paragraph::new(text)
            .block(panel_block(app, "Home"))
            .wrap(Wrap { trim: false }),
        area,
    );
}

fn render_cast(frame: &mut Frame<'_>, area: Rect, app: &mut App, wide: bool) {
    let chunks = if wide {
        Layout::horizontal([Constraint::Percentage(58), Constraint::Percentage(42)]).split(area)
    } else {
        Layout::horizontal([Constraint::Percentage(100), Constraint::Length(0)]).split(area)
    };
    let actions = [
        "Enter a URL or path",
        "Browse local media",
        if app.cast_active {
            "Stop current cast"
        } else {
            "Cast selected source"
        },
        "Use browser receiver",
    ];
    app.content_selection = app.content_selection.min(actions.len() - 1);
    let items = actions
        .iter()
        .enumerate()
        .map(|(index, label)| {
            let suffix = if index >= 2 && app.source.is_none() {
                "  (select media first)"
            } else {
                ""
            };
            ListItem::new(format!(" {label}{suffix}"))
        })
        .collect::<Vec<_>>();
    let mut state = ListState::default().with_selected(Some(app.content_selection));
    frame.render_stateful_widget(
        List::new(items)
            .block(panel_block(app, "Cast"))
            .style(app.theme.base())
            .highlight_style(content_highlight_style(app)),
        chunks[0],
        &mut state,
    );
    if wide {
        let source = app
            .source
            .as_deref()
            .map(display_source)
            .unwrap_or_else(|| "Nothing selected".into());
        let mut details = vec![
            Line::styled("Current source", app.theme.title()),
            Line::raw(""),
            Line::raw(source),
            Line::raw(""),
            Line::styled(
                if app.cast_active {
                    format!(
                        "Casting to {}   Open Remote for status and controls",
                        app.cast_target.as_deref().unwrap_or("receiver")
                    )
                } else {
                    "U URL   F Files   B Browser".into()
                },
                app.theme.muted(),
            ),
        ];
        if !app.browser_urls.is_empty() {
            details.push(Line::raw(""));
            details.push(Line::styled("Browser receiver URLs", app.theme.title()));
            details.extend(app.browser_urls.iter().map(|url| Line::raw(url.clone())));
        }
        frame.render_widget(
            Paragraph::new(details)
                .block(panel_block(app, "Source"))
                .wrap(Wrap { trim: false }),
            chunks[1],
        );
    }
}

fn render_discover(frame: &mut Frame<'_>, area: Rect, app: &App, wide: bool) {
    let chunks = if wide {
        Layout::horizontal([Constraint::Percentage(57), Constraint::Percentage(43)]).split(area)
    } else {
        Layout::horizontal([Constraint::Percentage(100), Constraint::Length(0)]).split(area)
    };
    let preferred = PreferredDevice::load();
    let items = if app.devices.is_empty() {
        vec![ListItem::new(" Searching the local network…")]
    } else {
        app.devices
            .iter()
            .map(|receiver| {
                ListItem::new(Line::from(vec![
                    Span::styled(
                        if preferred
                            .as_ref()
                            .is_some_and(|value| receiver_matches_preferred(receiver, value))
                        {
                            " *"
                        } else {
                            "  "
                        },
                        Style::default().fg(app.theme.success),
                    ),
                    Span::styled(
                        format!("{:<12}", protocol_label(receiver.protocol)),
                        Style::default().fg(app.theme.accent),
                    ),
                    Span::raw(&receiver.name),
                ]))
            })
            .collect()
    };
    let mut state = ListState::default()
        .with_selected((!app.devices.is_empty()).then_some(app.device_selection));
    frame.render_stateful_widget(
        List::new(items)
            .block(panel_block(app, "Receivers"))
            .style(app.theme.base())
            .highlight_style(content_highlight_style(app)),
        chunks[0],
        &mut state,
    );
    if wide {
        render_device_details(frame, chunks[1], app);
    }
}

fn render_device_details(frame: &mut Frame<'_>, area: Rect, app: &App) {
    let lines = app.devices.get(app.device_selection).map_or_else(
        || vec![Line::styled("No receiver selected", app.theme.muted())],
        |receiver| {
            vec![
                Line::styled(receiver.name.clone(), app.theme.title()),
                Line::raw(""),
                Line::raw(format!("Protocol  {}", protocol_label(receiver.protocol))),
                Line::raw(format!("Address   {}", address_summary(receiver))),
                Line::raw(format!(
                    "Port      {}",
                    receiver
                        .wss_port
                        .or(receiver.port)
                        .map_or_else(|| "—".into(), |p| p.to_string())
                )),
                Line::raw(""),
                Line::styled("P  Save as preferred", app.theme.muted()),
                Line::styled("R  Rescan", app.theme.muted()),
            ]
        },
    );
    frame.render_widget(
        Paragraph::new(lines)
            .block(panel_block(app, "Details"))
            .wrap(Wrap { trim: false }),
        area,
    );
}

fn render_receiver(frame: &mut Frame<'_>, area: Rect, app: &App) {
    let state = if app.receiver_active {
        "Running"
    } else {
        "Stopped"
    };
    let playback = app.receiver_state.playback.as_ref();
    let rows = [
        Row::new(["Status".to_owned(), state.to_owned()]),
        Row::new([
            "Name".to_owned(),
            app.receiver_state
                .name
                .clone()
                .unwrap_or_else(|| "—".into()),
        ]),
        Row::new([
            "Port".to_owned(),
            app.receiver_state
                .port
                .map_or_else(|| "—".into(), |port| port.to_string()),
        ]),
        Row::new([
            "Clients".to_owned(),
            format!(
                "{} connected / {} authenticated",
                app.receiver_state.clients, app.receiver_state.authenticated_clients
            ),
        ]),
        Row::new([
            "Pairing".to_owned(),
            app.receiver_state.pairing.as_ref().map_or_else(
                || "No pending request".into(),
                |(name, code)| format!("{name}: {code}"),
            ),
        ]),
        Row::new([
            "Playback".to_owned(),
            playback.map_or_else(
                || "Idle".into(),
                |value| {
                    format!(
                        "{} — {}",
                        value.state,
                        value.title.as_deref().unwrap_or("untitled")
                    )
                },
            ),
        ]),
        Row::new([
            "Position".to_owned(),
            playback.map_or_else(
                || "00:00 / 00:00".into(),
                |value| {
                    format!(
                        "{} / {}",
                        format_millis(value.position_ms),
                        format_millis(value.duration_ms)
                    )
                },
            ),
        ]),
        Row::new([
            "Queue".to_owned(),
            playback.map_or_else(
                || "Empty".into(),
                |value| {
                    if value.queue_len == 0 {
                        "Empty".into()
                    } else {
                        format!("{} of {}", value.current_index + 1, value.queue_len)
                    }
                },
            ),
        ]),
        Row::new([
            "Audio".to_owned(),
            playback.map_or_else(
                || "Volume —".into(),
                |value| {
                    format!(
                        "Volume {:.0}%{}",
                        value.volume,
                        if value.muted { " (muted)" } else { "" }
                    )
                },
            ),
        ]),
        Row::new([
            "Options".to_owned(),
            playback.map_or_else(
                || "Speed 1.00× / Loop off".into(),
                |value| {
                    format!(
                        "Speed {:.2}× / Loop {}",
                        value.speed,
                        if value.looping { "on" } else { "off" }
                    )
                },
            ),
        ]),
    ];
    let table = Table::new(rows, [Constraint::Length(14), Constraint::Min(20)])
        .block(panel_block(app, "Receiver"))
        .style(app.theme.base())
        .header(
            Row::new(["Component", "Status"])
                .style(app.theme.title())
                .bottom_margin(1),
        );
    frame.render_widget(table, area);
    let hint = Rect {
        x: area.x.saturating_add(2),
        y: area.bottom().saturating_sub(3),
        width: area.width.saturating_sub(4),
        height: 1,
    };
    frame.render_widget(
        Paragraph::new(if app.receiver_active {
            "Space play/pause  A/D seek  +/- volume  [/] queue  X stop playback  Enter stop host"
        } else {
            "Enter  Start receiver   Receiver hosting stays in this dashboard"
        })
        .style(app.theme.muted()),
        hint,
    );
}

fn format_millis(milliseconds: u64) -> String {
    let seconds = milliseconds / 1000;
    let hours = seconds / 3600;
    let minutes = (seconds % 3600) / 60;
    let seconds = seconds % 60;
    if hours > 0 {
        format!("{hours:02}:{minutes:02}:{seconds:02}")
    } else {
        format!("{minutes:02}:{seconds:02}")
    }
}

fn render_settings(frame: &mut Frame<'_>, area: Rect, app: &App) {
    let preferred = PreferredDevice::load()
        .map(|device| format!("{} ({})", device.name, device.protocol))
        .unwrap_or_else(|| "Not configured".into());
    let path = config_path()
        .map(|path| path.display().to_string())
        .unwrap_or_else(|| "Unavailable".into());
    let rows = [
        Row::new(vec![
            "Version".to_owned(),
            format!("v{}", env!("CARGO_PKG_VERSION")),
        ]),
        Row::new(vec!["Updates".to_owned(), update_status_text(app)]),
        Row::new(vec!["Theme".to_owned(), app.config.ui.theme.clone()]),
        Row::new(vec![
            "Mouse".to_owned(),
            on_off(app.config.ui.mouse).to_owned(),
        ]),
        Row::new(vec![
            "Unicode".to_owned(),
            on_off(app.config.ui.unicode).to_owned(),
        ]),
        Row::new(vec!["Preferred".to_owned(), preferred]),
        Row::new(vec!["Config".to_owned(), path]),
    ];
    frame.render_widget(
        Table::new(rows, [Constraint::Length(13), Constraint::Min(20)])
            .block(panel_block(app, "Settings"))
            .style(app.theme.base())
            .header(
                Row::new(["Option", "Value"])
                    .style(app.theme.title())
                    .bottom_margin(1),
            )
            .footer(
                Row::new([
                    "",
                    "R check updates   I install   T theme   M mouse   U Unicode   C clear",
                ])
                .style(app.theme.muted()),
            ),
        area,
    );
}

fn update_status_text(app: &App) -> String {
    match app.update_status {
        UpdateStatus::Disabled => "Disabled by environment".into(),
        UpdateStatus::Idle => "Not checked".into(),
        UpdateStatus::Checking => "Checking…".into(),
        UpdateStatus::UpToDate => "Up to date".into(),
        UpdateStatus::Available => app
            .available_update
            .as_ref()
            .map(|update| format!("v{} available", update.version))
            .unwrap_or_else(|| "Available".into()),
        UpdateStatus::Downloading => app
            .update_progress
            .map(format_update_progress)
            .unwrap_or_else(|| "Preparing download…".into()),
        UpdateStatus::Error => app
            .update_error
            .as_deref()
            .map(|error| format!("Check failed: {error}"))
            .unwrap_or_else(|| "Check failed".into()),
    }
}

fn format_update_progress(progress: crate::update_installer::InstallProgress) -> String {
    match progress.total {
        Some(total) if total > 0 => format!(
            "Downloading {:.0}% ({}/{})",
            progress.downloaded as f64 / total as f64 * 100.0,
            human_size(progress.downloaded),
            human_size(total)
        ),
        _ => format!("Downloading {}", human_size(progress.downloaded)),
    }
}

fn render_help(frame: &mut Frame<'_>, area: Rect, app: &App) {
    frame.render_widget(
        Paragraph::new(help_text(app))
            .block(panel_block(app, "Help"))
            .wrap(Wrap { trim: false }),
        area,
    );
}

fn render_footer(frame: &mut Frame<'_>, area: Rect, app: &App) {
    let line = if let Some(notice) = &app.notice {
        let style = match notice.level {
            NoticeLevel::Info => Style::default().fg(app.theme.accent),
            NoticeLevel::Success => Style::default().fg(app.theme.success),
            NoticeLevel::Warning => Style::default().fg(app.theme.warning),
            NoticeLevel::Error => Style::default().fg(app.theme.error),
        };
        Line::styled(
            truncate(&notice.message, area.width.saturating_sub(2) as usize),
            style,
        )
    } else {
        Line::styled(
            " ↑/↓ or j/k  Navigate   Enter  Select   Ctrl+P  Commands   ?  Help   Q  Quit",
            app.theme.muted(),
        )
    };
    frame.render_widget(Paragraph::new(line).style(app.theme.base()), area);
}

fn render_url_input(frame: &mut Frame<'_>, app: &App) {
    let area = centered(frame.area(), 72, 9);
    frame.render_widget(Clear, area);
    frame.render_widget(
        Paragraph::new(vec![
            Line::raw("Enter an HTTP(S) URL or a local media path:"),
            Line::raw(""),
            Line::styled(format!("> {}", app.input), app.theme.title()),
            Line::raw(""),
            Line::styled(
                "Enter confirm   Esc cancel   paste supported",
                app.theme.muted(),
            ),
        ])
        .block(panel_block(app, "Media source"))
        .wrap(Wrap { trim: false }),
        area,
    );
}

fn render_file_picker(frame: &mut Frame<'_>, app: &mut App) {
    let area = centered(frame.area(), 92, 82);
    frame.render_widget(Clear, area);
    let vertical = Layout::vertical([
        Constraint::Length(3),
        Constraint::Min(7),
        Constraint::Length(2),
    ])
    .split(area);
    let path = format!(
        " {}{}",
        app.file_picker.directory.display(),
        if app.file_picker.filtering {
            format!("  /{}", app.file_picker.query)
        } else {
            String::new()
        }
    );
    frame.render_widget(
        Paragraph::new(path)
            .block(panel_block(app, "Choose local media"))
            .style(app.theme.title()),
        vertical[0],
    );
    let columns = if area.width >= 80 {
        Layout::horizontal([Constraint::Percentage(65), Constraint::Percentage(35)])
            .split(vertical[1])
    } else {
        Layout::horizontal([Constraint::Percentage(100), Constraint::Length(0)]).split(vertical[1])
    };
    let entries = app
        .file_picker
        .entries
        .iter()
        .map(|entry| {
            let icon = if entry.is_dir {
                if app.config.ui.unicode { "▸" } else { ">" }
            } else if app.config.ui.unicode {
                "•"
            } else {
                "-"
            };
            ListItem::new(format!(" {icon} {}", entry.name))
        })
        .collect::<Vec<_>>();
    let mut state = ListState::default()
        .with_selected((!app.file_picker.entries.is_empty()).then_some(app.file_picker.selected));
    frame.render_stateful_widget(
        List::new(entries)
            .block(panel_block(app, "Files"))
            .style(app.theme.base())
            .highlight_style(app.theme.selected()),
        columns[0],
        &mut state,
    );
    if columns[1].width > 0 {
        let details = app.file_picker.selected_entry().map_or_else(
            || vec![Line::styled("No entry selected", app.theme.muted())],
            |entry| {
                vec![
                    Line::styled(entry.name.clone(), app.theme.title()),
                    Line::raw(""),
                    Line::raw(if entry.is_dir {
                        "Directory"
                    } else {
                        "Media file"
                    }),
                    Line::raw(format!("Size  {}", human_size(entry.size))),
                    Line::raw(format!(
                        "Modified  {}",
                        entry.modified.map_or("unknown".into(), format_system_time)
                    )),
                ]
            },
        );
        frame.render_widget(
            Paragraph::new(details)
                .block(panel_block(app, "Metadata"))
                .wrap(Wrap { trim: false }),
            columns[1],
        );
    }
    frame.render_widget(
        Paragraph::new(format!(
            " / filter   . hidden   A all files ({})   Backspace parent   Esc cancel",
            if app.file_picker.media_only {
                "off"
            } else {
                "on"
            }
        ))
        .style(app.theme.muted()),
        vertical[2],
    );
}

fn render_help_overlay(frame: &mut Frame<'_>, app: &App) {
    let area = centered(frame.area(), 76, 72);
    frame.render_widget(Clear, area);
    frame.render_widget(
        Paragraph::new(help_text(app))
            .block(panel_block(app, "Keyboard help"))
            .wrap(Wrap { trim: false }),
        area,
    );
}

fn render_palette(frame: &mut Frame<'_>, app: &App) {
    let area = centered(frame.area(), 58, 54);
    frame.render_widget(Clear, area);
    let items = Section::ALL
        .iter()
        .map(|section| ListItem::new(format!("{}  {}", section.label(), section.description())))
        .collect::<Vec<_>>();
    let mut state = ListState::default().with_selected(Some(app.palette_selection));
    frame.render_stateful_widget(
        List::new(items)
            .block(panel_block(app, "Go to"))
            .style(app.theme.base())
            .highlight_style(app.theme.selected()),
        area,
        &mut state,
    );
}

fn render_quit(frame: &mut Frame<'_>, app: &App) {
    let area = centered(frame.area(), 52, 8);
    frame.render_widget(Clear, area);
    frame.render_widget(
        Paragraph::new(vec![
            Line::raw("Quit PlayBridge?"),
            Line::raw(""),
            Line::styled("Enter/Y quit   N/Esc cancel", app.theme.muted()),
        ])
        .block(panel_block(app, "Confirm"))
        .alignment(Alignment::Center),
        area,
    );
}

fn render_pairing(frame: &mut Frame<'_>, app: &App) {
    let area = centered(frame.area(), 58, 34);
    frame.render_widget(Clear, area);
    let device = app.pairing_device.as_deref().unwrap_or("receiver");
    let code = format!("{:_<6}", app.input);
    frame.render_widget(
        Paragraph::new(vec![
            Line::styled(format!("Pair with {device}"), app.theme.title()),
            Line::raw(""),
            Line::raw("Enter the six-digit code shown by the receiver:"),
            Line::raw(""),
            Line::styled(format!("        {code}"), app.theme.selected()),
            Line::raw(""),
            Line::styled(
                "Enter verify   Backspace edit   Esc cancel",
                app.theme.muted(),
            ),
        ])
        .block(panel_block(app, "Secure pairing"))
        .alignment(Alignment::Center)
        .wrap(Wrap { trim: false }),
        area,
    );
}

fn render_browser_pairing(frame: &mut Frame<'_>, app: &App) {
    let area = centered(frame.area(), 58, 32);
    frame.render_widget(Clear, area);
    let device = app.pairing_device.as_deref().unwrap_or("browser receiver");
    let code = format!("{:_<6}", app.input);
    frame.render_widget(
        Paragraph::new(vec![
            Line::styled(format!("Pair with {device}"), app.theme.title()),
            Line::raw(""),
            Line::raw("Enter the six-digit code shown in the browser:"),
            Line::raw(""),
            Line::styled(format!("        {code}"), app.theme.selected()),
            Line::raw(""),
            Line::styled(
                "Enter approve   Backspace edit   Esc cancel",
                app.theme.muted(),
            ),
        ])
        .block(panel_block(app, "Browser pairing"))
        .alignment(Alignment::Center)
        .wrap(Wrap { trim: false }),
        area,
    );
}

fn render_browser_host(frame: &mut Frame<'_>, app: &App) {
    let area = centered(frame.area(), 82, 38);
    frame.render_widget(Clear, area);
    let mut lines = vec![
        Line::styled(
            "Open a receiver URL in the target browser",
            app.theme.title(),
        ),
        Line::raw(""),
    ];
    lines.extend(app.browser_urls.iter().map(|url| Line::raw(url.clone())));
    lines.extend([
        Line::raw(""),
        Line::styled(
            "Waiting for the browser receiver…   Enter/Esc hide",
            app.theme.muted(),
        ),
    ]);
    frame.render_widget(
        Paragraph::new(lines)
            .block(panel_block(app, "Browser receiver"))
            .wrap(Wrap { trim: false }),
        area,
    );
}

fn render_manual_receiver(frame: &mut Frame<'_>, app: &App) {
    let area = centered(frame.area(), 72, 28);
    frame.render_widget(Clear, area);
    frame.render_widget(
        Paragraph::new(vec![
            Line::styled("Connect to a receiver manually", app.theme.title()),
            Line::raw(""),
            Line::raw("Use playbridge://host[:port], googlecast://host[:port],"),
            Line::raw("or roku://host[:port]."),
            Line::raw(""),
            Line::styled(format!("> {}", app.input), app.theme.selected()),
            Line::raw(""),
            Line::styled("Enter connect   Esc cancel", app.theme.muted()),
        ])
        .block(panel_block(app, "Manual receiver"))
        .wrap(Wrap { trim: false }),
        area,
    );
}

fn render_update_confirm(frame: &mut Frame<'_>, app: &App) {
    let area = centered(frame.area(), 66, 12);
    frame.render_widget(Clear, area);
    let Some(update) = &app.available_update else {
        return;
    };
    let size = update
        .manifest
        .asset
        .size
        .map(human_size)
        .unwrap_or_else(|| "unknown size".into());
    frame.render_widget(
        Paragraph::new(vec![
            Line::from(Span::styled(
                format!("Install PlayBridge CLI v{}?", update.version),
                app.theme.title(),
            )),
            Line::from(""),
            Line::from(format!("Download: {size}")),
            Line::from("The archive will be verified with SHA-256."),
            Line::from("The dashboard will close briefly and restart after installation."),
            Line::from(""),
            Line::from(Span::styled(
                "Enter/Y install   Esc/N cancel",
                app.theme.muted(),
            )),
        ])
        .block(panel_block(app, "CLI Update"))
        .wrap(Wrap { trim: false }),
        area,
    );
}

fn render_update_progress(frame: &mut Frame<'_>, app: &App) {
    let area = centered(frame.area(), 62, 9);
    frame.render_widget(Clear, area);
    let status = app
        .update_progress
        .map(format_update_progress)
        .unwrap_or_else(|| "Refreshing release metadata…".into());
    frame.render_widget(
        Paragraph::new(vec![
            Line::from(Span::styled("Installing CLI update", app.theme.title())),
            Line::from(""),
            Line::from(status),
            Line::from("The dashboard remains usable until replacement begins."),
            Line::from(""),
            Line::from(Span::styled("Esc cancel download", app.theme.muted())),
        ])
        .block(panel_block(app, "CLI Update"))
        .wrap(Wrap { trim: false }),
        area,
    );
}

fn render_update_manual(frame: &mut Frame<'_>, app: &App) {
    let area = centered(frame.area(), 76, 12);
    frame.render_widget(Clear, area);
    let reason = app
        .update_error
        .as_deref()
        .unwrap_or("This installation cannot be replaced automatically.");
    frame.render_widget(
        Paragraph::new(vec![
            Line::from(Span::styled("Manual update required", app.theme.title())),
            Line::from(""),
            Line::from(reason),
            Line::from(""),
            Line::from(crate::update_installer::manual_install_hint()),
            Line::from(""),
            Line::from(Span::styled("Enter/Esc close", app.theme.muted())),
        ])
        .block(panel_block(app, "CLI Update"))
        .wrap(Wrap { trim: false }),
        area,
    );
}

fn render_too_small(frame: &mut Frame<'_>, app: &App) {
    frame.render_widget(
        Paragraph::new(format!(
            "PlayBridge\n\nTerminal is {}×{}.\nResize to at least 40×12.\n\nCtrl+C exits.",
            frame.area().width,
            frame.area().height
        ))
        .style(app.theme.base())
        .alignment(Alignment::Center)
        .wrap(Wrap { trim: false }),
        frame.area(),
    );
}

fn panel_block<'a>(app: &App, title: &'a str) -> Block<'a> {
    let mut block = Block::default()
        .borders(Borders::ALL)
        .border_type(BorderType::Rounded)
        .border_style(Style::default().fg(app.theme.muted))
        .style(app.theme.base().bg(app.theme.panel))
        .padding(Padding::horizontal(1));
    if !title.is_empty() {
        block = block.title(Span::styled(format!(" {title} "), app.theme.title()));
    }
    block
}

fn content_highlight_style(app: &App) -> Style {
    if app.focus == Focus::Content {
        app.theme.selected()
    } else {
        app.theme.base()
    }
}

fn centered(area: Rect, max_width: u16, percent_height: u16) -> Rect {
    let width = area.width.saturating_sub(4).min(max_width);
    let height = area
        .height
        .saturating_mul(percent_height)
        .checked_div(100)
        .unwrap_or(0)
        .max(6)
        .min(area.height.saturating_sub(2));
    Rect {
        x: area.x + area.width.saturating_sub(width) / 2,
        y: area.y + area.height.saturating_sub(height) / 2,
        width,
        height,
    }
}

fn help_text(app: &App) -> Text<'static> {
    Text::from(vec![
        Line::styled("Navigation", app.theme.title()),
        Line::raw("  ↑/↓ or j/k     Move through lists"),
        Line::raw("  ←/→ or h/l     Move between navigation and content"),
        Line::raw("  Enter           Select"),
        Line::raw("  Esc/Backspace   Back"),
        Line::raw(""),
        Line::styled("Actions", app.theme.title()),
        Line::raw("  Ctrl+P or :     Command palette"),
        Line::raw("  R               Rescan receivers"),
        Line::raw("  P               Save preferred receiver"),
        Line::raw("  U / F / B       URL / files / browser receiver"),
        Line::raw("  Q               Quit"),
        Line::raw(""),
        Line::styled("Playback", app.theme.title()),
        Line::raw("  Space/P pause · arrows/A/D seek · W/S volume · M mute"),
        Line::raw("  O loop · B boost · 1–4 speed · X stop playback"),
    ])
}

fn protocol_rank(protocol: ReceiverProtocol) -> u8 {
    match protocol {
        ReceiverProtocol::PlayBridge => 0,
        ReceiverProtocol::Dlna => 1,
        ReceiverProtocol::Roku => 2,
        ReceiverProtocol::GoogleCast => 3,
        ReceiverProtocol::Dial => 4,
    }
}

fn protocol_label(protocol: ReceiverProtocol) -> &'static str {
    match protocol {
        ReceiverProtocol::PlayBridge => "PlayBridge",
        ReceiverProtocol::Dlna => "DLNA",
        ReceiverProtocol::Roku => "Roku",
        ReceiverProtocol::GoogleCast => "Google Cast",
        ReceiverProtocol::Dial => "DIAL",
    }
}

fn preferred_address(receiver: &Receiver) -> Option<&str> {
    receiver
        .addresses
        .iter()
        .find(|address| address.contains('.'))
        .or_else(|| receiver.addresses.first())
        .map(String::as_str)
}

fn receiver_matches_preferred(receiver: &Receiver, preferred: &PreferredDevice) -> bool {
    receiver.uuid.as_deref() == Some(preferred.uuid.as_str())
        || receiver.id.0 == preferred.uuid
        || (receiver.protocol.as_str() == preferred.protocol
            && preferred_address(receiver) == Some(preferred.address.as_str()))
}

fn receiver_cast_supported(protocol: ReceiverProtocol) -> bool {
    !matches!(protocol, ReceiverProtocol::Dial)
}

fn parse_manual_receiver(value: &str) -> Result<Receiver, String> {
    let url = reqwest::Url::parse(value.trim())
        .map_err(|_| "Enter a receiver such as playbridge://192.168.1.34:8765".to_owned())?;
    let protocol = match url.scheme() {
        "playbridge" | "native" => ReceiverProtocol::PlayBridge,
        "googlecast" | "chromecast" => ReceiverProtocol::GoogleCast,
        "roku" => ReceiverProtocol::Roku,
        _ => return Err("Manual receivers support playbridge, googlecast, or roku".into()),
    };
    let host = url
        .host_str()
        .ok_or_else(|| "Manual receiver address has no host".to_owned())?
        .to_owned();
    let default_port = match protocol {
        ReceiverProtocol::PlayBridge => 8765,
        ReceiverProtocol::GoogleCast => 8009,
        ReceiverProtocol::Roku => 8060,
        ReceiverProtocol::Dlna | ReceiverProtocol::Dial => unreachable!(),
    };
    let port = url.port().unwrap_or(default_port);
    Ok(Receiver {
        id: ReceiverId(format!("manual:{}:{host}:{port}", protocol.as_str())),
        protocol,
        name: format!("Manual {host}"),
        addresses: vec![host.clone()],
        port: Some(port),
        wss_port: (protocol == ReceiverProtocol::PlayBridge).then_some(port),
        location: None,
        uuid: (protocol == ReceiverProtocol::PlayBridge).then(|| format!("manual-{host}-{port}")),
    })
}

fn address_summary(receiver: &Receiver) -> String {
    let address = preferred_address(receiver).unwrap_or("unavailable");
    let additional = receiver.addresses.len().saturating_sub(1);
    if additional == 0 {
        address.into()
    } else {
        format!("{address} (+{additional})")
    }
}

fn display_source(source: &str) -> String {
    if source.starts_with("http://") || source.starts_with("https://") {
        let Ok(url) = reqwest::Url::parse(source) else {
            return "Remote URL".into();
        };
        let host = url.host_str().unwrap_or("remote host");
        return format!("{}://{host}{}", url.scheme(), url.path());
    }
    Path::new(source)
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or(source)
        .into()
}

fn truncate(value: &str, width: usize) -> String {
    if UnicodeWidthStr::width(value) <= width {
        return value.into();
    }
    if width <= 1 {
        return "…".into();
    }
    let mut output = String::new();
    for character in value.chars() {
        if UnicodeWidthStr::width(output.as_str()) + UnicodeWidthChar::width(character).unwrap_or(0)
            >= width
        {
            break;
        }
        output.push(character);
    }
    output.push('…');
    output
}

fn human_size(bytes: u64) -> String {
    const UNITS: &[&str] = &["B", "KiB", "MiB", "GiB", "TiB"];
    let mut size = bytes as f64;
    let mut unit = 0;
    while size >= 1024.0 && unit + 1 < UNITS.len() {
        size /= 1024.0;
        unit += 1;
    }
    if unit == 0 {
        format!("{bytes} B")
    } else {
        format!("{size:.1} {}", UNITS[unit])
    }
}

fn format_system_time(time: std::time::SystemTime) -> String {
    let Ok(age) = std::time::SystemTime::now().duration_since(time) else {
        return "just now".into();
    };
    match age.as_secs() {
        0..=59 => "just now".into(),
        60..=3_599 => format!("{}m ago", age.as_secs() / 60),
        3_600..=86_399 => format!("{}h ago", age.as_secs() / 3_600),
        _ => format!("{}d ago", age.as_secs() / 86_400),
    }
}

const fn on_off(value: bool) -> &'static str {
    if value { "on" } else { "off" }
}

#[cfg(test)]
mod tests {
    use super::*;
    use playbridge_cast_core::discovery::ReceiverId;
    use ratatui::{Terminal, backend::TestBackend};

    fn receiver(id: &str, protocol: ReceiverProtocol, name: &str, addresses: &[&str]) -> Receiver {
        Receiver {
            id: ReceiverId(id.to_owned()),
            protocol,
            name: name.to_owned(),
            addresses: addresses
                .iter()
                .map(|address| (*address).to_owned())
                .collect(),
            port: None,
            wss_port: None,
            location: None,
            uuid: None,
        }
    }

    fn rendered(width: u16, height: u16, section: Section) -> String {
        let mut app = App::new(UiConfig::default()).unwrap();
        app.section = section;
        render_app(width, height, app)
    }

    fn render_app(width: u16, height: u16, mut app: App) -> String {
        let backend = TestBackend::new(width, height);
        let mut terminal = Terminal::new(backend).unwrap();
        terminal.draw(|frame| render(frame, &mut app)).unwrap();
        let buffer = terminal.backend().buffer();
        (0..height)
            .map(|y| {
                (0..width)
                    .map(|x| buffer[(x, y)].symbol())
                    .collect::<String>()
                    .trim_end()
                    .to_owned()
            })
            .collect::<Vec<_>>()
            .join("\n")
    }

    #[test]
    fn wide_dashboard_has_navigation_and_home_content() {
        let output = rendered(120, 30, Section::Home);
        assert!(output.contains("Navigate"));
        assert!(output.contains("Cast without leaving your terminal"));
    }

    #[test]
    fn narrow_dashboard_collapses_navigation() {
        let output = rendered(60, 18, Section::Discover);
        assert!(!output.contains("Settings"));
        assert!(output.contains("Receivers"));
    }

    #[test]
    fn very_small_terminal_has_actionable_message() {
        let output = rendered(36, 10, Section::Home);
        assert!(output.contains("Resize to at least 40×12"));
    }

    #[test]
    fn available_update_is_persistent_in_header_and_settings() {
        let mut app = App::new(UiConfig::default()).unwrap();
        app.section = Section::Settings;
        app.update_status = UpdateStatus::Available;
        app.available_update = Some(available_update("9.8.7"));
        let output = render_app(120, 30, app);
        assert!(output.contains("Update v9.8.7"));
        assert!(output.contains("v9.8.7 available"));
        assert!(output.contains("R check updates"));
    }

    #[test]
    fn settings_update_keys_check_and_block_install_during_active_work() {
        let mut app = App::new(UiConfig::default()).unwrap();
        app.section = Section::Settings;
        app.available_update = Some(available_update("9.8.7"));
        let check = handle_key(
            &mut app,
            KeyEvent::new(KeyCode::Char('r'), KeyModifiers::NONE),
        )
        .unwrap();
        assert!(matches!(check, Some(DashboardAction::CheckUpdate)));

        app.cast_active = true;
        let install = handle_key(
            &mut app,
            KeyEvent::new(KeyCode::Char('i'), KeyModifiers::NONE),
        )
        .unwrap();
        assert!(install.is_none());
        assert!(
            app.notice
                .as_ref()
                .is_some_and(|notice| notice.message.contains("Stop the active cast"))
        );
    }

    fn available_update(version: &str) -> crate::update::AvailableUpdate {
        let target = crate::update::current_target().unwrap();
        crate::update::AvailableUpdate {
            version: semver::Version::parse(version).unwrap(),
            manifest: crate::update::UpdateManifest {
                schema_version: 1,
                product: "cli".into(),
                channel: "stable".into(),
                version: version.into(),
                published_at: "2026-08-01T00:00:00Z".into(),
                release_url: "https://github.com/playbridgeapp/playbridge/releases".into(),
                asset: crate::update::UpdateAsset {
                    name: target.asset_name.into(),
                    url: "https://github.com/playbridgeapp/playbridge/update.tar.gz".into(),
                    sha256: "a".repeat(64),
                    size: Some(1024),
                },
            },
        }
    }

    #[test]
    fn authenticated_source_display_drops_credentials_and_query() {
        assert_eq!(
            display_source("https://user:secret@example.com/video.m3u8?token=secret"),
            "https://example.com/video.m3u8"
        );
    }

    #[test]
    fn receiver_updates_preserve_selection_and_protocol_order() {
        let mut app = App::new(UiConfig::default()).unwrap();
        app.upsert_device(receiver(
            "dlna:bedroom",
            ReceiverProtocol::Dlna,
            "Bedroom TV",
            &["192.168.1.34"],
        ));
        app.device_selection = 0;
        app.upsert_device(receiver(
            "playbridge:desktop",
            ReceiverProtocol::PlayBridge,
            "Desktop",
            &["192.168.1.32"],
        ));
        app.upsert_device(receiver(
            "google_cast:speaker",
            ReceiverProtocol::GoogleCast,
            "Bedroom Speaker",
            &["192.168.1.17"],
        ));

        assert_eq!(app.devices[0].protocol, ReceiverProtocol::PlayBridge);
        assert_eq!(app.devices[1].protocol, ReceiverProtocol::Dlna);
        assert_eq!(app.devices[2].protocol, ReceiverProtocol::GoogleCast);
        assert_eq!(app.devices[app.device_selection].id.0, "dlna:bedroom");
    }

    #[test]
    fn receiver_address_summary_prefers_ipv4() {
        let receiver = receiver(
            "playbridge:desktop",
            ReceiverProtocol::PlayBridge,
            "Desktop",
            &["fe80::1%en0", "192.168.1.32", "fdeb::2"],
        );
        assert_eq!(address_summary(&receiver), "192.168.1.32 (+2)");
    }

    #[test]
    fn cast_launch_keeps_the_source_inside_the_dashboard() {
        let mut app = App::new(UiConfig::default()).unwrap();
        app.apply_launch(DashboardLaunch::Cast {
            source: Some("https://example.com/movie.mp4".into()),
            browser: false,
        });

        assert_eq!(app.section, Section::Cast);
        assert_eq!(app.source.as_deref(), Some("https://example.com/movie.mp4"));
        assert_eq!(app.overlay, Overlay::ReceiverPicker);
    }

    #[test]
    fn browser_launch_starts_inside_the_dashboard() {
        let mut app = App::new(UiConfig::default()).unwrap();
        app.apply_launch(DashboardLaunch::Cast {
            source: Some("https://example.com/movie.mp4".into()),
            browser: true,
        });

        assert_eq!(app.section, Section::Cast);
        assert!(app.browser_start_requested);
        assert_eq!(app.overlay, Overlay::None);
    }

    #[test]
    fn discover_launch_preserves_protocols_and_timeout() {
        let mut app = App::new(UiConfig::default()).unwrap();
        app.apply_launch(DashboardLaunch::Discover {
            protocols: HashSet::from([ReceiverProtocol::Roku]),
            timeout: Duration::from_secs(17),
        });

        assert_eq!(app.section, Section::Discover);
        assert_eq!(
            app.discovery_config.protocols,
            HashSet::from([ReceiverProtocol::Roku])
        );
        assert_eq!(app.discovery_config.timeout, Duration::from_secs(17));
    }

    #[test]
    fn receiver_launch_preserves_command_options_for_the_background_host() {
        let mut app = App::new(UiConfig::default()).unwrap();
        app.apply_launch(DashboardLaunch::Receiver {
            arguments: vec!["--port".into(), "9876".into()],
            auto_start: true,
        });

        assert_eq!(app.section, Section::Receiver);
        assert_eq!(app.receiver_arguments, ["--port", "9876"]);
        assert!(app.receiver_start_requested);
    }

    #[test]
    fn connected_cast_opens_remote_and_ignores_stale_updates() {
        let mut app = App::new(UiConfig::default()).unwrap();
        app.cast_generation = 7;
        app.cast_active = true;
        app.overlay = Overlay::BrowserPairing;
        app.pairing_device = Some("Browser receiver".into());
        app.input = "654321".into();
        let snapshot = crate::send::CastSnapshot {
            state: "playing".into(),
            title: "Movie".into(),
            position_ms: 5_000,
            duration_ms: 60_000,
            volume: Some(0.5),
            muted: None,
            looping: None,
            speed: None,
        };
        apply_cast_event(
            &mut app,
            crate::send::CastEvent::Connected {
                generation: 7,
                capabilities: crate::send::CastCapabilities {
                    play_pause: true,
                    ..crate::send::CastCapabilities::default()
                },
                snapshot: snapshot.clone(),
            },
        );
        assert_eq!(app.section, Section::Remote);
        assert_eq!(app.remote.snapshot.as_ref().unwrap().title, "Movie");
        assert_eq!(app.overlay, Overlay::None);
        assert!(app.pairing_device.is_none());
        assert!(app.input.is_empty());

        let mut stale = snapshot;
        stale.title = "Stale".into();
        apply_cast_event(
            &mut app,
            crate::send::CastEvent::Snapshot {
                generation: 6,
                snapshot: stale,
            },
        );
        assert_eq!(app.remote.snapshot.as_ref().unwrap().title, "Movie");
    }

    #[test]
    fn remote_keeps_unsupported_controls_visible() {
        let output = rendered(120, 34, Section::Remote);
        assert!(output.contains("Play / pause  (unsupported)"));
        assert!(output.contains("Stop playback  (unsupported)"));
    }

    #[test]
    fn content_selection_is_only_highlighted_when_content_has_focus() {
        let mut app = App::new(UiConfig::default()).unwrap();
        app.navigate_to(Section::Cast);
        assert_eq!(content_highlight_style(&app), app.theme.selected());

        app.focus = Focus::Navigation;
        assert_eq!(content_highlight_style(&app), app.theme.base());
    }

    #[test]
    fn pairing_event_prompts_for_the_receiver_sas_code() {
        let mut app = App::new(UiConfig::default()).unwrap();
        app.cast_generation = 11;
        app.cast_active = true;
        apply_cast_event(
            &mut app,
            crate::send::CastEvent::PairingCodeRequested {
                generation: 11,
                device_name: "Living room TV".into(),
            },
        );

        assert_eq!(app.overlay, Overlay::Pairing);
        let output = render_app(100, 30, app);
        assert!(output.contains("Pair with Living room TV"));
        assert!(output.contains("______"));
        assert!(output.contains("shown by the receiver"));
    }

    #[test]
    fn pairing_code_is_returned_to_the_cast_actor() {
        let mut app = App::new(UiConfig::default()).unwrap();
        app.overlay = Overlay::Pairing;
        app.input = "123456".into();

        let action =
            handle_key(&mut app, KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE)).unwrap();
        assert!(matches!(
            action,
            Some(DashboardAction::CastCommand(
                crate::send::CastCommand::SubmitPairingCode(code)
            )) if code == "123456"
        ));
        assert_eq!(app.overlay, Overlay::Pairing);
        assert!(app.input.is_empty());
    }

    #[test]
    fn browser_pairing_code_is_returned_to_the_cast_actor() {
        let mut app = App::new(UiConfig::default()).unwrap();
        app.overlay = Overlay::BrowserPairing;
        app.input = "654321".into();

        let action =
            handle_key(&mut app, KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE)).unwrap();
        assert!(matches!(
            action,
            Some(DashboardAction::CastCommand(
                crate::send::CastCommand::SubmitBrowserPairing(code)
            )) if code == "654321"
        ));
    }

    #[test]
    fn manual_receiver_parser_applies_protocol_defaults() {
        let receiver = parse_manual_receiver("playbridge://192.168.1.34").unwrap();
        assert_eq!(receiver.protocol, ReceiverProtocol::PlayBridge);
        assert_eq!(receiver.addresses, ["192.168.1.34"]);
        assert_eq!(receiver.wss_port, Some(8765));

        let cast = parse_manual_receiver("googlecast://living-room.local:9000").unwrap();
        assert_eq!(cast.protocol, ReceiverProtocol::GoogleCast);
        assert_eq!(cast.port, Some(9000));
    }
}
