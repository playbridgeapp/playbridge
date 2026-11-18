import SwiftUI

/// The connected TV's subtitle style, edited from the phone. The TV keeps it and applies it at once.
struct TvSubtitleStyleSections: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @ObservedObject var controller: TvSubtitleStyleController
    @State private var error: String?

    var body: some View {
        Group {
            if !vm.isConnected || !vm.playBridgeLinkConnected {
                Section { message("Connect to the TV to change how its subtitles look.") }
            } else if !vm.tvSupportsSubtitleStyle {
                Section { message("Update PlayBridge on this TV to change its subtitle style from your phone.") }
            } else {
                let style = controller.style(for: vm.subtitleStyleSession)
                SubtitleStyleEditor(
                    style: style ?? SubtitleStylePreferences(),
                    onChange: { controller.set($0) },
                    enabled: style != nil,
                    fonts: vm.tvSupportsSubtitleFont ? fontChoices : nil,
                    previewUsesCustomFont: false
                )
            }
        }
        .task(id: vm.tvSupportsSubtitleStyle) {
            if vm.tvSupportsSubtitleStyle { controller.query() }
        }
    }

    private var fontChoices: SubtitleFontChoices {
        let sending = controller.uploadProgress.map { "Sending the font to the TV… \($0.percent)%" }
        return SubtitleFontChoices(
            installed: controller.fonts(for: vm.subtitleStyleSession),
            status: sending ?? error,
            busy: controller.uploadProgress != nil,
            onPick: { url in
                error = nil
                Task { @MainActor in
                    let picked = await Task.detached { PickedSubtitleFont.read(url) }.value
                    switch picked {
                    case .rejected(let message): error = message
                    case .font(let data, _):
                        if case .failed(let code) = await controller.upload(data) {
                            error = PickedSubtitleFont.message(for: code, toTV: true)
                        }
                    }
                }
            },
            onRemove: { family in
                error = nil
                controller.removeFont(family)
            }
        )
    }

    private func message(_ text: String) -> some View {
        Text(text).font(Theme.font(.subheadline)).foregroundColor(Theme.onSurfaceVariant)
    }
}

/// Settings → Subtitles: how subtitles look in the phone player and on the connected TV.
struct SubtitleStyleSettingsSheet: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Phone player").font(Theme.font(size: 17, weight: .semibold))
                } footer: {
                    Text("How subtitles look when videos play on this phone.")
                }
                PhoneSubtitleStyleSections()
                Section {
                    Text(vm.pairedDevice.map { "TV · \($0.name)" } ?? "TV").font(Theme.font(size: 17, weight: .semibold))
                } footer: {
                    Text("Your TV keeps its own style and applies changes right away.")
                }
                TvSubtitleStyleSections(controller: vm.tvSubtitles)
            }
            .navigationTitle("Subtitles")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}
