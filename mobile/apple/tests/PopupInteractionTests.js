const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const file = path.join(__dirname, '../PlayBridge Phone/PlayBridge Phone/Browser/BrowserInteraction.swift');
const source = fs.readFileSync(file, 'utf8').split('static let source = #"""')[1].split('"""#')[0];
const listeners = {}, messages = [];
class Element {
    constructor({anchor = false, target = '_blank', width = 120, height = 24, opacity = '1', parent = null} = {}) {
        Object.assign(this, {anchor, target, width, height, parentElement: parent, href: 'https://example.test/help',
            style: {opacity, display: 'block', visibility: 'visible', pointerEvents: 'auto'}});
    }
    matches() { return this.anchor; }
    closest() { return this.anchor ? this : null; }
    getBoundingClientRect() { return {width: this.width, height: this.height}; }
    getClientRects() { return [this.getBoundingClientRect()]; }
    getAttribute(name) { return name === 'target' ? this.target : null; }
    contains(hit) {
        for (let node = hit; node; node = node.parentElement) if (node === this) return true;
        return false;
    }
    getRootNode() { return {}; }
}
let hit;
const document = {elementFromPoint: () => hit, querySelector: () => null, addEventListener: () => {}};
const window = {webkit: {messageHandlers: {popupInteraction: {postMessage: body => messages.push(body)}}},
    addEventListener: (name, callback) => { listeners[name] = callback; }};
vm.runInNewContext(source, {window, document, navigator: {userActivation: {isActive: true}}, Element,
    innerWidth: 400, innerHeight: 800, getComputedStyle: element => element.style});
function click(anchor, trusted = true, target = anchor) {
    messages.length = 0; hit = target;
    const path = [];
    for (let node = target; node; node = node.parentElement) path.push(node);
    listeners.click({isTrusted: trusted, composedPath: () => path, clientX: 10, clientY: 10});
    return messages[0];
}
const link = new Element({anchor: true});
assert.equal(click(link, false), undefined);
assert.equal(click(link).newWindow, true);
assert.equal(click(link).href, link.href);
assert.equal(click(link).visible, true);
assert.equal(click(link).pointerSafe, true);
assert.equal(click(null).href, undefined); // Button scripts cannot claim an anchor.
assert.equal(click(new Element({anchor: true, target: '_self'})).newWindow, false);
assert.equal(click(new Element({anchor: true, width: 400, height: 800})).fraction, 1);
assert.equal(click(new Element({anchor: true, opacity: '0'})).opacity, 0);
assert.equal(click(new Element({anchor: true, parent: new Element({opacity: '0'})})).opacity, 0);
const overlay = new Element({width: 400, height: 800, opacity: '0.01', parent: link});
assert.equal(click(link, true, overlay).fraction, 1);
assert.equal(click(link, true, overlay).opacity, 0.01);
link.style.pointerEvents = 'none';
assert.equal(click(link).pointerSafe, false);
assert.match(fs.readFileSync(file, 'utf8'), /message\.world == Self\.world/);
console.log('PASS: isolated click listener rejects synthetic events and captures resolved anchor, target, coverage, ancestor opacity and pointer visibility');
