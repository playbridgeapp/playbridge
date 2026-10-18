// SPDX-License-Identifier: MPL-2.0
import SwiftUI

struct BrowserSecurityIndicator: View {
    @ObservedObject var tab: BrowserTab
    @State private var showInfo = false
    var body: some View {
        if !tab.isHome && tab.siteSecurity.connection != .unavailable {
            Button { showInfo = true } label: {
                Image(systemName: tab.siteSecurity.connection.symbol)
                    .font(.system(size: 14))
                    .foregroundColor(tab.siteSecurity.connection == .secure ? Theme.onSurfaceVariant : Theme.danger)
                    .frame(width: 30, height: 44).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Site info")
            .accessibilityValue(tab.siteSecurity.connection.label)
            .accessibilityIdentifier("browserSecurityIndicator")
            .sheet(isPresented: $showInfo) { BrowserSiteInfoSheet(tab: tab) }
        }
    }
}

struct BrowserSiteInfoSheet: View {
    @ObservedObject var tab: BrowserTab
    @Environment(\.dismiss) private var dismiss
    @State private var showSettings = false
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(tab.siteSecurity.url?.host ?? "Website").font(Theme.font(.headline))
                        .textSelection(.enabled).accessibilityIdentifier("siteInfoHost")
                    Label(tab.siteSecurity.connection.label, systemImage: tab.siteSecurity.connection.symbol)
                        .accessibilityIdentifier("siteInfoConnection")
                    if tab.siteSecurity.connection == .mixedContent {
                        Text("This HTTPS page includes content loaded without a secure connection.")
                            .foregroundColor(Theme.danger)
                    }
                }
                if let certificate = tab.siteSecurity.certificate {
                    Section("Certificate") {
                        detail("Subject", certificate.subject)
                        detail("Issuer", certificate.issuer)
                        detail("Valid from", certificate.validFrom?.formatted(date: .abbreviated, time: .omitted))
                        detail("Valid until", certificate.validUntil?.formatted(date: .abbreviated, time: .omitted))
                    }
                }
                Section {
                    Button { showSettings = true } label: { Label("Site Settings", systemImage: "slider.horizontal.3") }
                }
            }
            .navigationTitle("Site info").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .sheet(isPresented: $showSettings) { BrowserSiteSettingsSheet(tab: tab) }
        .presentationDetents([.medium, .large])
    }
    private func detail(_ title: String, _ value: String?) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(Theme.font(.caption)).foregroundColor(Theme.onSurfaceVariant)
            Text(value ?? "Not available").textSelection(.enabled)
        }
    }
}
