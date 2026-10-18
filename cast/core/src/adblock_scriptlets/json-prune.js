// SPDX-License-Identifier: MPL-2.0
// PlayBridge-written scriptlet resource (no uBlock Origin code).
function jsonPrune(prunePaths, requiredPaths) {
    if (typeof prunePaths !== "string" || !prunePaths.trim()) return;
    function parsePaths(spec) {
        if (typeof spec !== "string" || !spec.trim()) return [];
        return spec.trim().split(/\s+/).map(function(p) { return p.split("."); });
    }
    var toPrune = parsePaths(prunePaths);
    var required = parsePaths(requiredPaths);
    function hasPath(obj, parts, idx) {
        if (!obj || typeof obj !== "object") return false;
        var key = parts[idx];
        var last = idx === parts.length - 1;
        if (key === "*" || key === "[]") {
            var keys = Object.keys(obj);
            for (var i = 0; i < keys.length; i++) {
                if (last || hasPath(obj[keys[i]], parts, idx + 1)) return true;
            }
            return false;
        }
        if (!Object.prototype.hasOwnProperty.call(obj, key)) return false;
        return last ? true : hasPath(obj[key], parts, idx + 1);
    }
    function deletePath(obj, parts, idx) {
        if (!obj || typeof obj !== "object") return;
        var key = parts[idx];
        var last = idx === parts.length - 1;
        if (key === "*" || key === "[]") {
            if (Array.isArray(obj) && last) {
                obj.length = 0;
                return;
            }
            var keys = Object.keys(obj);
            for (var i = 0; i < keys.length; i++) {
                if (last) delete obj[keys[i]];
                else deletePath(obj[keys[i]], parts, idx + 1);
            }
            return;
        }
        if (!Object.prototype.hasOwnProperty.call(obj, key)) return;
        if (last) delete obj[key];
        else deletePath(obj[key], parts, idx + 1);
    }
    var rules = window.__pb_json_prune_rules;
    if (!rules) {
        rules = [];
        try {
            Object.defineProperty(window, "__pb_json_prune_rules", { value: rules, configurable: false, enumerable: false });
        } catch (_) {
            window.__pb_json_prune_rules = rules;
        }
        var origParse = JSON.parse;
        if (typeof origParse === "function") {
            var origToString = origParse.toString.bind(origParse);
            var wrappedParse = function() {
                var res = origParse.apply(this, arguments);
                try {
                    if (res && typeof res === "object") {
                        for (var r = 0; r < rules.length; r++) {
                            var rule = rules[r];
                            var reqOk = true;
                            for (var q = 0; q < rule.req.length; q++) {
                                if (!hasPath(res, rule.req[q], 0)) { reqOk = false; break; }
                            }
                            if (reqOk) {
                                for (var p = 0; p < rule.prune.length; p++) {
                                    deletePath(res, rule.prune[p], 0);
                                }
                            }
                        }
                    }
                } catch (_) {}
                return res;
            };
            try { wrappedParse.toString = origToString; } catch (_) {}
            try { JSON.parse = wrappedParse; } catch (_) {}
        }
    }
    rules.push({ prune: toPrune, req: required });
}
