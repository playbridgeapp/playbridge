// SPDX-License-Identifier: MPL-2.0
// PlayBridge-written scriptlet resource (no uBlock Origin code).
function addEventListenerDefuser(typePattern, handlerPattern) {
    function toRegExp(pat) {
        if (typeof pat !== "string" || !pat) return null;
        if (pat.charAt(0) === "/" && pat.lastIndexOf("/") > 0) {
            var last = pat.lastIndexOf("/");
            try { return new RegExp(pat.slice(1, last), pat.slice(last + 1)); } catch (_) { return null; }
        }
        return new RegExp(pat.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
    }
    var typeRe = toRegExp(typePattern);
    var handlerRe = toRegExp(handlerPattern);
    var rules = window.__pb_aeld_rules;
    if (!rules) {
        rules = [];
        try {
            Object.defineProperty(window, "__pb_aeld_rules", { value: rules, configurable: false, enumerable: false });
        } catch (_) {
            window.__pb_aeld_rules = rules;
        }
        var proto = (typeof EventTarget !== "undefined" && EventTarget.prototype) ? EventTarget.prototype : window;
        var orig = proto.addEventListener;
        if (typeof orig === "function") {
            var origToString = orig.toString.bind(orig);
            var wrapped = function(type, listener, options) {
                try {
                    var tStr = String(type || "");
                    var fnStr = "";
                    if (typeof listener === "function") fnStr = Function.prototype.toString.call(listener);
                    else if (listener && typeof listener.handleEvent === "function") fnStr = Function.prototype.toString.call(listener.handleEvent);
                    for (var i = 0; i < rules.length; i++) {
                        var r = rules[i];
                        var matchType = !r.t || r.t.test(tStr);
                        var matchHandler = !r.h || r.h.test(fnStr);
                        if (matchType && matchHandler) return;
                    }
                } catch (_) {}
                return orig.apply(this, arguments);
            };
            try { wrapped.toString = origToString; } catch (_) {}
            try { proto.addEventListener = wrapped; } catch (_) {}
        }
    }
    rules.push({ t: typeRe, h: handlerRe });
}
