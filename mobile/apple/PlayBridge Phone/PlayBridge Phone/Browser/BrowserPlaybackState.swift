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
    func nextExpiry(now: TimeInterval) -> TimeInterval? {
        frames.values.filter { now - $0 < 2.5 }.min().map { $0 + 2.5 }
    }
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

/// Canvas-backed Movi players use a CSS fullscreen fallback on iPhone. Observe
/// their DOM state in an isolated world without enabling the media detector or
/// depending on page-world custom-element methods.
enum BrowserMoviFullscreenScript {
    static let world = WKContentWorld.world(name: "PlayBridgeMoviFullscreen")
    static let source = #"""
    (() => {
      const players = new Map();
      let lastFullscreen;
      function report(fullscreen, force = false) {
        if (!force && fullscreen === lastFullscreen) return;
        lastFullscreen = fullscreen;
        try { window.webkit.messageHandlers.moviFullscreen.postMessage({fullscreen}); } catch (_) {}
      }
      function sample(force = false) {
        const native = document.fullscreenElement || document.webkitFullscreenElement;
        let fullscreen = false;
        for (const [player, observer] of players) {
          if (!player.isConnected || player.ownerDocument !== document) {
            observer.disconnect();
            player.removeEventListener('fullscreenchange', onFullscreenChange);
            players.delete(player);
            continue;
          }
          if (player.classList.contains('movi-pseudo-fullscreen') ||
              (native && (native === player || player.contains(native)))) fullscreen = true;
        }
        report(fullscreen, force);
      }
      function onFullscreenChange() { sample(); }
      function track(player) {
        if (players.has(player)) return;
        const observer = new MutationObserver(onFullscreenChange);
        observer.observe(player, {attributes: true, attributeFilter: ['class']});
        player.addEventListener('fullscreenchange', onFullscreenChange);
        players.set(player, observer);
      }
      function discover(node) {
        if (node.nodeType !== Node.ELEMENT_NODE) return;
        if (node.matches('movi-player')) track(node);
        for (const player of node.querySelectorAll('movi-player')) track(player);
      }
      // Only inspect newly inserted subtrees. Catalog image/attribute changes
      // do not trigger discovery or a whole-document rescan.
      new MutationObserver(records => {
        let removed = false;
        for (const record of records) {
          for (const node of record.addedNodes) discover(node);
          if (record.removedNodes.length) removed = true;
        }
        if (players.size || removed) sample();
      }).observe(document, {childList: true, subtree: true});
      for (const player of document.querySelectorAll('movi-player')) track(player);
      document.addEventListener('fullscreenchange', onFullscreenChange);
      document.addEventListener('webkitfullscreenchange', onFullscreenChange);
      window.addEventListener('pagehide', () => report(false, true));
      window.addEventListener('pageshow', () => sample(true));
      sample();
    })();
    """#
}
