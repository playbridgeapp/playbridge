import SwiftUI
import UIKit

/// Focusable background view so Siri Remote presses reach the player controller
/// when no overlay control owns focus.
final class FocusableView: UIView {
    override var canBecomeFocused: Bool { true }
}

/// Shared controller for the two-tier player overlay (`PlayerControlsOverlay`),
/// `PlayerKeyMap` remote handling, auto-hide/seek/back-confirm timers, and
/// `PlayerEngineChrome` remote-navigation wiring across `MPVPlayerView` and
/// `NativePlayerView`.
final class PlayerOverlayController {
    static let phoneCardNotification = Notification.Name("PlayBridgePhoneCard")
    static let bookmarkPillNotification = Notification.Name("PlayBridgeBookmarkPill")

    let engine: PlaybackEngine
    let playbackState = PlayerControlsData()
    let failureRemote = AppRemoteFocusDriver()

    private(set) weak var parentViewController: UIViewController?
    private(set) var hostingController: UIHostingController<PlayerControlsOverlay>?
    private(set) var backgroundFocusView: FocusableView?

    private var hideControlsTimer: Timer?
    private var seekCommitTimer: Timer?
    private var transportBadgeTimer: Timer?
    private var noticeTimer: Timer?
    private var backArmTimer: Timer?
    private var holdTimer: Timer?
    private var phoneSeekTimer: Timer?
    private var phoneCardTimer: Timer?
    private var bookmarkPillTimer: Timer?
    private var observerTokens: [NSObjectProtocol] = []

    private var backArmed = false
    private var arrowHoldRepeats = 0
    private var lastArrowType: UIPress.PressType?
    private var controlsRevision = 0
    private var consumedPlaybackSelect = false

    // Engine state queries
    var isStopped: () -> Bool = { false }
    var isPreBuffering: () -> Bool = { false }
    var isPlaylistVisible: () -> Bool = { false }
    var isMusic: () -> Bool = { false }
    var isTouchScrubbing: () -> Bool = { false }

    // Engine action callbacks
    var onCancelTouchScrubIfNeeded: (_ fullControls: Bool, _ hasSidePanel: Bool, _ playlistVisible: Bool) -> Void = { _, _, _ in }
    var onWillOpenSidePanel: (PlayerSidePanel) -> Void = { _ in }
    var onSeek: (Double) -> Void = { _ in }
    var onSetPaused: (_ paused: Bool, _ openControls: Bool) -> Void = { _, _ in }
    var onTogglePlayPause: (_ openControls: Bool) -> Void = { _ in }
    var onSelectSubtitle: (Int) -> Void = { _ in }
    var onSelectAudio: (Int) -> Void = { _ in }
    var onSelectQueueItem: (Int) -> Void = { _ in }
    var onLoopToggle: () -> Void = {}
    var onSwitchEngine: () -> Void = {}
    var onSkipSegment: () -> Void = {}
    var onFailureAction: (PlaybackFailureAction) -> Void = { _ in }
    var onCloseFailure: () -> Void = {}
    var onHandleFailureKey: ((String) -> Bool)?
    var onLocalTuning: (PlayerTuning.Command) -> Void = { _ in }
    var onToggleBookmark: () -> Void = {}
    var onLeaveParty: () -> Void = {}
    var onPartyRate: (Double) -> Void = { _ in }
    var onPartyGoLive: () -> Void = {}
    var onBack: () -> Void = {}
    var onBroadcastStatus: () -> Void = {}
    var onSubtitleClearanceChange: (Double) -> Void = { _ in }

    // Optional mpv scrub hooks for remote Back/scrub/OK
    var onRemoteDismissScrub: (() -> Bool)?
    var onRemoteScrubStep: ((_ forward: Bool) -> Bool)?
    var onRemoteCommitScrub: (() -> Bool)?

    init(engine: PlaybackEngine) {
        self.engine = engine
        failureRemote.acceptsInput = { [weak self] in
            guard let self else { return false }
            return !self.isStopped() && !self.isPreBuffering() && !StillWatchingGate.isPrompting && self.playbackState.failureSentence != nil
        }
    }

    deinit {
        teardown()
    }

