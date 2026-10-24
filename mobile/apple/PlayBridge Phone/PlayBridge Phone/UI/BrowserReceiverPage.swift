import SwiftUI
import UIKit

/// Play on → Add a device → Browser on a TV. The TV opens this phone's page and shows a code.
struct BrowserReceiverPage: View {
    @ObservedObject var controller: BrowserReceiverController
    @Environment(\.dismiss) private var dismiss
    var onWiFi: Bool
    var castHere: (ExternalReceiverDevice) -> Void
    @State private var codes: [String: String] = [:]
    @State private var rejectedIDs: Set<String> = []
    @State private var codeErrors: [String: String] = [:]
    @State private var approving: Set<String> = []
    @State private var autoFocusID: String?
    @State private var confirmStop = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Use any TV browser as a receiver. This phone hosts a page on your Wi\u{2011}Fi.")
                    .font(Theme.font(size: 14))
                    .foregroundColor(Theme.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                hostBlock
                if showsAddress {
                    steps
                    Text(BrowserReceiverState.keepOpenNote)
                        .font(Theme.font(size: 13))
                        .foregroundColor(Theme.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let error = commandError {
                    Text(error)
                        .font(Theme.font(size: 14))
                        .foregroundColor(Theme.danger)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if controller.state.running, onWiFi || !controller.state.pending.isEmpty || !readySessions.isEmpty {
                    statusArea
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
        }
        .background(Theme.surface.ignoresSafeArea())
        .navigationTitle("Browser on a TV")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text("Browser on a TV")
                    .font(Theme.font(size: 17, weight: .semibold))
                    .foregroundColor(Theme.onSurface)
                    .lineLimit(2)
                    .truncationMode(.tail)
                    .multilineTextAlignment(.center)
            }
            if controller.state.running {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Stop") { confirmStop = true }
                        .disabled(controller.state.busy)
                }
            }
        }
        .onAppear {
            controller.openSetup()
            syncPending()
        }
        .onChange(of: pendingIDs) { _ in syncPending() }
        .alert("Stop hosting?", isPresented: $confirmStop) {
            Button("Stop host", role: .destructive) {
                Task {
                    await controller.stopHost()
                    // Nothing left to do on a stopped host page; reopening it starts hosting again.
                    if !controller.state.running { dismiss() }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The TV browser will disconnect.")
        }
    }

    private var pendingIDs: [String] { controller.state.pending.map(\.sessionId) }

    private var showsAddress: Bool {
        onWiFi && controller.state.running && controller.pageAddress != nil
    }

    /// Host-command failures other than a bad pairing code or a failed start.
    private var commandError: String? {
        guard controller.state.running, let error = controller.state.lastError, !error.isEmpty else { return nil }
        if codeErrors.values.contains(error) { return nil }
        return error
    }

    private var readySessions: [BrowserReadySession] {
        BrowserReceiverState.uniqueReceivers(controller.state.ready)
    }

    @ViewBuilder private var hostBlock: some View {
        if !onWiFi {
            Text("Connect this phone to Wi\u{2011}Fi to use a TV browser.")
                .font(Theme.font(size: 14))
                .foregroundColor(Theme.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        } else if !controller.state.running, let error = controller.state.lastError, !error.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text(error)
                    .font(Theme.font(size: 14))
                    .foregroundColor(Theme.danger)
                    .fixedSize(horizontal: false, vertical: true)
                Button {
                    Task { await controller.startHost() }
                } label: {
                    Text("Try again")
                        .font(Theme.font(size: 15, weight: .semibold))
                        .foregroundColor(Theme.primary)
                        .frame(minHeight: 44)
                }
                .buttonStyle(.plain)
                .disabled(controller.state.busy)
            }
        } else if !controller.state.running || controller.pageAddress == nil {
            HStack(spacing: 10) {
                ProgressView()
                Text("Starting\u{2026}")
                    .font(Theme.font(size: 15, weight: .semibold))
                    .foregroundColor(Theme.onSurface)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else if let url = controller.pageAddress {
            urlCard(url)
        }
    }

    private func urlCard(_ url: String) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Open this on the TV's browser")
                .font(Theme.font(size: 13))
                .foregroundColor(Theme.onSurfaceVariant)
            Text(url)
                .font(Theme.font(size: 22, weight: .bold, design: .monospaced))
                .foregroundColor(Theme.onSurface)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                UIPasteboard.general.string = url
            } label: {
                Label("Copy address", systemImage: "doc.on.doc")
                    .font(Theme.font(size: 14, weight: .semibold))
            }
            .buttonStyle(.plain)
            .foregroundColor(Theme.primary)
            .frame(minHeight: 44, alignment: .leading)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 16))
    }

    private var steps: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("1. Connect the TV to the same Wi\u{2011}Fi")
            Text("2. Open the address above in the TV's browser")
            Text("3. Enter the 6\u{2011}digit code shown on the TV")
        }
        .font(Theme.font(size: 14))
        .foregroundColor(Theme.onSurfaceVariant)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private var statusArea: some View {
        if controller.state.pending.isEmpty && readySessions.isEmpty {
            HStack(spacing: 10) {
                ProgressView()
                Text("Waiting for the TV\u{2026}")
                    .font(Theme.font(size: 15, weight: .semibold))
                    .foregroundColor(Theme.onSurface)
            }
            .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
            .padding(.horizontal, 14)
            .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 16))
        }
        ForEach(controller.state.pending) { request in
            BrowserPairingCard(
                request: request,
                code: codeBinding(request.sessionId),
                rejected: rejectedIDs.contains(request.sessionId),
                errorText: codeErrors[request.sessionId],
                autoFocus: request.sessionId == autoFocusID,
                onFilled: { filled in submitCode(sessionId: request.sessionId, code: filled) },
                onCancel: { cancelRequest(request.sessionId) }
            )
        }
        if !readySessions.isEmpty {
            sectionLabel("Connected")
            ForEach(readySessions) { session in
                readyRow(session)
            }
        }
    }

    private func readyRow(_ session: BrowserReadySession) -> some View {
        HStack(spacing: 0) {
            HStack(spacing: 12) {
                Image(systemName: "globe")
                    .font(Theme.font(size: 20))
                    .foregroundColor(Theme.primary)
                    .frame(width: 28, height: 28)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(session.name)
                        .font(Theme.font(size: 15, weight: .semibold))
                        .foregroundColor(Theme.onSurface)
                        .lineLimit(1)
                    HStack(spacing: 4) {
                        Circle().fill(PlayOnStatusGreen.color).frame(width: 6, height: 6).accessibilityHidden(true)
                        Text("Ready")
                    }
                    .font(Theme.font(size: 13))
                    .foregroundColor(Theme.onSurfaceVariant)
                    .accessibilityElement(children: .combine)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    castHere(controller.receiver(for: session, lanHost: LocalFileServer.lanIPAddress()))
                } label: {
                    Text("Play here")
                        .font(Theme.font(size: 14, weight: .semibold))
                        .foregroundColor(Theme.onSecondaryContainer)
                        .lineLimit(1)
                        .padding(.horizontal, 14)
                        .frame(minHeight: 44)
                        .background(Theme.secondaryContainer, in: Capsule())
                }
                .buttonStyle(.plain)
                .fixedSize(horizontal: true, vertical: false)
            }
            Menu {
                Button("Disconnect") { Task { await controller.disconnect(sessionId: session.sessionId) } }
                Button("Forget", role: .destructive) { Task { await controller.forget(receiverId: session.receiverId) } }
            } label: {
                Image(systemName: "ellipsis.vertical")
                    .font(Theme.font(size: 16, weight: .semibold))
                    .foregroundColor(Theme.onSurfaceVariant)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("More options for \(session.name)")
        }
        .frame(minHeight: 64)
    }

    private func sectionLabel(_ text: String) -> some View {
        Text(text.uppercased())
            .font(Theme.font(size: 12, weight: .bold))
            .foregroundColor(Theme.onSurfaceVariant)
            .tracking(0.8)
            .padding(.top, 4)
    }

    private func codeBinding(_ sessionId: String) -> Binding<String> {
        Binding(
            get: { codes[sessionId] ?? "" },
            set: { codes[sessionId] = $0 }
        )
    }

    private func syncPending() {
        let ids = Set(pendingIDs)
        codes = codes.filter { ids.contains($0.key) }
        rejectedIDs.formIntersection(ids)
        codeErrors = codeErrors.filter { ids.contains($0.key) }
        approving.formIntersection(ids)
        if autoFocusID == nil || !ids.contains(autoFocusID!) {
            autoFocusID = pendingIDs.first
        }
    }

    private func submitCode(sessionId: String, code: String) {
        guard code.count == 6, !approving.contains(sessionId) else { return }
        guard let request = controller.state.pending.first(where: { $0.sessionId == sessionId }),
              request.expiresAt > Date() else { return }
        approving.insert(sessionId)
        rejectedIDs.remove(sessionId)
        codeErrors[sessionId] = nil
        Task {
            await controller.approve(sessionId: sessionId, code: code)
            approving.remove(sessionId)
            guard controller.state.pending.contains(where: { $0.sessionId == sessionId }) else { return }
            guard let error = controller.state.lastError, !error.isEmpty else { return }
            rejectedIDs.insert(sessionId)
            codes[sessionId] = ""
            codeErrors[sessionId] = error
        }
    }

    /// Drops this pending browser. There is no separate decline command.
    private func cancelRequest(_ sessionId: String) {
        codes[sessionId] = nil
        rejectedIDs.remove(sessionId)
        codeErrors[sessionId] = nil
        Task { await controller.disconnect(sessionId: sessionId) }
    }
}

