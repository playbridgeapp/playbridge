// SPDX-License-Identifier: MPL-2.0
// PlayBridge-written scriptlet resource (no uBlock Origin code).
function abortOnPropertyWrite(chain) {
    if (typeof chain !== "string" || !chain) return;
    var parts = chain.split(".");
    var abort = function() {
        throw new ReferenceError("PlayBridge aborted property write");
    };
    function trap(obj, idx) {
        if (!obj || (typeof obj !== "object" && typeof obj !== "function")) return;
        var prop = parts[idx];
        if (idx === parts.length - 1) {
            var val = obj[prop];
            try {
                Object.defineProperty(obj, prop, {
                    configurable: true,
                    get: function() { return val; },
                    set: abort
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