    var currentSurface: PlayerControlsSurface {
        if playbackState.activeSidePanel != .none { return .panel }
        if playbackState.showUI && playbackState.isFullControlsVisible { return .controls }
        if playbackState.showUI { return .seekBar }
        return .nothing
    }

    var canAutoHide: Bool {
        !playbackState.userPaused
            && !playbackState.isScrubbing
            && playbackState.activeSidePanel == .none
            && playbackState.failureSentence == nil
    }

    var shouldOverlayOwnFocus: Bool {
        playbackState.failureSentence != nil
            || playbackState.activeSidePanel != .none
            || (playbackState.showUI && playbackState.isFullControlsVisible)
            || playbackState.activeSkipSegment != nil
    }

    var preferredFocusEnvironments: [UIFocusEnvironment] {
        if shouldOverlayOwnFocus, let overlayView = hostingController?.view {
            return [overlayView]
        }
        if let focusView = backgroundFocusView {
            return [focusView]
        }
        return []
    }

    func attach(to parent: UIViewController, container: UIView, behindSubviews: [UIView] = []) {
        parentViewController = parent

        let focusView = FocusableView()
        focusView.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(focusView)
        NSLayoutConstraint.activate([
            focusView.topAnchor.constraint(equalTo: container.topAnchor),
            focusView.bottomAnchor.constraint(equalTo: container.bottomAnchor),
            focusView.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            focusView.trailingAnchor.constraint(equalTo: container.trailingAnchor),
        ])
        backgroundFocusView = focusView

        let overlay = PlayerControlsOverlay(
            data: playbackState,
            engine: engine,
            failureRemote: failureRemote,
            onTogglePlayPause: { [weak self] in
                guard let self else { return }
                self.dispatchTogglePlayPause(openControls: true)
            },
            onSeekStep: { [weak self] delta in
                self?.stepPendingSeek(deltaSeconds: delta)
            },
            onOpenSidePanel: { [weak self] panel in
                self?.openSidePanel(panel)
            },
            onCloseSidePanel: { [weak self] in
                self?.closeSidePanel()
            },
            onSelectSubtitle: { [weak self] trackId in
                guard let self else { return }
                self.onSelectSubtitle(trackId)
                self.closeSidePanel()
            },
            onSelectAudio: { [weak self] trackId in
                guard let self else { return }
                self.onSelectAudio(trackId)
                self.closeSidePanel()
            },
            onSelectChapter: { [weak self] chapter in
                guard let self else { return }
                self.seekFromOverlay(to: Double(chapter.startMs) / 1000.0)
                self.closeSidePanel()
            },
            onSelectQueueItem: { [weak self] index in
                guard let self else { return }
                self.closeSidePanel()
                self.onSelectQueueItem(index)
            },
            onToggleBookmark: { [weak self] in
                self?.onToggleBookmark()
            },
            onLeaveParty: { [weak self] in
                self?.onLeaveParty()
            },
            onLoopToggle: { [weak self] in
                guard let self else { return }
                self.onLoopToggle()
                self.showControls(full: true, autoHide: true)
            },
            onStartOver: { [weak self] in
                guard let self else { return }
                self.seekFromOverlay(to: 0)
                self.closeSidePanel()
            },
            onSwitchEngine: { [weak self] in
                guard let self else { return }
                self.closeSidePanel()
                self.onSwitchEngine()
            },
            onSkipSegment: { [weak self] in
                self?.onSkipSegment()
            },
            onFailureAction: { [weak self] action in
                self?.onFailureAction(action)
            },
            onTuning: { [weak self] command in
                guard let self else { return }
                self.onLocalTuning(command)
                if self.playbackState.activeSidePanel == .none {
                    self.showControls(full: true, autoHide: true)
                }
            },
            onChromeActivity: { [weak self] in
                guard let self else { return }
                if self.playbackState.showUI {
                    self.showControls(full: self.playbackState.isFullControlsVisible, autoHide: true)
                }
            },
            onSubtitleClearanceChange: { [weak self] height in
                self?.onSubtitleClearanceChange(height)
            }
        )

        let host = UIHostingController(rootView: overlay)
        host.view.backgroundColor = .clear
        host.view.translatesAutoresizingMaskIntoConstraints = false
        parent.addChild(host)
        container.addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.topAnchor.constraint(equalTo: container.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: container.bottomAnchor),
            host.view.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: container.trailingAnchor),
        ])
        host.didMove(toParent: parent)
        hostingController = host

        for subview in behindSubviews {
            container.bringSubviewToFront(subview)
        }

        let cardObserver = NotificationCenter.default.addObserver(
            forName: Self.phoneCardNotification,
            object: nil,
            queue: .main
        ) { [weak self] note in
            guard let card = note.userInfo?["card"] as? PhoneChangeCard else { return }
            self?.showPhoneCard(card)
        }
        let queueObserver = NotificationCenter.default.addObserver(
            forName: WebSocketServer.queueChange,
            object: nil,
            queue: .main
        ) { [weak self] note in
            guard let self, let info = note.userInfo,
                  let kind = info["kind"] as? String else { return }
            let senderName = info["senderName"] as? String
            let itemTitle = info["title"] as? String
            let title: String?
            switch kind {
            case "next":
                title = PhoneConfirmations.queueNextTitle(itemTitle: itemTitle)
            case "add":
                title = PhoneConfirmations.queueAddedTitle(itemTitle: itemTitle)
            case "jump":
                let index = (info["index"] as? Int) ?? 0
                let count = (info["count"] as? Int) ?? 0
                title = PhoneConfirmations.queuePositionTitle(itemTitle: itemTitle, index: index, count: count)
            default:
                title = nil
            }
            if let title {
                self.showPhoneCard(PhoneConfirmations.card(kind: .queue, title: title, name: senderName))
            }
        }
        let bookmarkObserver = NotificationCenter.default.addObserver(
            forName: Self.bookmarkPillNotification,
            object: nil,
            queue: .main
        ) { [weak self] note in
            guard let bookmarked = note.userInfo?["bookmarked"] as? Bool else { return }
            self?.showBookmarkPill(bookmarked: bookmarked)
        }
        observerTokens.append(contentsOf: [cardObserver, queueObserver, bookmarkObserver])
    }

    func showUI(autoHide: Bool = true) {
        showControls(full: playbackState.isFullControlsVisible, autoHide: autoHide)
    }

    func showControls(full: Bool, autoHide: Bool = true, resetFocus: Bool = false) {
        controlsRevision += 1
        let revision = controlsRevision
        DispatchQueue.main.async { [weak self] in
            guard let self,
                  revision == self.controlsRevision,
                  !self.isStopped(),
                  !self.isPreBuffering(),
                  self.playbackState.failureSentence == nil else { return }
            self.onCancelTouchScrubIfNeeded(
                full,
                self.playbackState.activeSidePanel != .none,
                self.isPlaylistVisible()
            )
            let focusChanged = !self.playbackState.showUI || (full != self.playbackState.isFullControlsVisible)
            self.playbackState.showUI = true
            self.playbackState.isFullControlsVisible = full
            if full && (focusChanged || resetFocus) {
                self.playbackState.controlsFocusRevision += 1
            }
            if focusChanged {
                self.updateTransportFocus()
            }
            self.hideControlsTimer?.invalidate()
            guard autoHide, self.canAutoHide else { return }
            self.hideControlsTimer = Timer.scheduledTimer(
                withTimeInterval: PlayerKeyMap.autoHideDelaySeconds(fullControls: full),
                repeats: false
            ) { [weak self] _ in
                guard let self, self.canAutoHide else { return }
                self.hideControls()
            }
        }
    }

    func hideControls() {
        controlsRevision += 1
        hideControlsTimer?.invalidate()
        hideControlsTimer = nil
        playbackState.activeSidePanel = .none
        playbackState.isFullControlsVisible = false
        playbackState.showUI = false
        updateTransportFocus()
    }

    func showNotice(_ text: String, emphasis: Bool = false, icon: String? = nil, duration: TimeInterval = 2.0) {
        noticeTimer?.invalidate()
        playbackState.notice = PlayerNotice(text: text, emphasis: emphasis, icon: icon)
        noticeTimer = Timer.scheduledTimer(withTimeInterval: duration, repeats: false) { [weak self] _ in
            self?.playbackState.notice = nil
        }
    }

    func showTransportBadge(_ badge: PlayerTransportBadge) {
        transportBadgeTimer?.invalidate()
        playbackState.transportBadge = badge
        guard badge != .none else { return }
        transportBadgeTimer = Timer.scheduledTimer(withTimeInterval: 1.4, repeats: false) { [weak self] _ in
            self?.playbackState.transportBadge = .none
        }
    }

    func showPhonePaused(name: String?) {
        transportBadgeTimer?.invalidate()
        playbackState.transportBadge = .none
        playbackState.phonePaused = PhoneOrigin(name: name)
        if playbackState.showUI {
            showControls(full: playbackState.isFullControlsVisible, autoHide: true)
        }
    }

    func clearPhonePaused() {
        playbackState.phonePaused = nil
    }

    func showPhoneSeek(name: String?, targetSeconds: Double) {
        phoneSeekTimer?.invalidate()
        let clamped = max(0, targetSeconds)
        playbackState.phoneSeek = PhoneSeek(name: name, targetSeconds: clamped)
        phoneSeekTimer = Timer.scheduledTimer(
            withTimeInterval: PhoneConfirmationTiming.durationSeconds,
            repeats: false
        ) { [weak self] _ in
            self?.playbackState.phoneSeek = nil
        }
    }

    func showBookmarkPill(bookmarked: Bool) {
        bookmarkPillTimer?.invalidate()
        playbackState.isBookmarked = bookmarked
        playbackState.bookmarkPillText = bookmarked
            ? "Bookmarked. Your place is saved as you watch."
            : "Bookmark removed"
        bookmarkPillTimer = Timer.scheduledTimer(withTimeInterval: 3.0, repeats: false) { [weak self] _ in
            self?.playbackState.bookmarkPillText = nil
        }
    }

    func showPhoneCard(_ card: PhoneChangeCard) {
        phoneCardTimer?.invalidate()
        playbackState.phoneCard = card
        phoneCardTimer = Timer.scheduledTimer(
            withTimeInterval: PhoneConfirmationTiming.durationSeconds,
            repeats: false
        ) { [weak self] _ in
            self?.playbackState.phoneCard = nil
        }
    }

    func clearPhoneConfirmations() {
        phoneSeekTimer?.invalidate()
        phoneSeekTimer = nil
        phoneCardTimer?.invalidate()
        phoneCardTimer = nil
        playbackState.phonePaused = nil
        playbackState.phoneSeek = nil
        playbackState.phoneCard = nil
    }

    func armBackConfirm() {
        backArmed = true
        backArmTimer?.invalidate()
        showNotice("Press Back again to stop", duration: PlayerKeyMap.backConfirmWindowSeconds)
        backArmTimer = Timer.scheduledTimer(
            withTimeInterval: PlayerKeyMap.backConfirmWindowSeconds,
            repeats: false
        ) { [weak self] _ in
            self?.backArmed = false
        }
    }

    func clearBackArm() {
        backArmed = false
        backArmTimer?.invalidate()
        backArmTimer = nil
    }

    func stepPendingSeek(deltaSeconds: Double) {
        let base = playbackState.pendingSeekTime ?? playbackState.currentTime
        let cap = playbackState.duration > 0 ? max(0, playbackState.duration - 2) : Double.infinity
        let target = max(0, min(base + deltaSeconds, cap))
        playbackState.pendingSeekTime = target
        showControls(full: false, autoHide: false)
        seekCommitTimer?.invalidate()
        seekCommitTimer = Timer.scheduledTimer(
            withTimeInterval: PlayerKeyMap.scrubCommitDelaySeconds,
            repeats: false
        ) { [weak self] _ in
            self?.commitPendingSeek()
        }
    }

    func commitPendingSeek() {
        seekCommitTimer?.invalidate()
        seekCommitTimer = nil
        guard let target = playbackState.pendingSeekTime else { return }
        playbackState.pendingSeekTime = nil
        seekFromOverlay(to: target)
        showControls(full: false, autoHide: true)
    }

    func cancelHoldRepeat() {
        holdTimer?.invalidate()
        holdTimer = nil
        phoneSeekTimer?.invalidate()
        phoneSeekTimer = nil
        phoneCardTimer?.invalidate()
        phoneCardTimer = nil
        lastArrowType = nil
        arrowHoldRepeats = 0
    }

    func cancelForFailure() {
        hideControlsTimer?.invalidate()
        hideControlsTimer = nil
        seekCommitTimer?.invalidate()
        seekCommitTimer = nil
        cancelHoldRepeat()
        playbackState.pendingSeekTime = nil
        playbackState.showUI = false
        playbackState.isFullControlsVisible = false
        playbackState.activeSidePanel = .none
        playbackState.activeSkipSegment = nil
        failureRemote.attachWindow(hostingController?.view.window)
        updateTransportFocus()
    }

    func openSidePanel(_ panel: PlayerSidePanel) {
        guard panel != .none else {
            closeSidePanel()
            return
        }
        onCancelTouchScrubIfNeeded(true, true, isPlaylistVisible())
        holdTimer?.invalidate()
        playbackState.activeSidePanel = panel
        onWillOpenSidePanel(panel)
        showControls(full: true, autoHide: false)
    }

    func closeSidePanel() {
        guard playbackState.activeSidePanel != .none else { return }
        playbackState.lastClosedSidePanel = playbackState.activeSidePanel
        playbackState.activeSidePanel = .none
        showControls(full: true, autoHide: true, resetFocus: true)
    }

    func updateTransportFocus() {
        failureRemote.attachWindow(hostingController?.view.window)
        guard let parent = parentViewController else { return }
        parent.setNeedsFocusUpdate()
        // SwiftUI must first apply the FocusState/default-focus request to its
        // UIKit descendants. Then resolve preferredFocusEnvironments immediately.
        DispatchQueue.main.async { [weak self, weak parent] in
            guard let self, let parent, !self.isStopped() else { return }
            self.hostingController?.view.layoutIfNeeded()
            parent.setNeedsFocusUpdate()
            parent.updateFocusIfNeeded()
        }
    }

    // MARK: - Remote input

    static func remoteKey(for type: UIPress.PressType) -> PlayerRemoteKey? {
        switch type {
        case .select: return .ok
        case .leftArrow: return .left
        case .rightArrow: return .right
        case .upArrow: return .up
        case .downArrow: return .down
        case .menu: return .back
        case .playPause: return .playPause
        default: return nil
        }
    }

    func makeKeyContext(repeatCount: Int = 0) -> PlayerKeyContext {
        let confirmBack = !isPreBuffering()
            && !StillWatchingGate.isPrompting
            && playbackState.failureSentence == nil
            && !playbackState.cacheStalled
            && !isMusic()
        return PlayerKeyContext(
            surface: currentSurface,
            repeatCount: repeatCount,
            backArmed: backArmed,
            confirmBack: confirmBack,
            isImage: false,
            quietAllowed: true
        )
    }

    func handlePressesBegan(
        _ presses: Set<UIPress>,
        with event: UIPressesEvent?,
        superCall: () -> Void
    ) {
        if StillWatchingGate.isPrompting {
            NotificationCenter.default.post(name: .playBridgeStillWatchingResume, object: nil)
            return
        }
        guard let type = presses.first?.type else {
            superCall()
            return
        }
        if isPreBuffering() {
            if type == .menu {
                clearBackArm()
                onBroadcastStatus()
                onBack()
                return
            }
            superCall()
            return
        }
        NotificationCenter.default.post(name: .playBridgeUserActivity, object: nil)

        if playbackState.failureSentence != nil {
            if type == .menu {
                onCloseFailure()
            } else {
                superCall()
            }
            return
        }

        if isPlaylistVisible() {
            superCall()
            return
        }

        guard let key = Self.remoteKey(for: type) else {
            superCall()
            return
        }

        if key != .back {
            clearBackArm()
        }

        let action = PlayerKeyMap.resolve(key: key, context: makeKeyContext(repeatCount: 0))
        if case .seek = action, (type == .leftArrow || type == .rightArrow) {
            holdTimer?.invalidate()
            lastArrowType = type
            arrowHoldRepeats = 0
            holdTimer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
                guard let self, self.lastArrowType == type, let key = Self.remoteKey(for: type) else { return }
                self.arrowHoldRepeats += 1
                let repeatAction = PlayerKeyMap.resolve(
                    key: key,
                    context: self.makeKeyContext(repeatCount: self.arrowHoldRepeats)
                )
                if case .seek(let delta) = repeatAction {
                    self.stepPendingSeek(deltaSeconds: delta)
                }
            }
        }

        handleResolvedKeyAction(action, superCall: superCall)
    }

    func handlePressesEnded(
        _ presses: Set<UIPress>,
        with event: UIPressesEvent?,
        superCall: () -> Void
    ) {
        if let type = presses.first?.type, type == .leftArrow || type == .rightArrow {
            holdTimer?.invalidate()
            holdTimer = nil
            lastArrowType = nil
            arrowHoldRepeats = 0
        }
        if presses.first?.type == .select, consumedPlaybackSelect {
            consumedPlaybackSelect = false
            return
        }
        if presses.first?.type == .menu { return }
        superCall()
    }

    func handlePressesCancelled(
        _ presses: Set<UIPress>,
        with event: UIPressesEvent?,
        superCall: () -> Void
    ) {
        if let type = presses.first?.type, type == .leftArrow || type == .rightArrow {
            holdTimer?.invalidate()
            holdTimer = nil
            lastArrowType = nil
            arrowHoldRepeats = 0
        }
        if presses.first?.type == .select, consumedPlaybackSelect {
            consumedPlaybackSelect = false
            return
        }
        if presses.first?.type == .menu { return }
        holdTimer?.invalidate()
        superCall()
    }

    // MARK: - Phone PlayerEngineChrome

    func bindEngineChrome(_ chrome: PlayerEngineChrome) {
        chrome.handleFailureKey = { [weak self] key in
            guard let self else { return false }
            if let handler = self.onHandleFailureKey {
                return handler(key)
            }
            return self.handleFailureKey(key)
        }
        chrome.snapshot = { [weak self] in
            guard let self else { return PlayerEngineChromeSnapshot() }
            return PlayerEngineChromeSnapshot(
                engineMenu: false,
                settingsSheet: false,
                scrubbing: self.playbackState.isScrubbing || self.isTouchScrubbing(),
                controlsVisible: self.playbackState.showUI && !self.isPreBuffering(),
                infoPanel: self.playbackState.activeSidePanel != .none
            )
        }
        chrome.apply = { [weak self] effect in
            self?.applyRemoteEffect(effect) ?? false
        }
        chrome.setPartySession = { [weak self] active in
            self?.playbackState.isWatchTogetherParty = active
        }
        chrome.applyPartyPaused = { [weak self] paused in
            self?.onSetPaused(paused, false)
        }
        chrome.applyPartySeek = { [weak self] seconds in
            self?.onSeek(seconds)
        }
        chrome.applyPartyRate = { [weak self] rate in
            self?.onPartyRate(rate)
        }
        chrome.applyPartyGoLive = { [weak self] in
            self?.onPartyGoLive()
        }
    }

    func handleFailureKey(_ key: String) -> Bool {
        guard !isStopped(), !isPreBuffering(), !StillWatchingGate.isPrompting,
              playbackState.failureSentence != nil else { return false }
        switch key {
        case "dpad_left", "dpad_right", "dpad_up", "dpad_down", "dpad_center", "back", "home":
            failureRemote.attachWindow(hostingController?.view.window)
            failureRemote.handle(key)
            return true
        default:
            return false
        }
    }

    func applyRemoteEffect(_ effect: PlayerRemoteEffect) -> Bool {
        if playbackState.failureSentence != nil { return true }
        switch effect {
        case .dismissInfoPanel, .dismissEngineMenu:
            guard playbackState.activeSidePanel != .none else { return false }
            closeSidePanel()
            return true
        case .dismissScrub:
            if onRemoteDismissScrub?() == true { return true }
            if playbackState.pendingSeekTime != nil {
                commitPendingSeek()
                return true
            }
            return false
        case .dismissControls:
            guard playbackState.showUI, !isPreBuffering() else { return false }
            hideControls()
            return true
        case .togglePlayback:
            dispatchTogglePlayPause(openControls: false)
            return true
        case .seekBackward:
            stepPendingSeek(deltaSeconds: -PlayerKeyMap.seekStepSeconds)
            return true
        case .seekForward:
            stepPendingSeek(deltaSeconds: PlayerKeyMap.seekStepSeconds)
            return true
        case .scrubBackward:
            if onRemoteScrubStep?(false) == true { return true }
            return false
        case .scrubForward:
            if onRemoteScrubStep?(true) == true { return true }
            return false
        case .commitScrub:
            if onRemoteCommitScrub?() == true { return true }
            return false
        default:
            return false
        }
    }

    func teardown() {
        hideControlsTimer?.invalidate()
        hideControlsTimer = nil
        seekCommitTimer?.invalidate()
        seekCommitTimer = nil
        transportBadgeTimer?.invalidate()
        transportBadgeTimer = nil
        noticeTimer?.invalidate()
        noticeTimer = nil
        backArmTimer?.invalidate()
        backArmTimer = nil
        holdTimer?.invalidate()
        holdTimer = nil
        phoneSeekTimer?.invalidate()
        phoneSeekTimer = nil
        phoneCardTimer?.invalidate()
        phoneCardTimer = nil
        bookmarkPillTimer?.invalidate()
        bookmarkPillTimer = nil
        for token in observerTokens {
            NotificationCenter.default.removeObserver(token)
        }
        observerTokens.removeAll()
        if let host = hostingController {
            host.willMove(toParent: nil)
            host.view.removeFromSuperview()
            host.removeFromParent()
            hostingController = nil
        }
        backgroundFocusView?.removeFromSuperview()
        backgroundFocusView = nil
    }

    // MARK: - Private

    private func dispatchSeek(to seconds: Double) {
        if playbackState.isWatchTogetherParty {
            Task { @MainActor in
                _ = TvTogether.shared.localSeek(positionMs: Int64(max(0, seconds * 1000)))
            }
            return
        }
        onSeek(seconds)
    }

    private func dispatchSetPaused(_ paused: Bool, openControls: Bool) {
        if playbackState.isWatchTogetherParty {
            Task { @MainActor in
                if paused {
                    _ = TvTogether.shared.localPause()
                } else {
                    _ = TvTogether.shared.localPlay()
                }
            }
            if openControls {
                showControls(full: true, autoHide: true)
            }
            return
        }
        onSetPaused(paused, openControls)
    }

    private func dispatchTogglePlayPause(openControls: Bool) {
        if playbackState.isWatchTogetherParty {
            let currentlyPlaying = playbackState.isPlaying
            Task { @MainActor in
                if currentlyPlaying {
                    _ = TvTogether.shared.localPause()
                } else {
                    _ = TvTogether.shared.localPlay()
                }
            }
            if openControls {
                showControls(full: true, autoHide: true)
            }
            return
        }
        onTogglePlayPause(openControls)
    }

    private func seekFromOverlay(to seconds: Double) {
        dispatchSeek(to: seconds)
        onBroadcastStatus()
    }

    private func handleResolvedKeyAction(_ action: PlayerKeyAction, superCall: () -> Void) {
        switch action {
        case .seek(let deltaSeconds):
            stepPendingSeek(deltaSeconds: deltaSeconds)
        case .stepItem(let delta):
            let next = playbackState.playlistIndex + delta
            if playbackState.playlistItems.indices.contains(next) {
                onSelectQueueItem(next)
            }
        case .pauseAndOpenControls:
            consumedPlaybackSelect = true
            dispatchSetPaused(true, openControls: true)
        case .openControls:
            showControls(full: true, autoHide: true)
        case .transport(let command, let quiet):
            if command == "play" {
                dispatchSetPaused(false, openControls: !quiet)
            } else if command == "pause" {
                dispatchSetPaused(true, openControls: !quiet)
            } else {
                dispatchTogglePlayPause(openControls: !quiet)
            }
        case .hideSeekBar, .hideControls:
            hideControls()
        case .closePanel:
            closeSidePanel()
        case .armBack:
            armBackConfirm()
        case .exitPlayer:
            clearBackArm()
            onBroadcastStatus()
            onBack()
        case .ignore:
            break
        case .passThrough:
            if currentSurface == .controls {
                showControls(full: true, autoHide: true)
            }
            superCall()
        }
    }
}
