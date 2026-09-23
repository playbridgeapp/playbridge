import Foundation
import WebKit

/// Frame reports are live observations, never persisted tab metadata. Expiry
/// handles detached frames and WebKit processes suspended without a pause event.
struct BrowserPlaybackState {
    private var frames: [String: TimeInterval] = [:]
    mutating func update(frame: String, playing: Bool, now: TimeInterval) {
        frames = frames.filter { now - $0.value < 2.5 }
        if playing, frames.count < 256 || frames[frame] != nil { frames[frame] = now }
        else { frames.removeValue(forKey: frame) }
    }
    func isPlaying(now: TimeInterval) -> Bool { frames.values.contains { now - $0 < 2.5 } }
}

enum BrowserPlaybackScript {
    static let world = WKContentWorld.world(name: "PlayBridgePlaybackState")
    static let source = #"""
    (() => {
      const pauseMessage = 'playbridge:pause-media';
      function pauseMediaInFrame() {
        for (const media of document.querySelectorAll('video,audio')) {
          try { media.pause(); } catch (_) {}
        }
        for (let index = 0; index < window.frames.length; index++) {
          try { window.frames[index].postMessage(pauseMessage, '*'); } catch (_) {}
        }
      }
      Object.defineProperty(window, '__playbridgePauseMedia', {value: pauseMediaInFrame});
      window.addEventListener('message', event => {
        if (event.data === pauseMessage && event.source === window.parent) pauseMediaInFrame();
      });
      const frame = String(Date.now()) + '-' + Math.random().toString(36).slice(2);
      const samples = new WeakMap();
      let timer = null, lastReport = 0, lastPlaying = false;
      function report(playing, force = false) {
        const now = performance.now();
        if (force || playing !== lastPlaying || (playing && now - lastReport >= 400)) {
          lastPlaying = playing; lastReport = now;
          try { window.webkit.messageHandlers.playbackState.postMessage({frame, playing}); } catch (_) {}
        }
      }
      function sample() {
        const now = performance.now();
        let active = false, playing = false;
        for (const media of document.querySelectorAll('video,audio')) {
          if (media.paused || media.ended) { samples.delete(media); continue; }
          active = true;
          const previous = samples.get(media);
          const advanced = !previous || Math.abs(media.currentTime - previous.time) > 0.001;
          const lastAdvance = advanced ? now : previous.lastAdvance;
          samples.set(media, {time: media.currentTime, lastAdvance});
          if (media.readyState >= 2 && now - lastAdvance < 1200) playing = true;
        }
        report(playing);
        if (active && timer === null) timer = setInterval(sample, 500);
        if (!active && timer !== null) { clearInterval(timer); timer = null; }
      }
      for (const event of ['play','playing','pause','ended','emptied','waiting','stalled','seeking','seeked','ratechange','timeupdate']) {
        document.addEventListener(event, sample, true);
      }
      window.addEventListener('pagehide', () => {
        if (timer !== null) clearInterval(timer);
        timer = null; report(false, true);
      });
      window.addEventListener('pageshow', sample);
    })();
    """#
}
