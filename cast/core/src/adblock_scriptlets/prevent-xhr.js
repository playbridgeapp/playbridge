// SPDX-License-Identifier: MPL-2.0
// PlayBridge-written scriptlet resource (no uBlock Origin code).
function preventXhr(propsFilter, responseBodyArg) {
    function toRegExp(pat) {
        if (typeof pat !== "string" || !pat) return null;
        if (pat.charAt(0) === "/" && pat.lastIndexOf("/") > 0) {
            var last = pat.lastIndexOf("/");
            try { return new RegExp(pat.slice(1, last), pat.slice(last + 1)); } catch (_) { return null; }
        }
        return new RegExp(pat.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
    }
    function parseMatchers(spec) {
        if (typeof spec !== "string" || !spec.trim() || spec.trim() === "*") return [];
        return spec.trim().split(/\s+/).map(function(part) {
            var colon = part.indexOf(":");
            if (colon > 0 && part.charAt(0) !== "/") {
                var key = part.slice(0, colon);
                var val = part.slice(colon + 1);
                if (key === "url" || key === "method") {
                    return { prop: key, re: toRegExp(val) };
                }
            }
            return { prop: "url", re: toRegExp(part) };
        });
    }
    var matchers = parseMatchers(propsFilter);
    var bodyType = typeof responseBodyArg === "string" ? responseBodyArg.trim() : "";
    var rules = window.__pb_pxhr_rules;
    if (!rules) {
        rules = [];
        try {
            Object.defineProperty(window, "__pb_pxhr_rules", { value: rules, configurable: false, enumerable: false });
        } catch (_) {
            window.__pb_pxhr_rules = rules;
        }
        var proto = typeof XMLHttpRequest !== "undefined" ? XMLHttpRequest.prototype : null;
        if (proto && typeof proto.open === "function" && typeof proto.send === "function") {
            var origOpen = proto.open;
            var origSend = proto.send;
            var origOpenStr = origOpen.toString.bind(origOpen);
            var origSendStr = origSend.toString.bind(origSend);
            var wrappedOpen = function(method, url) {
                try {
                    this.__pb_xhr_method = String(method || "GET");
                    this.__pb_xhr_url = String(url || "");
                } catch (_) {}
                return origOpen.apply(this, arguments);
            };
            var wrappedSend = function() {
                try {
                    var props = {
                        method: this.__pb_xhr_method || "GET",
                        url: this.__pb_xhr_url || ""
                    };
                    for (var i = 0; i < rules.length; i++) {
                        var r = rules[i];
                        var matched = true;
                        for (var j = 0; j < r.m.length; j++) {
                            var m = r.m[j];
                            var val = String(props[m.prop] || "");
                            if (m.re && !m.re.test(val)) { matched = false; break; }
                        }
                        if (matched) {
                            var bodyText = "";
                            if (r.bt === "emptyObj" || r.bt === "{}") bodyText = "{}";
                            else if (r.bt === "emptyArr" || r.bt === "[]") bodyText = "[]";
                            else if (r.bt === "true") bodyText = "true";
                            else if (r.bt === "false") bodyText = "false";
                            var self = this;
                            var respVal = bodyText;
                            if (self.responseType === "json") {
                                try { respVal = bodyText ? JSON.parse(bodyText) : null; } catch (_) { respVal = null; }
                            } else if (self.responseType === "arraybuffer") {
                                respVal = new ArrayBuffer(0);
                            } else if (self.responseType === "blob" && typeof Blob !== "undefined") {
                                respVal = new Blob([bodyText]);
                            }
                            try {
                                Object.defineProperty(self, "readyState", { configurable: true, get: function() { return 4; } });
                                Object.defineProperty(self, "status", { configurable: true, get: function() { return 200; } });
                                Object.defineProperty(self, "statusText", { configurable: true, get: function() { return "OK"; } });
                                Object.defineProperty(self, "responseText", { configurable: true, get: function() { return bodyText; } });
                                Object.defineProperty(self, "response", { configurable: true, get: function() { return respVal; } });
                                Object.defineProperty(self, "responseURL", { configurable: true, get: function() { return props.url; } });
                            } catch (_) {}
                            setTimeout(function() {
                                try {
                                    self.dispatchEvent(new Event("readystatechange"));
                                    self.dispatchEvent(new Event("load"));
                                    self.dispatchEvent(new Event("loadend"));
                                } catch (_) {}
                            }, 0);
                            return;
                        }
                    }
                } catch (_) {}
                return origSend.apply(this, arguments);
            };
            try { wrappedOpen.toString = origOpenStr; wrappedSend.toString = origSendStr; } catch (_) {}
            try { proto.open = wrappedOpen; proto.send = wrappedSend; } catch (_) {}
        }
    }
    rules.push({ m: matchers, bt: bodyType });
}
