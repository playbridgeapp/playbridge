// SPDX-License-Identifier: MPL-2.0
// PlayBridge-written scriptlet resource (no uBlock Origin code).
function removeClass(classesArg, selectorArg) {
    if (typeof classesArg !== "string" || !classesArg) return;
    var classes = classesArg.split("|").map(function(s) { return s.trim(); }).filter(Boolean);
    if (!classes.length) return;
    var sel = (typeof selectorArg === "string" && selectorArg.trim()) ? selectorArg.trim() : null;
    var rules = window.__pb_rc_rules;
    function applyRules() {
        if (!rules) return;
        for (var i = 0; i < rules.length; i++) {
            var r = rules[i];
            for (var j = 0; j < r.classes.length; j++) {
                var cls = r.classes[j];
                var q = r.sel ? r.sel : "." + CSS.escape(cls);
                try {
                    var nodes = document.querySelectorAll(q);
                    for (var k = 0; k < nodes.length; k++) {
                        nodes[k].classList.remove(cls);
                    }
                } catch (_) {}
            }
        }
    }
    if (!rules) {
        rules = [];
        try {
            Object.defineProperty(window, "__pb_rc_rules", { value: rules, configurable: false, enumerable: false });
        } catch (_) {
            window.__pb_rc_rules = rules;
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
                    attributes: true,
                    attributeFilter: ["class"]
                });
            } catch (_) {}
        };
        if (document.documentElement) startObserver();
        else window.addEventListener("DOMContentLoaded", startObserver, { once: true });
        window.addEventListener("DOMContentLoaded", applyRules, { once: true });
    }
    rules.push({ classes: classes, sel: sel });
    applyRules();
}
