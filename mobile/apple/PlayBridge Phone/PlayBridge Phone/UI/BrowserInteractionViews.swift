import SwiftUI
import UniformTypeIdentifiers

struct BrowserPromptView: View {
    let prompt: BrowserPrompt
    let resolve: (Bool, String?) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var accepted = false
    var body: some View {
        NavigationStack {
            Form {
                Text(prompt.message).textSelection(.enabled)
                if prompt.defaultText != nil { TextField("Response", text: $text) }
                Button(prompt.acceptLabel) { accepted = true; dismiss() }
                if prompt.showsCancel { Button("Cancel", role: .cancel) { dismiss() } }
            }
            .navigationTitle(prompt.title)
            .navigationBarTitleDisplayMode(.inline)
        }
        .onAppear { text = prompt.defaultText ?? "" }
        .onDisappear { resolve(accepted, accepted ? text : nil) }
        .presentationDetents([.medium, .large])
    }
}

struct BrowserFailureView: View {
    let failure: BrowserNavigationFailure
    let retry: () -> Void
    let back: () -> Void
    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle").font(Theme.font(.largeTitle))
            Text("Couldn’t open page").font(Theme.font(.headline))
            Text(failure.message).multilineTextAlignment(.center)
            Text(failure.address).font(Theme.font(.caption)).lineLimit(3).textSelection(.enabled)
            HStack {
                Button("Back", action: back).buttonStyle(.bordered)
                Button("Try Again", action: retry).buttonStyle(.borderedProminent)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.surface)
    }
}

struct BrowserDownloadsView: View {
    @ObservedObject var downloads: BrowserDownloads
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                if downloads.items.isEmpty { Text("Downloaded files will appear here.") }
                ForEach(downloads.items) { item in
                    BrowserDownloadRow(item: item, downloads: downloads)
                }
            }
            .navigationTitle("Downloads")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

private struct BrowserDownloadRow: View {
    @ObservedObject var item: BrowserDownload
    let downloads: BrowserDownloads
    @State private var export = false
    @State private var confirmRetry = false
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(item.filename).font(Theme.font(.headline))
            Text(item.state).font(Theme.font(.caption))
            if item.state == "Downloading" { ProgressView(value: item.fraction) }
            if let message = item.message { Text(message).font(Theme.font(.caption)) }
            HStack {
                if item.state == "Downloading" {
                    Button("Cancel") { downloads.cancel(item) }
                } else if item.state == "Complete", let url = item.fileURL {
                    Button("Save to Files") { export = true }
                    ShareLink(item: url)
                } else if item.canRetry && item.state != "Starting" {
                    Button("Retry") { confirmRetry = true }
                }
                Spacer()
                Button("Delete", role: .destructive) { downloads.remove(item) }
            }
            .buttonStyle(.borderless)
        }
        .sheet(isPresented: $export) {
            if let url = item.fileURL { BrowserFileExport(url: url) }
        }
        .alert("Retry download?", isPresented: $confirmRetry) {
            Button("Retry") { downloads.retry(item) }
            Button("Cancel", role: .cancel) {}
        } message: { Text("This may send the original website request again.") }
    }
}

private struct BrowserFileExport: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        UIDocumentPickerViewController(forExporting: [url], asCopy: true)
    }
    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}
}
