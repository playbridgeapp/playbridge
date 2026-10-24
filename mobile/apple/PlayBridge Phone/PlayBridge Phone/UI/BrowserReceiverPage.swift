import SwiftUI
import CoreImage.CIFilterBuiltins
import UIKit

/// Play on → Add a device → Browser on a TV. The TV opens this phone's page and shows a code.
struct BrowserReceiverPage: View {
    @ObservedObject var controller: BrowserReceiverController
    var castHere: (ExternalReceiverDevice) -> Void
    @State private var codes: [String: String] = [:]
    @State private var confirmStop = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if let error = controller.state.lastError, !error.isEmpty {
                    Text(error)
                        .font(Theme.font(size: 14))
                        .foregroundColor(Theme.danger)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if controller.state.running, let url = controller.pageAddress {
                    urlCard(url)
                    Text(BrowserReceiverState.keepOpenNote)
                        .font(Theme.font(size: 13))
                        .foregroundColor(Theme.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                    steps
                    pendingSection
                    readySection
                } else {
                    Text("Use a browser on a TV as a temporary receiver. This phone hosts a page on your Wi‑Fi.")
                        .font(Theme.font(size: 14))
                        .foregroundColor(Theme.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                    Button {
                        Task { await controller.startHost() }
                    } label: {
                        Text(controller.state.busy ? "Starting…" : "Start browser host")
                            .font(Theme.font(size: 16, weight: .semibold))
                            .frame(maxWidth: .infinity, minHeight: 48)
                            .foregroundColor(Theme.onPrimary)
                            .background(Theme.primaryDim, in: RoundedRectangle(cornerRadius: 14))
                    }
                    .buttonStyle(.plain)
                    .disabled(controller.state.busy)
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
                    Button("Stop") { stopTapped() }
                        .disabled(controller.state.busy)
                }
            }
        }
        .onAppear { controller.openSetup() }
        .alert("Stop the browser host?", isPresented: $confirmStop) {
            Button("Stop host", role: .destructive) { Task { await controller.stopHost() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The TV browser will disconnect.")
        }
    }

    private func stopTapped() {
        if controller.state.ready.isEmpty {
            Task { await controller.stopHost() }
        } else {
            confirmStop = true
        }
    }

    private func urlCard(_ url: String) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(url)
                .font(Theme.font(size: 22, weight: .bold, design: .monospaced))
                .foregroundColor(Theme.onSurface)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let image = Self.qrImage(url) {
                Image(uiImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 180, height: 180)
                    .padding(8)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 12))
                    .accessibilityLabel("QR code for the TV browser address")
            }
            Button {
                UIPasteboard.general.string = url
            } label: {
                Label("Copy address", systemImage: "doc.on.doc")
                    .font(Theme.font(size: 14, weight: .semibold))
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.plain)
            .foregroundColor(Theme.primary)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 16))
    }

    private var steps: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("1. Connect the TV to the same Wi‑Fi")
            Text("2. Open the address above in the TV’s browser")
            Text("3. Enter the code shown on the TV")
        }
        .font(Theme.font(size: 14))
        .foregroundColor(Theme.onSurfaceVariant)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private var pendingSection: some View {
        if controller.state.pending.isEmpty && controller.state.ready.isEmpty {
            HStack(spacing: 10) {
                ProgressView()
                Text("Waiting for the TV…")
                    .font(Theme.font(size: 15, weight: .semibold))
                    .foregroundColor(Theme.onSurface)
            }
            .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
            .padding(.horizontal, 14)
            .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 16))
        }
        ForEach(controller.state.pending) { request in
            VStack(alignment: .leading, spacing: 10) {
                Text(request.name)
                    .font(Theme.font(size: 16, weight: .semibold))
                    .foregroundColor(Theme.onSurface)
                Text("Enter the code shown on the TV")
                    .font(Theme.font(size: 13))
                    .foregroundColor(Theme.onSurfaceVariant)
                TextField("Code", text: codeBinding(request.sessionId))
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    .font(Theme.font(size: 22, weight: .bold, design: .monospaced))
                    .padding(12)
                    .background(Theme.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: 12))
                    .accessibilityLabel("Code from \(request.name)")
                Button("Approve") {
                    Task { await controller.approve(sessionId: request.sessionId, code: codes[request.sessionId] ?? "") }
                }
                .font(Theme.font(size: 16, weight: .semibold))
                .frame(maxWidth: .infinity, minHeight: 44)
                .foregroundColor(Theme.onPrimary)
                .background(Theme.primaryDim, in: RoundedRectangle(cornerRadius: 12))
                .disabled((codes[request.sessionId] ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 16))
        }
    }

    @ViewBuilder private var readySection: some View {
        ForEach(controller.state.ready) { session in
            VStack(alignment: .leading, spacing: 10) {
                Text(session.name)
                    .font(Theme.font(size: 16, weight: .semibold))
                    .foregroundColor(Theme.onSurface)
                Text("Ready")
                    .font(Theme.font(size: 13))
                    .foregroundColor(Theme.onSurfaceVariant)
                Button("Cast here") {
                    castHere(controller.receiver(for: session, lanHost: LocalFileServer.lanIPAddress()))
                }
                .font(Theme.font(size: 16, weight: .semibold))
                .frame(maxWidth: .infinity, minHeight: 44)
                .foregroundColor(Theme.onPrimary)
                .background(Theme.primaryDim, in: RoundedRectangle(cornerRadius: 12))
                HStack(spacing: 16) {
                    Button("Disconnect") { Task { await controller.disconnect(sessionId: session.sessionId) } }
                    Button("Forget") { Task { await controller.forget(receiverId: session.receiverId) } }
                }
                .font(Theme.font(size: 14, weight: .semibold))
                .foregroundColor(Theme.onSurfaceVariant)
                .frame(minHeight: 44)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 16))
        }
    }

    private func codeBinding(_ sessionId: String) -> Binding<String> {
        Binding(
            get: { codes[sessionId] ?? "" },
            set: { newValue in
                let filtered = String(newValue.filter(\.isNumber).prefix(6))
                codes[sessionId] = filtered
                if filtered.count == 6 {
                    Task { await controller.approve(sessionId: sessionId, code: filtered) }
                }
            })
    }

    private static func qrImage(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        guard let cg = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
