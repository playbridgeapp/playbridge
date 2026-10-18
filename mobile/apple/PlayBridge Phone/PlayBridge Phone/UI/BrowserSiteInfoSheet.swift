// SPDX-License-Identifier: MPL-2.0
import SwiftUI

struct BrowserSecurityIndicator: View {
    @ObservedObject var tab: BrowserTab
    @State private var showInfo = false
    var body: some View {
        if !tab.isHome && tab.siteSecurity.connection != .unavailable {
            Button { showInfo = true } label: {
                // Android's Chrome-style "tune" icon: accent when secure, muted otherwise.
                // The connection details (lock/warning) live in the sheet.
                Image(systemName: "slider.horizontal.3")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(tab.siteSecurity.connection == .secure ? Theme.primary : Theme.onSurfaceVariant)
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
    @State private var adblockOnSite = true
    private var site: String? { AdblockSiteSettings.site(for: URL(string: tab.urlString)) }
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
                if let site, !tab.isBridgedApp {
                    Section {
                        Toggle(isOn: Binding(get: { adblockOnSite }, set: { on in
                            adblockOnSite = on
                            AdblockSiteSettings.setDisabled(!on, site: site)
                        })) {
                            Label("Ad blocking on this site", systemImage: "shield")
                        }
                        .tint(Theme.primary)
                        .disabled(!ContentBlocker.isEnabled)
                        .accessibilityIdentifier("siteInfoAdblockToggle")
                    } footer: {
                        Text(ContentBlocker.isEnabled
                             ? "Turn off if \(site) doesn’t work properly. The page reloads. Pop-ups are set in Site Settings."
                             : "Ad blocking is off for all sites. Turn it on in Content Blocking.")
                    }
                }
                Section {
                    Button { showSettings = true } label: { Label("Site Settings", systemImage: "slider.horizontal.3") }
                }
            }
            .onAppear { adblockOnSite = !AdblockSiteSettings.isDisabled(URL(string: tab.urlString)) }
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
