import SwiftUI

struct RemoteVolumeSheet: View {
    let title: String
    let pill: RemoteVolumePill
    let canStep: Bool
    let canMute: Bool
    let onSet: (Int) -> Void
    let onStep: (Bool) -> Void
    let onMute: () -> Void
    @State private var draft = RemoteVolumeDraft()
    @State private var throttle = LatestVolumeThrottle()
    @State private var sender: Task<Void, Never>?

    private var now: Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }
    private var shown: Double { draft.dragging || draft.pinDeadline != nil ? draft.value : Double(pill.percent ?? 50) }

    var body: some View {
        VStack(spacing: 20) {
            Text(title).font(Theme.font(.headline))
            HStack(spacing: 10) {
                Image(systemName: pill.icon).foregroundStyle(Theme.primary)
                Text(draft.dragging || draft.pinDeadline != nil ? String(Int(shown.rounded())) : pill.percent.map(String.init) ?? (pill.exact ? "—" : ""))
                    .font(Theme.font(size: 44, weight: .semibold)).monospacedDigit()
                    .accessibilityIdentifier("remote.volumeValue")
                if pill.muted { Text("Muted").foregroundStyle(Theme.onSurfaceVariant) }
            }
            if pill.exact {
                HStack {
                    stepButton(up: false)
                    Slider(value: Binding(get: { shown }, set: { value in
                        let previous = Int(shown.rounded())
                        draft.move(value)
                        if Int(value.rounded()) / 5 != previous / 5 { RemoteHaptics.shared.perform(.volume) }
                        offer(Int(value.rounded()))
                    }), in: 0...100, onEditingChanged: { editing in
                        if !editing {
                            draft.release(at: now)
                            offer(Int(draft.value.rounded()))
                        }
                    })
                    .tint(Theme.primary).accessibilityLabel(title)
                    .accessibilityIdentifier("remote.volumeSlider")
                    stepButton(up: true)
                }
            } else if canStep {
                HStack(spacing: 32) { stepButton(up: false); stepButton(up: true) }
            }
            if canMute {
                Button(action: onMute) {
                    Label(pill.muted ? "Unmute" : "Mute", systemImage: pill.muted ? "speaker.wave.2" : "speaker.slash")
                        .frame(minHeight: 44)
                }.buttonStyle(.bordered).tint(Theme.primary)
            }
        }
        .padding(24).frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.surface).presentationDetents([.height(300)])
        .presentationDragIndicator(.visible)
        .onAppear { draft.reconcile(pill.percent, at: now) }
        .onChange(of: pill.percent) { percent in draft.reconcile(percent, at: now) }
        .task(id: draft.pinDeadline) {
            guard let deadline = draft.pinDeadline else { return }
            try? await Task.sleep(nanoseconds: UInt64(max(0, deadline - now)) * 1_000_000)
            guard !Task.isCancelled else { return }
            draft.reconcile(pill.percent, at: now)
        }
        .onDisappear { sender?.cancel(); sender = nil; throttle.clear() }
    }

    private func stepButton(up: Bool) -> some View {
        Button {
            // A queued drag value must not undo a subsequent step.
            sender?.cancel(); sender = nil; throttle.clear()
            let previous = shown
            draft.unpin()
            draft.reconcile(pill.percent, at: now)
            if canStep {
                onStep(up) // The action boundary performs the step's haptic after sending.
            } else {
                // Absolute-only receivers still get usable −/+ without sending unsupported keys.
                draft.move(previous + (up ? 5 : -5)); draft.release(at: now)
                offer(Int(draft.value.rounded()))
                RemoteHaptics.shared.perform(.volume)
            }
        } label: {
            Image(systemName: up ? "plus" : "minus").font(Theme.font(size: 24))
                .frame(width: pill.exact ? 44 : 64, height: pill.exact ? 44 : 64)
                .background(Theme.surfaceContainerHigh, in: Circle())
        }.buttonStyle(.plain).accessibilityLabel(title + (up ? " up" : " down"))
    }

    private func offer(_ value: Int) {
        throttle.offer(value)
        guard sender == nil else { return }
        sender = Task { @MainActor in
            while !Task.isCancelled && throttle.hasPending {
                if let value = throttle.poll(at: now) { onSet(value) }
                else {
                    try? await Task.sleep(nanoseconds: UInt64(max(1, throttle.wait(at: now))) * 1_000_000)
                }
            }
            if !Task.isCancelled { sender = nil }
        }
    }
}
