/// Main-thread state for asynchronous native video-layer recovery. Tokens fence
/// completions after backgrounding, item replacement or player destruction.
struct MPVVideoRecoveryState {
    private(set) var isBackgrounded = false
    private(set) var needsRecovery = false
    private var stopped = false
    private var generation = 0
    private var pending: Int?

    mutating func enterBackground() {
        guard !stopped else { return }
        invalidate()
        isBackgrounded = true
        needsRecovery = true
    }

    mutating func becomeActive() {
        isBackgrounded = false
    }

    mutating func begin(rendererNeedsFlush: Bool) -> Int? {
        guard !stopped, !isBackgrounded, pending == nil,
              needsRecovery || rendererNeedsFlush else { return nil }
        generation &+= 1
        pending = generation
        return generation
    }

    mutating func finish(_ token: Int) -> Bool {
        guard !stopped, !isBackgrounded, pending == token else { return false }
        pending = nil
        needsRecovery = false
        return true
    }

    mutating func invalidate() {
        generation &+= 1
        pending = nil
    }

    mutating func stop() {
        invalidate()
        stopped = true
        needsRecovery = false
    }
}
