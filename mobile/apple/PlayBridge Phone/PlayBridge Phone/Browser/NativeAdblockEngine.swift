// SPDX-License-Identifier: MPL-2.0
import Foundation
import CryptoKit
#if canImport(PlayBridgeCastCore)
import PlayBridgeCastCore
#endif

enum AdblockFailure: String, Error {
    case unavailable, invalidResponse, engineFailure, conversionFailure
}

/// Immutable ownership of a native handle; Rust serializes access and never logs inputs.
final class NativeAdblockEngine: @unchecked Sendable {
    private let handle: UInt64
    private init(handle: UInt64) { self.handle = handle }
    deinit {
        #if canImport(PlayBridgeCastCore)
        pb_adblock_destroy(handle)
        #endif
    }
    private static func object(_ pointer: UnsafeMutablePointer<CChar>?) throws -> [String: Any] {
        #if canImport(PlayBridgeCastCore)
        guard let pointer else { throw AdblockFailure.invalidResponse }
        defer { pb_adblock_string_free(pointer) }
        guard let object = try JSONSerialization.jsonObject(with: Data(String(cString: pointer).utf8)) as? [String: Any],
              object["error"] == nil else { throw AdblockFailure.engineFailure }
        return object
        #else
        throw AdblockFailure.unavailable
        #endif
    }
    static func load(lists: [String], cached: Data?) throws -> NativeAdblockEngine {
        #if canImport(PlayBridgeCastCore)
        guard pb_adblock_abi_version() == 1 else { throw AdblockFailure.unavailable }
        let config = try JSONSerialization.data(withJSONObject: ["lists": lists])
        let created: [String: Any] = try config.withUnsafeBytes { configBytes in
            let p = configBytes.bindMemory(to: UInt8.self).baseAddress
            if let cached, let restored = try? cached.withUnsafeBytes({ bytes in
                try object(pb_adblock_deserialize(bytes.bindMemory(to: UInt8.self).baseAddress, bytes.count, p, config.count))
            }) { return restored }
            return try object(pb_adblock_create_json(p, config.count))
        }
        guard let handle = created["handle"] as? UInt64, handle != 0 else { throw AdblockFailure.invalidResponse }
        return NativeAdblockEngine(handle: handle)
        #else
        throw AdblockFailure.unavailable
        #endif
    }
    func serialize() throws -> Data {
        #if canImport(PlayBridgeCastCore)
        var count = 0
        guard let bytes = pb_adblock_serialize(handle, &count) else { throw AdblockFailure.engineFailure }
        defer { pb_adblock_bytes_free(bytes, count) }
        return Data(bytes: bytes, count: count)
        #else
        throw AdblockFailure.unavailable
        #endif
    }
    func matches(url: String, source: String, type: String, method: String = "GET") -> Bool {
        #if canImport(PlayBridgeCastCore)
        guard let input = try? JSONSerialization.data(withJSONObject: ["op": "check", "url": url,
            "source_url": source, "request_type": type, "method": method]),
              let result = try? input.withUnsafeBytes({ bytes in
                  try Self.object(pb_adblock_call_json(handle, bytes.bindMemory(to: UInt8.self).baseAddress, bytes.count))
              }) else { return false }
        return result["matched"] as? Bool == true
        #else
        return false
        #endif
    }
    func cosmetic(url: String) -> AdblockCosmeticResources {
        #if canImport(PlayBridgeCastCore)
        guard let input = try? JSONSerialization.data(withJSONObject: ["op": "cosmetic", "url": url]),
              let result = try? input.withUnsafeBytes({ bytes in
                  try Self.object(pb_adblock_call_json(handle, bytes.bindMemory(to: UInt8.self).baseAddress, bytes.count))
              }) else { return .empty }
        return AdblockCosmeticResources(
            hideSelectors: (result["hide_selectors"] as? [String])?.sorted() ?? [],
            proceduralActions: (result["procedural_actions"] as? [String])?.sorted() ?? [],
            exceptions: (result["exceptions"] as? [String])?.sorted() ?? [],
            injectedScript: (result["injected_script"] as? String) ?? "",
            generichide: (result["generichide"] as? Bool) ?? false
        )
        #else
        return .empty
        #endif
    }
    static func chunks(lists: [String]) throws -> [String] {
        #if canImport(PlayBridgeCastCore)
        let input = try JSONSerialization.data(withJSONObject: ["lists": lists, "chunk_limit": 140000, "json_strings": true])
        let result = try input.withUnsafeBytes { bytes in
            try object(pb_adblock_content_blocking_json(bytes.bindMemory(to: UInt8.self).baseAddress, bytes.count))
        }
        guard let chunks = result["chunks"] as? [String] else { throw AdblockFailure.conversionFailure }
        return chunks
        #else
        throw AdblockFailure.unavailable
        #endif
    }
}

