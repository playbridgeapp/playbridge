import SwiftUI

struct PlaylistOverlay: View {
    @EnvironmentObject var playlistStore: PlaylistStore
    let onItemSelected: (Int) -> Void
    let onDismiss: () -> Void
    
    @FocusState private var focusedIndex: Int?
    
    var body: some View {
        HStack {
            Spacer()
            
            VStack(alignment: .leading, spacing: 0) {
                // Header
                HStack {
                    Text("Playlist")
                        .font(.headline)
                        .foregroundColor(.white)
                    
                    Spacer()
                    
                    Text("\(playlistStore.currentIndex + 1) / \(playlistStore.items.count)")
                        .font(.caption)
                        .foregroundColor(.gray)
                }
                .padding(.horizontal, 24)
                .padding(.top, 40)
                .padding(.bottom, 20)
                
                // Item List
                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(spacing: 8) {
                            ForEach(0..<playlistStore.items.count, id: \.self) { index in
                                let item = playlistStore.items[index]
                                let isCurrent = index == playlistStore.currentIndex
                                
                                Button(action: {
                                    onItemSelected(index)
                                }) {
                                    HStack(spacing: 16) {
                                        Text("\(index + 1)")
                                            .font(.system(.caption, design: .monospaced))
                                            .foregroundColor(isCurrent ? .blue : .gray)
                                            .frame(width: 30)
                                        
                                        if isCurrent {
                                            Image(systemName: "play.fill")
                                                .font(.caption2)
                                                .foregroundColor(.blue)
                                        } else {
                                            Spacer().frame(width: 12)
                                        }
                                        
                                        Text(item.titleOrNil ?? "Episode \(index + 1)")
                                            .font(.body)
                                            .foregroundColor(isCurrent ? .blue : .white)
                                            .lineLimit(1)
                                        
                                        Spacer()
                                    }
                                    .padding(.vertical, 12)
                                    .padding(.horizontal, 16)
                                    .background(isCurrent ? Color.blue.opacity(0.15) : Color.clear)
                                    .cornerRadius(10)
                                }
                                .buttonStyle(.plain)
                                .focused($focusedIndex, equals: index)
                                .id(index)
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.bottom, 40)
                    }
                    .onAppear {
                        focusedIndex = playlistStore.currentIndex
                        proxy.scrollTo(playlistStore.currentIndex, anchor: .center)
                    }
                }
            }
            .frame(width: 450)
            .background(
                Color.black.opacity(0.85)
                    .background(.ultraThinMaterial)
            )
            .edgesIgnoringSafeArea(.all)
        }
        .transition(.move(edge: .trailing))
        .onExitCommand {
            onDismiss()
        }
    }
}
