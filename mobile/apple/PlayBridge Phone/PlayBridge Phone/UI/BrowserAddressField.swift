// SPDX-License-Identifier: MPL-2.0
import SwiftUI
import UIKit

/// Shared by the browser chrome and its isolated UI fixture. Like Android, the idle bar
/// shows the address without its scheme and the host emphasised; focusing it switches to
/// the full address with everything selected, ready to type over.
struct BrowserAddressField: View {
    @ObservedObject var tab: BrowserTab
    @Binding var address: String
    @FocusState.Binding var isFocused: Bool
    let onSubmit: () -> Void
    var body: some View {
        HStack(spacing: 6) {
            if !isFocused { BrowserSecurityIndicator(tab: tab) }
            TextField("Search or enter address", text: $address)
                .font(Theme.font(size: 13))
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .keyboardType(.webSearch).submitLabel(.go).focused($isFocused)
                // The styled overlay draws the idle address; the field keeps focus and accessibility.
                .foregroundColor(isFocused ? Theme.onSurface : .clear)
                .overlay(alignment: .leading) {
                    if !isFocused && !address.isEmpty {
                        Text(BrowserAddressDisplay.styled(address))
                            .font(Theme.font(size: 13)).lineLimit(1).truncationMode(.tail)
                            .allowsHitTesting(false).accessibilityHidden(true)
                    }
                }
                .onSubmit(onSubmit)
                .accessibilityIdentifier("browserAddressField")
            if isFocused && !address.isEmpty {
                Button { address = "" } label: {
                    Image(systemName: "xmark.circle.fill").font(.system(size: 16))
                        .foregroundColor(Theme.onSurfaceVariant)
                        .frame(width: 32, height: 40).contentShape(Rectangle())
                }
                .buttonStyle(.plain).accessibilityLabel("Clear address")
            }
        }
        .padding(.horizontal, 10).padding(.vertical, 6).frame(minHeight: 40)
        .background(Theme.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: 20))
        .padding(.horizontal, 2)
        .onChange(of: isFocused) { focused in
            guard focused, !address.isEmpty else { return }
            // Select the whole address once the field is first responder, so typing replaces it.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                UIApplication.shared.sendAction(#selector(UIResponder.selectAll(_:)), to: nil, from: nil, for: nil)
            }
        }
    }
}

enum BrowserAddressDisplay {
    /// Full address for editing; empty on the new-tab page.
    static func editable(_ tab: BrowserTab) -> String { tab.isHome ? "" : tab.urlString }

    /// `https://www.example.com/path` → `www.example.com/path`, host bright and the rest dim.
    static func styled(_ address: String) -> AttributedString {
        var text = address
        for scheme in ["https://", "http://"] where text.lowercased().hasPrefix(scheme) {
            text.removeFirst(scheme.count)
            break
        }
        let hostEnd = text.firstIndex(where: { "/?#".contains($0) }) ?? text.endIndex
        var host = AttributedString(String(text[..<hostEnd]))
        host.foregroundColor = Theme.onSurface
        var rest = AttributedString(String(text[hostEnd...]))
        rest.foregroundColor = Theme.onSurfaceVariant.opacity(0.6)
        return host + rest
    }
}

/// Android's URL-bar panel: shown over the page while the address is focused and untouched.
struct BrowserCurrentPageCard: View {
    @ObservedObject var tab: BrowserTab
    let onCopied: () -> Void
    var body: some View {
        if !tab.isHome, let url = URL(string: tab.urlString), ["http", "https"].contains(url.scheme?.lowercased() ?? "") {
            HStack(spacing: 10) {
                Image(systemName: "globe").font(.system(size: 20)).foregroundColor(Theme.onSurfaceVariant)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text(tab.title.isEmpty ? (url.host ?? tab.urlString) : tab.title)
                        .font(Theme.font(size: 14, weight: .semibold)).foregroundColor(Theme.onSurface).lineLimit(1)
                    Text(BrowserAddressDisplay.styled(tab.urlString))
                        .font(Theme.font(size: 12)).lineLimit(1).truncationMode(.middle)
                }
                Spacer(minLength: 4)
                ShareLink(item: url) {
                    Image(systemName: "square.and.arrow.up").font(.system(size: 17))
                        .foregroundColor(Theme.onSurface).frame(width: 40, height: 40).contentShape(Rectangle())
                }
                .accessibilityLabel("Share")
                Button {
                    UIPasteboard.general.url = url
                    onCopied()
                } label: {
                    Image(systemName: "doc.on.doc").font(.system(size: 17))
                        .foregroundColor(Theme.onSurface).frame(width: 40, height: 40).contentShape(Rectangle())
                }
                .buttonStyle(.plain).accessibilityLabel("Copy address")
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(Theme.surfaceContainerHigh.opacity(0.6), in: RoundedRectangle(cornerRadius: 16))
            .padding(.horizontal, 12)
            .accessibilityIdentifier("browserCurrentPageCard")
        }
    }
}

/// iOS shows the copied contents only after the user pastes, so the row offers the system
/// Paste button (no "Allow Paste" prompt) and a row tap as an alternative.
struct BrowserClipboardSuggestionRow: View {
    @ObservedObject var clipboard: BrowserClipboardSuggestions
    let onOpen: (String) -> Void
    var body: some View {
        if let kind = clipboard.offered {
            HStack(spacing: 0) {
                Button {
                    if let target = clipboard.takeTarget(search: SearchEngine.current.searchURL) { onOpen(target) }
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: kind == .link ? "link" : "magnifyingglass")
                            .font(.system(size: 17)).foregroundColor(Theme.primary).frame(width: 24)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(kind == .link ? "Link you copied" : "Text you copied")
                                .font(Theme.font(size: 13, weight: .semibold)).foregroundColor(Theme.primary)
                            Text(kind == .link ? "Paste to open it" : "Paste to search for it")
                                .font(Theme.font(size: 12)).foregroundColor(Theme.onSurfaceVariant)
                        }
                        Spacer(minLength: 4)
                    }
                    .frame(minHeight: 52).padding(.leading, 14).contentShape(Rectangle())
                }
                .buttonStyle(.plain).accessibilityIdentifier("browserClipboardSuggestion")
                PasteButton(payloadType: String.self) { strings in
                    let text = strings.first
                    DispatchQueue.main.async {
                        if let target = clipboard.target(pasted: text, search: SearchEngine.current.searchURL) { onOpen(target) }
                    }
                }
                .labelStyle(.titleAndIcon).buttonBorderShape(.capsule).tint(Theme.primary)
                .accessibilityIdentifier("browserClipboardPaste")
                Button { clipboard.dismiss() } label: {
                    Image(systemName: "xmark").foregroundColor(Theme.onSurfaceVariant)
                        .frame(width: 44, height: 52).contentShape(Rectangle())
                }
                .buttonStyle(.plain).accessibilityLabel("Dismiss copied suggestion")
            }
            .padding(.horizontal, 12)
        }
    }
}