struct AdblockCosmeticResources: Sendable, Equatable {
    var hideSelectors: [String]
    var proceduralActions: [String]
    var exceptions: [String]
    var injectedScript: String
    var generichide: Bool

    static let empty = AdblockCosmeticResources(
        hideSelectors: [],
        proceduralActions: [],
        exceptions: [],
        injectedScript: "",
        generichide: false
    )

    static let exemptHostSuffixes = [
        "youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com", "ytimg.com"
    ]

    static func isHostExempt(_ host: String) -> Bool {
        let normalized = host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        guard !normalized.isEmpty else { return false }
        return exemptHostSuffixes.contains { NavigationAdRules.host(normalized, matches: $0) }
    }

    static func normalizedHost(for url: URL) -> String? {
        guard let scheme = url.scheme?.lowercased(), ["http", "https"].contains(scheme),
              let host = url.host?.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: ".")),
              !host.isEmpty else { return nil }
        return host
    }

    func scriptletSource(forHost host: String) -> String? {
        let trimmed = injectedScript.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              let hostData = try? JSONSerialization.data(withJSONObject: [host.lowercased()]),
              let hostArray = String(data: hostData, encoding: .utf8) else { return nil }
        return """
        (function() {
          try {
            var expectedHost = (\(hostArray))[0] || "";
            var currentHost = ((window.location && window.location.hostname) || "").toLowerCase().replace(/\\.$/, "");
            if (expectedHost && currentHost !== expectedHost) {
              var proto = (window.location && window.location.protocol) || "";
              if (!(window !== window.top && currentHost === "" && (proto === "about:" || proto === "blob:" || proto === "data:"))) {
                return;
              }
            }
            \(trimmed)
          } catch (_) {}
        })();
        """
    }

    func proceduralCosmeticSource(forHost host: String) -> String? {
        let parsedRules: [[String: Any]] = proceduralActions.compactMap { raw in
            guard let data = raw.data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
            return obj
        }
        guard !parsedRules.isEmpty || !hideSelectors.isEmpty else { return nil }
        let payloadObj: [String: Any] = [
            "host": host.lowercased(),
            "hideSelectors": hideSelectors,
            "rules": parsedRules
        ]
        guard let payloadData = try? JSONSerialization.data(withJSONObject: payloadObj, options: [.sortedKeys]),
              let payloadJSON = String(data: payloadData, encoding: .utf8) else { return nil }
        return Self.proceduralRunnerSource(payloadJSON: payloadJSON)
    }

    private static func proceduralRunnerSource(payloadJSON: String) -> String {
        return #"""
        (function(config) {
          try {
            if (!config || typeof config !== "object") return;
            var expectedHost = String(config.host || "").toLowerCase().replace(/\.$/, "");
            var currentHost = ((window.location && window.location.hostname) || "").toLowerCase().replace(/\.$/, "");
            if (expectedHost && currentHost !== expectedHost) {
              var proto = (window.location && window.location.protocol) || "";
              if (!(window !== window.top && currentHost === "" && (proto === "about:" || proto === "blob:" || proto === "data:"))) {
                return;
              }
            }

            var MAX_CANDIDATES = 2000;
            var rules = Array.isArray(config.rules) ? config.rules : [];
            var hideSelectors = Array.isArray(config.hideSelectors) ? config.hideSelectors : [];

            function parseRegex(raw) {
              if (typeof raw !== "string") return null;
              if (raw.length >= 2 && raw.charAt(0) === "/") {
                var lastSlash = raw.lastIndexOf("/");
                if (lastSlash > 0) {
                  var pattern = raw.slice(1, lastSlash);
                  var flags = raw.slice(lastSlash + 1);
                  if (/^[gimsuy]*$/.test(flags)) {
                    try { return new RegExp(pattern, flags); } catch (_) { return null; }
                  }
                }
              }
              return null;
            }

            function matchSubstringOrRegex(haystack, needle) {
              var text = String(haystack == null ? "" : haystack);
              var raw = String(needle == null ? "" : needle);
              var re = parseRegex(raw);
              if (re) return re.test(text);
              return text.indexOf(raw) !== -1;
            }

            function matchExactOrRegex(haystack, needle) {
              var text = String(haystack == null ? "" : haystack);
              var raw = String(needle == null ? "" : needle);
              var re = parseRegex(raw);
              if (re) return re.test(text);
              return text === raw;
            }

            function unquote(str) {
              var s = String(str == null ? "" : str).trim();
              if (s.length >= 2) {
                var first = s.charAt(0);
                var last = s.charAt(s.length - 1);
                if ((first === "\"" && last === "\"") || (first === "'" && last === "'")) {
                  return s.slice(1, -1);
                }
              }
              return s;
            }

            function parseAttrSpec(arg) {
              var raw = String(arg == null ? "" : arg).trim();
              if (!raw) return null;
              if (raw.charAt(0) === "/") {
                var i = 1;
                while (i < raw.length) {
                  if (raw.charAt(i) === "\\") { i += 2; continue; }
                  if (raw.charAt(i) === "/") break;
                  i++;
                }
                if (i < raw.length) {
                  i++;
                  while (i < raw.length && /[gimsuy]/.test(raw.charAt(i))) i++;
                  var nameRegex = raw.slice(0, i);
                  var rest = raw.slice(i).trim();
                  if (rest.charAt(0) === "=") {
                    return { name: nameRegex, value: unquote(rest.slice(1)) };
                  }
                  return { name: nameRegex, value: null };
                }
              }
              var eq = raw.indexOf("=");
              if (eq === -1) {
                return { name: unquote(raw), value: null };
              }
              return {
                name: unquote(raw.slice(0, eq)),
                value: unquote(raw.slice(eq + 1))
              };
            }

            function uniqueElements(list) {
              var out = [];
              var seen = typeof Set === "function" ? new Set() : null;
              for (var i = 0; i < list.length && out.length < MAX_CANDIDATES; i++) {
                var el = list[i];
                if (!el || el.nodeType !== 1) continue;
                if (seen) {
                  if (seen.has(el)) continue;
                  seen.add(el);
                } else if (out.indexOf(el) !== -1) {
                  continue;
                }
                out.push(el);
              }
              return out;
            }

            function allDocumentElements() {
              if (!document.documentElement) return [];
              var all = document.querySelectorAll("*");
              var out = [];
              for (var i = 0; i < all.length && out.length < MAX_CANDIDATES; i++) {
                out.push(all[i]);
              }
              return out;
            }

            function evaluateChain(steps, initialNodes) {
              if (!Array.isArray(steps) || steps.length === 0) return [];
              var candidates = initialNodes || null;
              for (var i = 0; i < steps.length; i++) {
                var step = steps[i];
                if (!step || typeof step !== "object") return [];
                var type = step.type;
                var arg = step.arg != null ? String(step.arg) : "";

                if (type === "matches-path") {
                  var pathAndQuery = ((window.location && window.location.pathname) || "") + ((window.location && window.location.search) || "");
                  if (!matchSubstringOrRegex(pathAndQuery, arg)) return [];
                  continue;
                }

                if (type === "css-selector") {
                  var sel = arg.trim();
                  if (!sel) return [];
                  if (candidates === null) {
                    try {
                      candidates = uniqueElements(document.querySelectorAll(sel));
                    } catch (_) {
                      return [];
                    }
                  } else {
                    var next = [];
                    var combinator = /^[\s>+~]/.test(arg);
                    for (var c = 0; c < candidates.length && next.length < MAX_CANDIDATES; c++) {
                      var base = candidates[c];
                      try {
                        if (combinator) {
                          var found = base.querySelectorAll(":scope " + sel);
                          for (var f = 0; f < found.length && next.length < MAX_CANDIDATES; f++) {
                            next.push(found[f]);
                          }
                        } else if (base.matches && base.matches(sel)) {
                          next.push(base);
                        }
                      } catch (_) {}
                    }
                    candidates = uniqueElements(next);
                  }
                } else if (type === "xpath") {
                  var roots = candidates === null ? [document] : candidates;
                  var xpOut = [];
                  for (var r = 0; r < roots.length && xpOut.length < MAX_CANDIDATES; r++) {
                    try {
                      var snap = document.evaluate(arg, roots[r], null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
                      for (var x = 0; x < snap.snapshotLength && xpOut.length < MAX_CANDIDATES; x++) {
                        var node = snap.snapshotItem(x);
                        if (node && node.nodeType === 1) xpOut.push(node);
                      }
                    } catch (_) {}
                  }
                  candidates = uniqueElements(xpOut);
                } else {
                  if (candidates === null) {
                    candidates = allDocumentElements();
                  }
                  var filtered = [];
                  if (type === "has-text") {
                    for (var h = 0; h < candidates.length; h++) {
                      var hel = candidates[h];
                      if (matchSubstringOrRegex(hel.textContent, arg)) {
                        filtered.push(hel);
                      }
                    }
                  } else if (type === "min-text-length") {
                    var minLen = parseInt(arg.trim(), 10);
                    if (!isNaN(minLen)) {
                      for (var m = 0; m < candidates.length; m++) {
                        var mel = candidates[m];
                        var t = String(mel.textContent || "").trim();
                        if (t.length >= minLen) filtered.push(mel);
                      }
                    }
                  } else if (type === "upward") {
                    var upSpec = arg.trim();
                    var isNum = /^\d+$/.test(upSpec);
                    var stepsUp = isNum ? parseInt(upSpec, 10) : 0;
                    for (var u = 0; u < candidates.length; u++) {
                      var cur = candidates[u];
                      if (isNum) {
                        if (stepsUp >= 1 && stepsUp <= 64) {
                          var n = stepsUp;
                          while (n > 0 && cur) {
                            cur = cur.parentElement;
                            n--;
                          }
                          if (cur && cur.nodeType === 1 && cur !== document.documentElement) {
                            filtered.push(cur);
                          }
                        }
                      } else if (upSpec) {
                        var p = cur.parentElement;
                        while (p && p.nodeType === 1 && p !== document.documentElement) {
                          try {
                            if (p.matches && p.matches(upSpec)) {
                              filtered.push(p);
                              break;
                            }
                          } catch (_) { break; }
                          p = p.parentElement;
                        }
                      }
                    }
                  } else if (type === "matches-attr") {
                    var attrSpec = parseAttrSpec(arg);
                    if (attrSpec) {
                      for (var a = 0; a < candidates.length; a++) {
                        var ael = candidates[a];
                        var attrs = ael.attributes;
                        if (!attrs) continue;
                        for (var ai = 0; ai < attrs.length; ai++) {
                          var attr = attrs[ai];
                          if (matchExactOrRegex(attr.name, attrSpec.name)) {
                            if (attrSpec.value === null || matchExactOrRegex(attr.value, attrSpec.value)) {
                              filtered.push(ael);
                              break;
                            }
                          }
                        }
                      }
                    }
                  } else if (type === "matches-css" || type === "matches-css-before" || type === "matches-css-after") {
                    var colon = arg.indexOf(":");
                    if (colon > 0) {
                      var propName = arg.slice(0, colon).trim();
                      var expectedVal = arg.slice(colon + 1).trim();
                      var pseudo = type === "matches-css-before" ? "::before" : (type === "matches-css-after" ? "::after" : null);
                      for (var cs = 0; cs < candidates.length; cs++) {
                        var cel = candidates[cs];
                        try {
                          var computed = window.getComputedStyle(cel, pseudo);
                          if (!computed) continue;
                          var actualVal = (computed.getPropertyValue(propName) || computed[propName] || "").trim();
                          if (matchExactOrRegex(actualVal, expectedVal)) {
                            filtered.push(cel);
                          }
                        } catch (_) {}
                      }
                    }
                  } else {
                    return [];
                  }
                  candidates = uniqueElements(filtered);
                }

                if (!candidates || candidates.length === 0) return [];
              }
              return candidates || [];
            }

            function applyAction(el, action) {
              if (!el || el.nodeType !== 1 || el === document.documentElement) return;
              if (!action) {
                if (el.style.getPropertyValue("display") !== "none" || el.style.getPropertyPriority("display") !== "important") {
                  el.style.setProperty("display", "none", "important");
                }
                return;
              }
              var type = action.type;
              var arg = action.arg != null ? String(action.arg) : "";
              if (type === "remove") {
                if (el.parentNode && el !== document.body) {
                  el.remove();
                }
              } else if (type === "style") {
                var decls = arg.split(";");
                for (var i = 0; i < decls.length; i++) {
                  var decl = decls[i].trim();
                  if (!decl) continue;
                  var colon = decl.indexOf(":");
                  if (colon <= 0) continue;
                  var prop = decl.slice(0, colon).trim();
                  var val = decl.slice(colon + 1).trim();
                  var prio = "";
                  if (/!important\s*$/i.test(val)) {
                    val = val.replace(/!important\s*$/i, "").trim();
                    prio = "important";
                  }
                  if (el.style.getPropertyValue(prop) !== val || el.style.getPropertyPriority(prop) !== prio) {
                    el.style.setProperty(prop, val, prio);
                  }
                }
              } else if (type === "remove-attr") {
                var spec = arg.trim();
                if (!spec || !el.attributes) return;
                var re = parseRegex(spec);
                if (re) {
                  var names = [];
                  for (var a = 0; a < el.attributes.length; a++) {
                    if (re.test(el.attributes[a].name)) names.push(el.attributes[a].name);
                  }
                  for (var n = 0; n < names.length; n++) el.removeAttribute(names[n]);
                } else {
                  var parts = spec.split("|");
                  for (var p = 0; p < parts.length; p++) {
                    var attrName = parts[p].trim();
                    if (attrName && el.hasAttribute(attrName)) el.removeAttribute(attrName);
                  }
                }
              } else if (type === "remove-class") {
                var cspec = arg.trim();
                if (!cspec || !el.classList || el.classList.length === 0) return;
                var cre = parseRegex(cspec);
                if (cre) {
                  var removeList = [];
                  for (var c = 0; c < el.classList.length; c++) {
                    if (cre.test(el.classList[c])) removeList.push(el.classList[c]);
                  }
                  for (var r = 0; r < removeList.length; r++) el.classList.remove(removeList[r]);
                } else {
                  var cparts = cspec.split("|");
                  for (var cp = 0; cp < cparts.length; cp++) {
                    var cls = cparts[cp].trim();
                    if (cls && el.classList.contains(cls)) el.classList.remove(cls);
                  }
                }
              }
            }

            function applyHideSelectors() {
              if (!hideSelectors.length) return;
              var root = document.head || document.documentElement;
              if (!root) return;
              var styleId = "__pb_adblock_hide_style";
              var styleEl = document.getElementById(styleId);
              var cssText = hideSelectors.join(",\n") + " { display: none !important; }";
              if (!styleEl) {
                styleEl = document.createElement("style");
                styleEl.id = styleId;
                styleEl.textContent = cssText;
                root.appendChild(styleEl);
              } else if (styleEl.textContent !== cssText) {
                styleEl.textContent = cssText;
              }
            }

            function runPass() {
              try {
                if (!document.documentElement) return;
                applyHideSelectors();
                for (var i = 0; i < rules.length; i++) {
                  var rule = rules[i];
                  if (!rule || !Array.isArray(rule.selector)) continue;
                  var matched = evaluateChain(rule.selector, null);
                  for (var j = 0; j < matched.length; j++) {
                    applyAction(matched[j], rule.action || null);
                  }
                }
              } catch (_) {}
            }

            var scheduled = false;
            function schedulePass() {
              if (scheduled) return;
              scheduled = true;
              setTimeout(function() {
                scheduled = false;
                runPass();
              }, 35);
            }

            runPass();
            if (document.readyState === "loading") {
              document.addEventListener("DOMContentLoaded", runPass, { once: true });
            }
            window.addEventListener("load", runPass, { once: true });

            if (typeof MutationObserver === "function") {
              var observerInstalled = false;
              var installObserver = function() {
                if (observerInstalled || !document.documentElement) return;
                observerInstalled = true;
                var obs = new MutationObserver(schedulePass);
                obs.observe(document.documentElement, {
                  childList: true,
                  subtree: true,
                  attributes: true,
                  attributeFilter: ["class", "id", "style", "hidden", "src", "href"]
                });
              };
              installObserver();
              if (!observerInstalled) {
                document.addEventListener("DOMContentLoaded", installObserver, { once: true });
              }
            }
          } catch (_) {}
        })(#PAYLOAD#);
        """#.replacingOccurrences(of: "#PAYLOAD#", with: payloadJSON)
    }
}

struct AdblockSiteScripts: Sendable, Equatable {
    var scriptlet: String?
    var cosmetic: String?

    static let empty = AdblockSiteScripts(scriptlet: nil, cosmetic: nil)
}

/// Readers retain a snapshot before releasing the lock; replacement cannot free an in-flight handle.
final class AdblockSnapshot: @unchecked Sendable {
    static let shared = AdblockSnapshot()
    private let lock = NSLock()
    private var engine: NativeAdblockEngine?
    func publish(_ next: NativeAdblockEngine) { lock.lock(); engine = next; lock.unlock() }
    func matches(url: String, source: String, type: String, method: String = "GET") -> Bool {
        lock.lock(); let current = engine; lock.unlock()
        return current?.matches(url: url, source: source, type: type, method: method) ?? false
    }
    func cosmetic(url: String) -> AdblockCosmeticResources {
        lock.lock(); let current = engine; lock.unlock()
        return current?.cosmetic(url: url) ?? .empty
    }
    func siteScripts(for url: URL?, enabled: Bool, isBridgedApp: Bool = false) -> AdblockSiteScripts {
        guard enabled, !isBridgedApp, let url,
              let host = AdblockCosmeticResources.normalizedHost(for: url),
              !AdblockCosmeticResources.isHostExempt(host) else {
            return .empty
        }
        let resources = cosmetic(url: url.absoluteString)
        return AdblockSiteScripts(
            scriptlet: resources.scriptletSource(forHost: host),
            cosmetic: resources.proceduralCosmeticSource(forHost: host)
        )
    }
}

/// Parsing, hashing, serialization and conversion never run on the main actor.
actor AdblockEngineWorker {
    static let shared = AdblockEngineWorker()
    private let directory: URL
    init(directory: URL? = nil) {
        self.directory = directory ?? FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
            .appendingPathComponent("PlayBridgeAdblock-v1", isDirectory: true)
    }
    func load(files: [URL]) throws -> (NativeAdblockEngine, [String]) {
        guard files.count <= 128 else { throw AdblockFailure.engineFailure }
        var lists: [String] = []
        var bytes = 0
        for file in files {
            guard FileManager.default.fileExists(atPath: file.path) else { continue }
            let size = (try file.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0
            bytes += size
            guard size <= 16 * 1024 * 1024, bytes <= 32 * 1024 * 1024 else { throw AdblockFailure.engineFailure }
            let text = try String(contentsOf: file, encoding: .utf8)
            let head = text.prefix(4096).lowercased()
            guard !head.contains("<html"), !head.contains("<!doctype html") else { continue }
            // Legacy downloads have not expanded includes yet; apply the profile
            // immediately, then ensureListsDownloaded refreshes them transactionally.
            lists.append(AdblockListPreparser.activeLines(text).joined(separator: "\n"))
        }
        let cache = directory.appendingPathComponent("engine.bin")
        let cacheSize = (try? cache.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
        let cached = cacheSize > 0 && cacheSize <= 64 * 1024 * 1024 ? try? Data(contentsOf: cache) : nil
        let engine = try NativeAdblockEngine.load(lists: lists, cached: cached)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        if let serialized = try? engine.serialize() { try? serialized.write(to: cache, options: .atomic) }
        return (engine, lists)
    }
    struct Chunk: Sendable { let identifier: String; let json: String }
    static func cacheIdentifier(for text: String) -> String {
        SHA256.hash(data: Data(("engine-v1\n" + text).utf8)).map { String(format: "%02x", $0) }.joined()
    }
    func convert(lists: [String]) throws -> [Chunk] {
        try NativeAdblockEngine.chunks(lists: lists).map {
            Chunk(identifier: "playbridge-adblock-engine-" + Self.cacheIdentifier(for: $0), json: $0)
        }
    }
}
