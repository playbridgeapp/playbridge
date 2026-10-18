// SPDX-License-Identifier: MPL-2.0
// PlayBridge-written scriptlet resource (no uBlock Origin code).
function abortCurrentScript(propChain, needle) {
    if (typeof propChain !== "string" || !propChain) return;
    var parts = propChain.split(".");
    function toRegExp(pat) {
        if (typeof pat !== "string" || !pat) return null;
        if (pat.charAt(0) === "/" && pat.lastIndexOf("/") > 0) {
            var last = pat.lastIndexOf("/");
            try { return new RegExp(pat.slice(1, last), pat.slice(last + 1)); } catch (_) { return null; }
        }
        return new RegExp(pat.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
    }
    var re = toRegExp(needle);
    function shouldAbort() {
        try {
            var cs = document.currentScript;
            if (!re) return true;
            if (cs) {
                var text = (cs.textContent || "") + " " + (cs.src || "");
                if (re.test(text)) return true;
            }
            var stack = (new Error()).stack || "";
            if (re.test(stack)) return true;
        } catch (_) {}
        return false;
    }
    var abortIfMatched = function() {
        if (shouldAbort()) {
            throw new ReferenceError("PlayBridge aborted current script");
        }
    };
    function trap(obj, idx) {
        if (!obj || (typeof obj !== "object" && typeof obj !== "function")) return;
        var prop = parts[idx];
        if (idx === parts.length - 1) {
            var val = obj[prop];
            try {
                Object.defineProperty(obj, prop, {
                    configurable: true,
                    get: function() {
                        abortIfMatched();
                        return val;
                    },
                    set: function(next) {
                        abortIfMatched();
                        val = next;
                    }
                });
            } catch (_) {}
            return;
        }
        var current = obj[prop];
        if (current && (typeof current === "object" || typeof current === "function")) {
            trap(current, idx + 1);
        }
        try {
            var desc = Object.getOwnPropertyDescriptor(obj, prop);
            if (desc && !desc.configurable) return;
            Object.defineProperty(obj, prop, {
                configurable: true,
                get: function() { return current; },
                set: function(next) {
                    current = next;
                    if (next && (typeof next === "object" || typeof next === "function")) {
                        trap(next, idx + 1);
                    }
                }
            });
        } catch (_) {}
    }
    trap(window, 0);
}
