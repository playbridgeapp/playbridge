// SPDX-License-Identifier: MPL-2.0
// PlayBridge-written scriptlet resource (no uBlock Origin code).
function preventFetch(propsFilter, responseBodyArg) {
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
                if (key === "url" || key === "method" || key === "credentials" || key === "mode" || key === "body") {
                    return { prop: key, re: toRegExp(val) };
                }
            }
            return { prop: "url", re: toRegExp(part) };
        });
    }
    var matchers = parseMatchers(propsFilter);
    var bodyType = typeof responseBodyArg === "string" ? responseBodyArg.trim() : "";
    var rules = window.__pb_pfetch_rules;
    if (!rules) {
        rules = [];
        try {
            Object.defineProperty(window, "__pb_pfetch_rules", { value: rules, configurable: false, enumerable: false });
        } catch (_) {
            window.__pb_pfetch_rules = rules;
        }
        var origFetch = window.fetch;
        if (typeof origFetch === "function") {
            var origToString = origFetch.toString.bind(origFetch);
            var wrappedFetch = function(input, init) {
                try {
                    var reqUrl = "";
                    var reqMethod = "GET";
                    var reqBody = "";
                    if (typeof input === "string") {
                        reqUrl = input;
                    } else if (input && typeof input === "object") {
                        if (typeof input.url === "string") reqUrl = input.url;
                        else reqUrl = String(input);
                        if (typeof input.method === "string") reqMethod = input.method;
                    }
                    if (init && typeof init === "object") {
                        if (typeof init.method === "string") reqMethod = init.method;
                        if (init.body !== undefined && init.body !== null) reqBody = String(init.body);
                    }
                    var props = { url: reqUrl, method: reqMethod, body: reqBody };
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
                            var ctype = "text/plain";
                            if (r.bt === "emptyObj" || r.bt === "{}") { bodyText = "{}"; ctype = "application/json"; }
                            else if (r.bt === "emptyArr" || r.bt === "[]") { bodyText = "[]"; ctype = "application/json"; }
                            else if (r.bt === "true") { bodyText = "true"; ctype = "application/json"; }
                            else if (r.bt === "false") { bodyText = "false"; ctype = "application/json"; }
                            return Promise.resolve(new Response(bodyText, {
                                status: 200,
                                statusText: "OK",
                                headers: { "Content-Type": ctype }
                            }));
                        }
                    }
                } catch (_) {}
                return origFetch.apply(this, arguments);
            };
            try { wrappedFetch.toString = origToString; } catch (_) {}
            try { window.fetch = wrappedFetch; } catch (_) {}
        }
    }
    rules.push({ m: matchers, bt: bodyType });
}
