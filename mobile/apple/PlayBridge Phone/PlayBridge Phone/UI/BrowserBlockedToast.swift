import SwiftUI

/// One per-tab bottom notice; repeated blocks replace the destination and reset
/// the countdown. No URL is sent to a system log or accessibility announcement.
struct BrowserBlockedToast: View {
    let notice: BrowserBlockedNotice
    let open: () -> Void
    let allow: () -> Void
    let dismiss: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @GestureState private var touching = false
    @State private var countdown = BrowserNoticeCountdown()
    @State private var offset = CGSize.zero

    var body: some View {
        VStack(spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "shield.fill").foregroundColor(Theme.primary)
                VStack(alignment: .leading, spacing: 3) {
                    Text(notice.title).font(Theme.font(size: 14, weight: .semibold))
                    if notice.kind != .message {
                        (Text(notice.destinationHost).bold() + Text(notice.destinationPath))
                            .font(Theme.font(size: 12)).lineLimit(1).truncationMode(.middle)
                        if notice.count > 1 {
                            Text("\(notice.count) blocked").font(Theme.font(size: 11))
                                .foregroundColor(Theme.onSurfaceVariant)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Button(action: dismiss) {
                    Image(systemName: "xmark").frame(width: 36, height: 36)
                }
                .accessibilityLabel("Dismiss blocked navigation notice")
            }
            if notice.kind != .message {
                HStack {
                    Spacer(minLength: 0)
                    if BrowserSitePolicy.origin(notice.origin) != nil {
                        Button("Allow on this site", action: allow)
                            .accessibilityLabel("Allow pop-ups and redirects on this site")
                    }
                    if notice.openTarget != nil {
                        Button("Open", action: open).buttonStyle(.borderedProminent)
                            .accessibilityLabel(notice.kind == .popup ? "Open blocked pop-up in a new tab" : "Open blocked redirect in this tab")
                    }
                }
                .font(Theme.font(size: 12, weight: .semibold))
            }
            GeometryReader { geometry in
                Rectangle().fill(Theme.primary).frame(width: geometry.size.width * countdown.progress)
            }
            .frame(height: 3).background(Theme.onSurfaceVariant.opacity(0.15))
            .accessibilityHidden(true)
        }
        .padding(12)
        .foregroundColor(Theme.onSurface)
        .background(Theme.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: 16))
        .shadow(color: .black.opacity(0.25), radius: 6, y: 3)
        .padding(12)
        .offset(reduceMotion ? .zero : offset)
        .simultaneousGesture(DragGesture(minimumDistance: 0)
            .updating($touching) { _, state, _ in state = true }
            .onChanged { value in offset = CGSize(width: value.translation.width, height: max(0, value.translation.height)) }
            .onEnded { value in
                if abs(value.translation.width) > 96 || value.translation.height > 96 { dismiss() }
                else { withAnimation(reduceMotion ? nil : .easeOut(duration: 0.15)) { offset = .zero } }
            })
        .task(id: notice.id) {
            countdown = BrowserNoticeCountdown(); offset = .zero
            var previous = ProcessInfo.processInfo.systemUptime
            while !Task.isCancelled {
                do { try await Task.sleep(nanoseconds: 50_000_000) } catch { return }
                let now = ProcessInfo.processInfo.systemUptime
                countdown.tick(elapsed: now - previous, paused: touching || voiceOver)
                previous = now
                if countdown.remaining <= 0 { dismiss(); return }
            }
        }
    }
}
