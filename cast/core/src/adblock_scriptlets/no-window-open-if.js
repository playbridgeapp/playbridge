// SPDX-License-Identifier: MPL-2.0
// PlayBridge-written scriptlet resource (no uBlock Origin code).
function noWindowOpenIf(pattern, allowOrBlock) {
    function toRegExp(pat) {
        if (typeof pat !== "string" || !pat) return null;
        if (pat.charAt(0) === "/" && pat.lastIndexOf("/") > 0) {
            var last = pat.lastIndexOf("/");
            try { return new RegExp(pat.slice(1, last), pat.slice(last + 1)); } catch (_) { return null; }
        }
        return new RegExp(pat.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
    }
    var invert = false;
    var raw = typeof pattern === "string" ? pattern : "";
    if (raw.charAt(0) === "!") {
        invert = true;
        raw = raw.slice(1);
    }
    if (allowOrBlock === "0" || allowOrBlock === "false") {
        invert = !invert;
    }
    var re = toRegExp(raw);
    var rules = window.__pb_nowoif_rules;
    if (!rules) {
        rules = [];
        try {
            Object.defineProperty(window, "__pb_nowoif_rules", { value: rules, configurable: false, enumerable: false });
        } catch (_) {
            window.__pb_nowoif_rules = rules;
        }
        var orig = window.open;
        if (typeof orig === "function") {
            var origToString = orig.toString.bind(orig);
            var wrapped = function(url) {
                try {
                    var targetStr = url === undefined ? "" : String(url);
                    for (var i = 0; i < rules.length; i++) {
                        var r = rules[i];
                        var matched = r.re ? r.re.test(targetStr) : true;
                        if (r.inv) matched = !matched;
                        if (matched) return null;
                    }
                } catch (_) {}
                return orig.apply(this, arguments);
            };
            try { wrapped.toString = origToString; } catch (_) {}
            try { window.open = wrapped; } catch (_) {}
        }
    }
    rules.push({ re: re, inv: invert });
}
