// SPDX-License-Identifier: MPL-2.0
#if PB_DEVELOPER_TOOLS
import SwiftUI

/// JSON and cURL for one detected item, like Android's "View raw message".
struct DetectedMediaDetailsSheet: View {
    let item: DetectedVideo
    let headers: [String: String]
    let qualities: [VideoQuality]
    @Environment(\.dismiss) private var dismiss
    @State private var format: Format = .json
    @State private var copied: String?

    enum Format: String, CaseIterable { case json = "JSON", curl = "cURL" }

    private var json: String { DetectedMediaExport.json(item, headers: headers, qualities: qualities) }
    private var curl: String { DetectedMediaExport.curl(url: item.url, headers: headers) }

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                Picker("Format", selection: $format) {
                    ForEach(Format.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                ScrollView([.vertical, .horizontal]) {
                    Text(format == .json ? json : curl)
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundColor(Theme.onSurface)
                        .textSelection(.enabled)
                        .fixedSize()
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("castItemDetailsText")
                }
                .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
                .padding(.horizontal, 16)
                HStack(spacing: 10) {
                    copyButton("Copy JSON", systemImage: "curlybraces", text: json)
                    copyButton("Copy as cURL", systemImage: "terminal", text: curl)
                    copyButton("Copy URL", systemImage: "link", text: item.url)
                }
                .padding(.horizontal, 16)
                Text("Includes the headers PlayBridge sends for this item, such as cookies or tokens. Share with care.")
                    .font(Theme.font(.caption)).foregroundColor(Theme.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16).padding(.bottom, 8)
            }
            .padding(.top, 8)
            .background(Theme.surface.ignoresSafeArea())
            .navigationTitle(item.displayTitle).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }

    private func copyButton(_ title: String, systemImage: String, text: String) -> some View {
        Button {
            UIPasteboard.general.string = text
            copied = title
        } label: {
            Label(copied == title ? "Copied" : title, systemImage: copied == title ? "checkmark" : systemImage)
                .font(Theme.font(size: 12, weight: .semibold))
                .frame(maxWidth: .infinity, minHeight: 36)
        }
        .buttonStyle(.bordered).tint(Theme.primary)
    }
}
#endif
