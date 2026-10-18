// SPDX-License-Identifier: MPL-2.0
// PlayBridge-written scriptlet resource (no uBlock Origin code).
function setConstant(chain, rawVal) {
    if (typeof chain !== "string" || !chain) return;
    var constant;
    var noop = function() {};
    var trueFn = function() { return true; };
    var falseFn = function() { return false; };
    if (rawVal === "true") constant = true;
    else if (rawVal === "false") constant = false;
    else if (rawVal === "null") constant = null;
    else if (rawVal === "undefined" || rawVal === undefined) constant = undefined;
    else if (rawVal === "noopFunc") constant = noop;
    else if (rawVal === "trueFunc") constant = trueFn;
    else if (rawVal === "falseFunc") constant = falseFn;
    else if (rawVal === "''" || rawVal === '""' || rawVal === "") constant = "";
    else if (rawVal === "emptyArr") constant = [];
    else if (rawVal === "emptyObj") constant = {};
    else if (/^-?\d+(\.\d+)?$/.test(String(rawVal))) constant = Number(rawVal);
    else constant = rawVal;

    var parts = chain.split(".");
    function apply(obj, idx) {
        if (!obj || (typeof obj !== "object" && typeof obj !== "function")) return;
        var prop = parts[idx];
        if (idx === parts.length - 1) {
            try {
                Object.defineProperty(obj, prop, {
                    configurable: true,
                    get: function() { return constant; },
                    set: function() {}
                });
            } catch (_) {}
            return;
        }
        var current = obj[prop];
        if (current === undefined || current === null) {
            current = {};
            try { obj[prop] = current; } catch (_) {}
        }
        if (current && (typeof current === "object" || typeof current === "function")) {
            apply(current, idx + 1);
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
                        apply(next, idx + 1);
                    }
                }
            });
        } catch (_) {}
    }
    apply(window, 0);
}
