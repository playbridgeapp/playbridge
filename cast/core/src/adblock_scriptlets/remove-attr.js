// SPDX-License-Identifier: MPL-2.0
// PlayBridge-written scriptlet resource (no uBlock Origin code).
function removeAttr(attrsArg, selectorArg) {
    if (typeof attrsArg !== "string" || !attrsArg) return;
    var attrs = attrsArg.split("|").map(function(s) { return s.trim(); }).filter(Boolean);
    if (!attrs.length) return;
    var sel = (typeof selectorArg === "string" && selectorArg.trim()) ? selectorArg.trim() : null;
    var rules = window.__pb_ra_rules;
    function applyRules() {
        if (!rules) return;
        for (var i = 0; i < rules.length; i++) {
            var r = rules[i];
            for (var j = 0; j < r.attrs.length; j++) {
                var attr = r.attrs[j];
                var q = r.sel ? r.sel : "[" + CSS.escape(attr) + "]";
                try {
                    var nodes = document.querySelectorAll(q);
                    for (var k = 0; k < nodes.length; k++) {
                        nodes[k].removeAttribute(attr);
                    }
                } catch (_) {}
            }
        }
    }
    if (!rules) {
        rules = [];
        try {
            Object.defineProperty(window, "__pb_ra_rules", { value: rules, configurable: false, enumerable: false });
        } catch (_) {
            window.__pb_ra_rules = rules;
        }
        var scheduled = false;
        var schedule = function() {
            if (scheduled) return;
            scheduled = true;
            requestAnimationFrame(function() {
                scheduled = false;
                applyRules();
            });
        };
        var startObserver = function() {
            var root = document.documentElement || document;
            if (!root || typeof MutationObserver === "undefined") return;
            try {
                new MutationObserver(schedule).observe(root, {
                    childList: true,
                    subtree: true,
                    attributes: true
                });
            } catch (_) {}
        };
        if (document.documentElement) startObserver();
        else window.addEventListener("DOMContentLoaded", startObserver, { once: true });
        window.addEventListener("DOMContentLoaded", applyRules, { once: true });
    }
    rules.push({ attrs: attrs, sel: sel });
    applyRules();
}