private struct BrowserPairingCard: View {
    var request: BrowserPairingRequest
    @Binding var code: String
    var rejected: Bool
    var errorText: String?
    var autoFocus: Bool
    var onFilled: (String) -> Void
    var onCancel: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: "globe")
                    .font(Theme.font(size: 20))
                    .foregroundColor(Theme.primary)
                    .frame(width: 28, height: 28)
                    .accessibilityHidden(true)
                Text("\(request.name) wants to connect")
                    .font(Theme.font(size: 15, weight: .semibold))
                    .foregroundColor(Theme.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
            }
            PairingCodeBoxes(
                code: $code,
                rejected: rejected,
                focus: $focused,
                accessibilityLabel: "Code from \(request.name)",
                onFilled: onFilled
            )
            if let errorText, !errorText.isEmpty {
                Text(errorText)
                    .font(Theme.font(size: 13))
                    .foregroundColor(Theme.danger)
                    .fixedSize(horizontal: false, vertical: true)
            }
            BrowserCodeExpiryLine(expiresAt: request.expiresAt)
            Button("Cancel", action: onCancel)
                .font(Theme.font(size: 15, weight: .semibold))
                .foregroundColor(Theme.primary)
                .frame(minHeight: 44)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 16))
        .onAppear { if autoFocus { focused = true } }
        .onChange(of: autoFocus) { wants in if wants { focused = true } }
        .onChange(of: rejected) { isRejected in if isRejected { focused = true } }
    }
}

private struct BrowserCodeExpiryLine: View {
    var expiresAt: Date

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            Text(Self.line(expiresAt: expiresAt, now: context.date))
                .font(Theme.font(size: 13))
                .foregroundColor(Theme.onSurfaceVariant)
        }
    }

    static func line(expiresAt: Date, now: Date) -> String {
        let remaining = Int(expiresAt.timeIntervalSince(now))
        if remaining <= 0 { return "Code expired \u{2014} reload the page on the TV." }
        let minutes = remaining / 60
        let seconds = remaining % 60
        return String(format: "Expires in %d:%02d", minutes, seconds)
    }
}
