// SPDX-License-Identifier: MPL-2.0
// PlayBridge-written scriptlet resource (no uBlock Origin code).
function noSetIntervalIf(needle, delayArg) {
    function toRegExp(pat) {
        if (typeof pat !== "string" || !pat) return null;
        if (pat.charAt(0) === "/" && pat.lastIndexOf("/") > 0) {
            var last = pat.lastIndexOf("/");
            try { return new RegExp(pat.slice(1, last), pat.slice(last + 1)); } catch (_) { return null; }
        }
        return new RegExp(pat.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
    }
    var invertNeedle = false;
    var rawNeedle = typeof needle === "string" ? needle : "";
    if (rawNeedle.charAt(0) === "!") {
        invertNeedle = true;
        rawNeedle = rawNeedle.slice(1);
    }
    var needleRe = toRegExp(rawNeedle);
    var invertDelay = false;
    var parsedDelay = null;
    if (typeof delayArg === "string" && delayArg !== "") {
        var dStr = delayArg;
        if (dStr.charAt(0) === "!") {
            invertDelay = true;
            dStr = dStr.slice(1);
        }
        if (/^\d+$/.test(dStr)) parsedDelay = Number(dStr);
    }
    var rules = window.__pb_nosiif_rules;
    if (!rules) {
        rules = [];
        try {
            Object.defineProperty(window, "__pb_nosiif_rules", { value: rules, configurable: false, enumerable: false });
        } catch (_) {
            window.__pb_nosiif_rules = rules;
        }
        var orig = window.setInterval;
        if (typeof orig === "function") {
            var origToString = orig.toString.bind(orig);
            var nextFakeId = 950000;
            var wrapped = function(cb, delay) {
                try {
                    var cbStr = typeof cb === "function" ? Function.prototype.toString.call(cb) : String(cb || "");
                    var dVal = delay === undefined ? 0 : Number(delay);
                    for (var i = 0; i < rules.length; i++) {
                        var r = rules[i];
                        var mNeedle = r.re ? r.re.test(cbStr) : true;
                        if (r.invN) mNeedle = !mNeedle;
                        var mDelay = r.delay !== null ? (dVal === r.delay) : true;
                        if (r.invD) mDelay = !mDelay;
                        if (mNeedle && mDelay) return ++nextFakeId;
                    }
                } catch (_) {}
                return orig.apply(this, arguments);
            };
            try { wrapped.toString = origToString; } catch (_) {}
            try { window.setInterval = wrapped; } catch (_) {}
        }
    }
    rules.push({ re: needleRe, invN: invertNeedle, delay: parsedDelay, invD: invertDelay });
}
