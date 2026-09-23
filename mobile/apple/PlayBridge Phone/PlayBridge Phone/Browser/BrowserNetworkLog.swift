import Foundation
import Combine

final class BrowserNetworkLog: ObservableObject {
    struct Entry: Identifiable {
        let id: String
        let date: Date
        let url: String
        let host: String
        let page: String
        let isSubframe: Bool
        let kind: String
        let method: String
        var state: String
        var status: Int?
    }
    @Published private(set) var entries: [Entry] = []
    @Published private(set) var discarded = 0
    static let limit = 1000

    func clear() { entries.removeAll(); discarded = 0 }

    func record(id: String = UUID().uuidString, url raw: String, page: String,
                kind: String, method: String = "", state: String, status: Int? = nil, isSubframe: Bool = false) {
        guard raw.count <= 16384, let url = URL(string: raw),
              ["http", "https"].contains(url.scheme?.lowercased() ?? ""), let host = url.host?.lowercased() else { return }
        let key = String(id.prefix(160))
        if let index = entries.lastIndex(where: { $0.id == key }) {
            entries[index].state = String(state.prefix(80))
            entries[index].status = status
            return
        }
        entries.append(Entry(id: key, date: Date(), url: Self.safeURL(raw), host: host,
            page: Self.frameLabel(page, isSubframe: isSubframe), isSubframe: isSubframe, kind: String(kind.prefix(40)), method: String(method.prefix(12)),
            state: String(state.prefix(80)), status: status))
        if entries.count > Self.limit { discarded += entries.count - Self.limit; entries.removeFirst(entries.count - Self.limit) }
    }

    func ingest(_ body: Any, page: String, isSubframe: Bool = false) {
        guard let batch = body as? [[String: Any]] else { return }
        for item in batch.prefix(100) {
            guard let id = item["id"] as? String, let url = item["url"] as? String else { continue }
            record(id: id, url: url, page: page, kind: item["kind"] as? String ?? "resource",
                method: item["method"] as? String ?? "", state: item["state"] as? String ?? "Observed",
                status: item["status"] as? Int, isSubframe: isSubframe)
        }
    }

    private static func frameLabel(_ raw: String, isSubframe: Bool) -> String {
        if raw == "about:srcdoc" { return "Inline frame (srcdoc)" }
        if raw == "about:blank" { return isSubframe ? "Blank embedded frame" : "Blank page" }
        let safe = safeURL(raw)
        return safe.isEmpty && isSubframe ? "Embedded frame" : safe
    }

    static func safeURL(_ raw: String) -> String {
        guard raw.count <= 16384, var url = URLComponents(string: raw), ["http", "https"].contains(url.scheme?.lowercased() ?? "") else { return "" }
        url.user = nil; url.password = nil; url.fragment = nil
        url.queryItems = url.queryItems?.map { URLQueryItem(name: $0.name.count > 64 ? "[redacted]" : $0.name, value: "[redacted]") }
        var parts = url.path.components(separatedBy: "/").map { $0.contains("=") || $0.count >= 40 ? "[redacted]" : $0 }
        if parts.count > 2, parts[1] == "s" { parts[2] = "[redacted]" }
        url.path = parts.joined(separator: "/")
        return url.string ?? ""
    }

    var exportText: String {
        (["PlayBridge browser network log (URLs redacted; in-memory observations)"] + entries.map {
            "\($0.date.ISO8601Format()) \($0.method) \($0.kind) \($0.status.map(String.init) ?? $0.state)\n\($0.url)\nPage: \($0.page) [\($0.isSubframe ? "embedded frame" : "main page")]"
        }).joined(separator: "\n")
    }
}

enum BrowserNetworkScript {
    static let source = #"""
    (() => {
        const prefix = Date.now().toString(36) + Math.random().toString(36);
        let sequence = 0, queue = [], timer;
        const id = () => prefix + '-' + (++sequence);
        const absolute = value => { try { return new URL(value, document.baseURI).href; } catch (_) { return ''; } };
        function flush() {
            timer = null;
            const batch = queue.splice(0, 100);
            try { if (batch.length) window.webkit.messageHandlers.networkLog.postMessage(batch); } catch (_) {}
            if (queue.length) timer = setTimeout(flush, 100);
        }
        function emit(entry) {
            if (!/^https?:/i.test(entry.url)) return;
            if (queue.length < 1000) queue.push(entry);
            if (!timer) timer = setTimeout(flush, 100);
        }
        if (window.fetch) {
            const fetch = window.fetch;
            window.fetch = function(input, init) {
                const entry = {id:id(), url:absolute(typeof input === 'string' || input instanceof URL ? input : input && input.url),
                    kind:'fetch', method:String(init && init.method || input && input.method || 'GET'), state:'Pending'};
                emit({...entry});
                let result;
                try { result = fetch.apply(this, arguments); }
                catch (error) { emit({...entry, state:'Failed'}); throw error; }
                result.then(response => {
                    emit({...entry, state:response.type === 'opaque' ? 'Opaque response' : 'Response', status:response.status || null});
                    if (response.url && response.url !== entry.url) emit({id:id(), url:response.url, kind:'fetch redirect', state:'Response', status:response.status || null});
                },
                    () => emit({...entry, state:'Failed or blocked'}));
                return result;
            };
        }
        const requests = new WeakMap(), open = XMLHttpRequest.prototype.open, send = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.open = function(method, url) {
            const result = open.apply(this, arguments);
            requests.set(this, {url:absolute(url), method:String(method), kind:'XHR'});
            return result;
        };
        XMLHttpRequest.prototype.send = function() {
            const entry = {...requests.get(this), id:id(), state:'Pending'};
            emit({...entry});
            this.addEventListener('loadend', () => {
                emit({...entry, state:this.status ? 'Response' : 'Failed or blocked', status:this.status || null});
                if (this.responseURL && this.responseURL !== entry.url) emit({id:id(), url:this.responseURL, kind:'XHR redirect', state:'Response', status:this.status || null});
            }, {once:true});
            try { return send.apply(this, arguments); }
            catch (error) { emit({...entry, state:'Failed'}); throw error; }
        };
        try {
            new PerformanceObserver(list => {
                for (const entry of list.getEntries()) {
                    if (entry.initiatorType === 'fetch' || entry.initiatorType === 'xmlhttprequest') continue;
                    emit({id:id(), url:entry.name, kind:entry.initiatorType || 'resource', state:'Observed', status:entry.responseStatus || null});
                }
            }).observe({type:'resource', buffered:true});
        } catch (_) {}
        window.addEventListener('error', event => {
            const target = event.target;
            if (target && target !== window && (target.currentSrc || target.src || target.href))
                emit({id:id(), url:absolute(target.currentSrc || target.src || target.href), kind:(target.tagName || 'resource').toLowerCase(), state:'Failed or blocked'});
        }, true);
        window.addEventListener('pagehide', flush);
    })();
    """#
}
