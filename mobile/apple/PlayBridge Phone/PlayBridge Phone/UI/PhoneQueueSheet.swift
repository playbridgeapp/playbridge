import SwiftUI

/// Up next for a phone list (Remote canvas, board 30): the whole list this iPhone holds, not
/// just the window the receiver has. Drag to reorder, remove, Repeat this, Save as collection,
/// Clear queue; tapping an item plays it. The receiver follows through PhoneQueueCoordinator.
struct PhoneQueueSheet: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var library: PhoneMediaLibrary
    @ObservedObject private var queue = PhoneQueueCoordinator.shared
    @Environment(\.dismiss) private var dismiss
    let looping: Bool
    let canLoop: Bool
    let onLoop: (Bool) -> Void
    @State private var saving: SavingItems?

    private var upcoming: [PhoneQueueCoordinator.Item] { Array(queue.items.dropFirst(queue.position + 1)) }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if let current = queue.current { nowPlaying(current) }
                    HStack(spacing: 8) {
                        pill(looping ? "Repeat this · On" : "Repeat this", selected: looping) { onLoop(!looping) }
                            .disabled(!canLoop)
                        pill("Save as collection", selected: false) { saving = SavingItems(items: queue.items.map(\.media)) }
                    }
                    .listRowSeparator(.hidden)
                }
                Section {
                    if upcoming.isEmpty {
                        Text("Nothing else is queued.").foregroundColor(Theme.onSurfaceVariant)
                    }
                    ForEach(upcoming) { item in row(item) }
                        .onMove { offsets, destination in queue.moveUpcoming(from: offsets, to: destination) }
                }
                Section {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: "iphone").foregroundColor(.orange)
                        Text("These play from your phone. Keep it on the same Wi-Fi until the queue ends.")
                            .font(Theme.font(.footnote))
                    }
                    .listRowBackground(Color.orange.opacity(0.15))
                    if !upcoming.isEmpty {
                        Button("Clear queue", role: .destructive) { queue.clearUpcoming() }
                            .frame(maxWidth: .infinity)
                    }
                }
            }
            .environment(\.editMode, .constant(.active))
            .navigationTitle("Up next").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    VStack(spacing: 0) {
                        Text("Up next").font(Theme.font(.headline))
                        Text("On \(vm.receiverName ?? "TV") · \(queue.remaining) left")
                            .font(Theme.font(.caption)).foregroundColor(Theme.onSurfaceVariant)
                    }
                }
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .sheet(item: $saving) { target in
                AddPhoneMediaToCollection(items: target.items)
            }
        }
    }

    private func nowPlaying(_ item: PhoneQueueCoordinator.Item) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                PhoneMediaThumbnailView(item: item.media, url: library.localURL(item.media), size: CGSize(width: 320, height: 180))
                    .frame(width: 80, height: 45).clipShape(RoundedRectangle(cornerRadius: 8))
                VStack(alignment: .leading, spacing: 2) {
                    Text("NOW PLAYING").font(Theme.font(size: 12, weight: .bold)).foregroundColor(.green)
                    Text(item.media.title).lineLimit(1)
                }
            }
            if let playback = vm.coordinator.playback, playback.durationMs > 0 {
                ProgressView(value: min(1, max(0, Double(playback.positionMs) / Double(playback.durationMs))))
            }
        }
        .padding(.vertical, 4)
    }

    private func row(_ item: PhoneQueueCoordinator.Item) -> some View {
        HStack(spacing: 10) {
            Button {
                queue.play(item.id)
                dismiss()
            } label: {
                HStack(spacing: 10) {
                    PhoneMediaThumbnailView(item: item.media, url: library.localURL(item.media), size: CGSize(width: 256, height: 144))
                        .frame(width: 64, height: 36).clipShape(RoundedRectangle(cornerRadius: 6))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.media.title).lineLimit(1)
                        if let duration = item.media.duration, duration > 0 {
                            Text(PhoneFilesModel.clock(duration)).font(Theme.font(.caption)).foregroundColor(Theme.onSurfaceVariant)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Play \(item.media.title) now")
            Button { queue.remove(item.id) } label: {
                Image(systemName: "xmark").frame(width: 44, height: 44)
            }
            .buttonStyle(.borderless)
            .foregroundColor(Theme.onSurfaceVariant)
            .accessibilityLabel("Remove from queue")
        }
    }

    private func pill(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(Theme.font(.footnote).weight(.medium)).frame(maxWidth: .infinity, minHeight: 40)
                .background(Capsule().fill(selected ? Theme.secondaryContainer : Color.clear))
                .overlay(Capsule().stroke(Theme.outlineVariant, lineWidth: 1))
        }
        .buttonStyle(.borderless)
        .accessibilityAddTraits(selected ? [.isSelected, .isButton] : .isButton)
    }

    private struct SavingItems: Identifiable {
        let id = UUID()
        let items: [PhoneMedia]
    }
}
