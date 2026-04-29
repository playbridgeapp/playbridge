import SwiftUI
import Combine

struct PrePlayView: View {
    let metadata: VisualMetadata
    let onStart: () -> Void
    let onBack: () -> Void
    
    @State private var isAnimating = false
    @State private var countdown = 5
    @State private var timerTask: Task<Void, Never>? = nil
    
    var body: some View {
        ZStack {
            // 1. Background Backdrop
            GeometryReader { proxy in
                ZStack {
                    if let backdrop = metadata.backdropUrl ?? metadata.posterUrl, let url = URL(string: backdrop) {
                        AsyncImage(url: url) { image in
                            image
                                .resizable()
                                .aspectRatio(contentMode: .fill)
                                .frame(width: proxy.size.width, height: proxy.size.height)
                                .clipped()
                        } placeholder: {
                            Color.black
                        }
                    } else {
                        Color.black
                    }
                    
                    // Dark Gradient Scrim
                    LinearGradient(
                        colors: [
                            .black.opacity(0.95),
                            .black.opacity(0.8),
                            .black.opacity(0.4),
                            .clear
                        ],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                    
                    Rectangle()
                        .fill(.black.opacity(0.3))
                        .background(.ultraThinMaterial.opacity(0.4))
                }
            }
            .ignoresSafeArea()
            
            // 2. Content Info
            HStack(spacing: 60) {
                VStack(alignment: .leading, spacing: 20) {
                    // Logo or Title
                    if let logo = metadata.logoUrl, let url = URL(string: logo) {
                        AsyncImage(url: url) { image in
                            image
                                .resizable()
                                .aspectRatio(contentMode: .fit)
                                .frame(height: 120)
                        } placeholder: {
                            titleView
                        }
                    } else {
                        titleView
                    }
                    
                    // Meta items (Year, Rating, Runtime)
                    HStack(spacing: 16) {
                        if let year = metadata.year {
                            Text(year)
                        }
                        if let rating = metadata.rating {
                            Text("IMDb \(rating)")
                                .padding(.horizontal, 8)
                                .padding(.vertical, 2)
                                .background(Color.yellow.opacity(0.2))
                                .cornerRadius(4)
                        }
                        if let runtime = metadata.runtime {
                            Text(runtime)
                        }
                    }
                    .font(.headline)
                    .foregroundColor(.gray)
                    
                    // Season / Episode Info
                    if let season = metadata.season, let episode = metadata.episode {
                        Text("S\(season) E\(episode)\(metadata.episodeTitle != nil ? " • \(metadata.episodeTitle!)" : "")")
                            .font(.title2)
                            .fontWeight(.bold)
                            .foregroundColor(.blue)
                    }
                    
                    // Overview
                    if let overview = metadata.overview {
                        Text(overview)
                            .font(.body)
                            .foregroundColor(.white.opacity(0.8))
                            .lineLimit(8)
                            .frame(maxWidth: 600, alignment: .leading)
                    }
                    
                    Spacer()
                    
                    // Action Section
                    HStack(spacing: 30) {
                        Text("Starting in \(countdown)s")
                            .font(.headline)
                            .foregroundColor(.white.opacity(0.6))
                    }
                }
                .padding(.leading, 80)
                .padding(.vertical, 60)
                .opacity(isAnimating ? 1 : 0)
                .offset(x: isAnimating ? 0 : -50)
                
                Spacer()
                
                // Right Side: Poster
                if let poster = metadata.posterUrl, let url = URL(string: poster) {
                    AsyncImage(url: url) { image in
                        image
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                            .frame(width: 400, height: 600)
                            .cornerRadius(20)
                            .shadow(radius: 30)
                    } placeholder: {
                        RoundedRectangle(cornerRadius: 20)
                            .fill(Color.gray.opacity(0.2))
                            .frame(width: 400, height: 600)
                    }
                    .padding(.trailing, 80)
                    .opacity(isAnimating ? 1 : 0)
                    .offset(x: isAnimating ? 0 : 50)
                }
            }
        }
        .onAppear {
            withAnimation(.easeOut(duration: 0.8)) {
                isAnimating = true
            }
            
            // Start countdown task
            timerTask = Task {
                while countdown > 0 {
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                    if Task.isCancelled { return }
                    await MainActor.run {
                        countdown -= 1
                    }
                }
                if !Task.isCancelled {
                    await MainActor.run {
                        onStart()
                    }
                }
            }
        }
        .onDisappear {
            timerTask?.cancel()
            timerTask = nil
        }
        .onExitCommand {
            onBack()
        }
    }
    
    private var titleView: some View {
        Text(metadata.title)
            .font(.system(size: 72, weight: .black))
            .foregroundColor(.white)
    }
}
