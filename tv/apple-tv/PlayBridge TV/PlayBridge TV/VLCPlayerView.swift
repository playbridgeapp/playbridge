//
//  VLCPlayerView.swift
//  PlayBridge TV
//

import SwiftUI
import TVVLCKit

struct VLCPlayerView: UIViewControllerRepresentable {
    let mediaPlayer: VLCMediaPlayer

    func makeUIViewController(context: Context) -> VLCPlayerViewController {
        return VLCPlayerViewController(mediaPlayer: mediaPlayer)
    }

    func updateUIViewController(_ uiViewController: VLCPlayerViewController, context: Context) {
    }
}

class VLCPlayerViewController: UIViewController {
    private let mediaPlayer: VLCMediaPlayer

    init(mediaPlayer: VLCMediaPlayer) {
        self.mediaPlayer = mediaPlayer
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        mediaPlayer.drawable = view
    }
}
