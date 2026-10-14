// Host tests for the real content/background transport and the shared controller.
// Run: node --test geckoview-plugin/app/src/test/js/pb-bridge.test.js (from tv/android).
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assets = path.resolve(__dirname, '../../main/assets/extensions/pb_bridge');
const controller = path.resolve(__dirname, '../../../../../player/app/src/main/assets/pb-video-control.js');
const read = file => fs.readFileSync(file, 'utf8');

function content() {
    const sent = [], events = {}, intervals = [];
    let receive;
    const listen = (name, fn) => {
        const previous = events[name];
        events[name] = (...args) => { if (previous) previous(...args); fn(...args); };
    };
    const video = {
        paused: true, currentTime: 20, duration: 100, volume: 0.5, muted: true, playbackRate: 1,
        getBoundingClientRect: () => ({ width: 100, height: 100, left: 0, top: 0, right: 100, bottom: 100 }),
        play() { this.paused = false; }, pause() { this.paused = true; }
    };
    const context = vm.createContext({
        crypto: { randomUUID: () => 'doc-1' },
        browser: { runtime: {
            sendMessage: message => { sent.push(message); return Promise.resolve(); },
            onMessage: { addListener: fn => { receive = fn; } }
        } },
        document: {
            title: 'Video', documentElement: { clientWidth: 100, clientHeight: 100 },
            querySelectorAll: query => query === 'video' ? [video] : [],
            addEventListener: listen
        },
        setInterval: fn => intervals.push(fn),
        innerWidth: 100, innerHeight: 100,
        addEventListener: listen
    });
    context.window = context;
    vm.runInContext(read(path.join(assets, 'content.js')), context);
    vm.runInContext(read(controller), context);
    return { context, video, sent, events, intervals, receive: (...args) => receive(...args) };
}

function background() {
    const sent = [], delivered = [], events = {};
    const port = {
        postMessage: message => sent.push(message),
        onMessage: { addListener: fn => { events.native = fn; } },
        onDisconnect: { addListener: fn => { events.disconnect = fn; } }
    };
    const context = vm.createContext({
        browser: {
            runtime: {
                connectNative: () => port,
                onMessage: { addListener: fn => { events.content = fn; } }
            },
            tabs: {
                query: async () => [{ id: 7 }],
                sendMessage: async (...args) => { delivered.push(args); return 'ok'; },
                onRemoved: { addListener: fn => { events.removed = fn; } }
            },
            webNavigation: {
                onCommitted: { addListener: fn => { events.committed = fn; } },
                getAllFrames: async query => (events.allFrames ? events.allFrames(query) : [])
            }
        },
        setTimeout: fn => { events.reconnect = fn; }
    });
    vm.runInContext(read(path.join(assets, 'background.js')), context);
    const send = (frameId, data, documentId = 'doc-1', active = true) => events.content({
        type: 'video', data: JSON.stringify(data), documentId
    }, { tab: { id: 7, active }, frameId });
    return { context, sent, delivered, events, send };
}

test('shared controller discovers DOM video and transports activation/status', () => {
    const { sent } = content();
    assert.equal(JSON.parse(sent[0].data).type, 'active');
    assert.equal(JSON.parse(sent[0].data).score, 10000);
    assert.equal(sent[0].documentId, 'doc-1');
    const status = JSON.parse(sent.at(-1).data);
    assert.equal(status.type, 'status');
    assert.equal(status.position, 20000);
    assert.equal(status.duration, 100000);
    assert.equal(status.muted, true);
});

test('targeted commands drive toggle, absolute/relative seek, volume and native overlay', () => {
    const c = content();
    const command = cmd => c.receive({ type: 'video_cmd', documentId: 'doc-1', cmd });
    command('toggle');
    assert.equal(c.video.paused, false);
    command('seek,10');
    assert.equal(c.video.currentTime, 30);
    command('seekto,50');
    assert.equal(c.video.currentTime, 50);
    command('vol,0.1');
    assert.equal(c.video.volume, 0.6);
    assert.ok(c.sent.some(message => JSON.parse(message.data).type === 'overlay'));
});

test('text uses the page prototype behind a Gecko Xray wrapper', () => {
    function PageCtor() {}
    Object.defineProperty(PageCtor.prototype, 'value', {
        configurable: true,
        set(next) { this._pageValue = next; },
    });
    const pageInput = textField();
    const pageWindow = {
        HTMLInputElement: PageCtor,
        HTMLTextAreaElement: PageCtor,
        InputEvent: function (type, init) { this.type = type; this.init = init; this.kind = 'input'; },
        Event: function (type, init) { this.type = type; this.init = init; this.kind = 'event'; },
    };
    const xray = {
        tagName: 'INPUT',
        wrappedJSObject: pageInput,
        _events: [],
        dispatchEvent(event) { this._events.push(event); },
        set value(next) { this._xrayValue = next; },
    };
    const page = loadMaximize((ctx, document) => {
        installTextGlobals(ctx);
        ctx.wrappedJSObject = pageWindow;
        document.hasFocus = () => true;
        document.activeElement = xray;
    });
    assert.equal(page.context.__pbvc.applyText('aGk='), 'ok');
    assert.equal(pageInput._pageValue, 'hi');
    assert.equal(pageInput._nativeValue, undefined);
    assert.equal(xray._xrayValue, undefined);
    assert.equal(pageInput._events[0].init.inputType, 'insertReplacementText');
    assert.equal(pageInput._events[0].init.data, 'hi');
    assert.equal(xray._events.length, 0);
});

test('content delivers focus commands to the controller and keeps eval', () => {
    const c = content();
    installTextGlobals(c.context);
    const input = textField();
    c.context.document.hasFocus = () => true;
    c.context.document.activeElement = input;
    let response = 'unset';
    c.receive({ type: 'focus_cmd', op: 'text', b64: 'aGk=' }, {}, value => { response = value; });
    assert.equal(response, '');
    assert.equal(input._nativeValue, 'hi');
    const posted = c.sent.map(message => JSON.parse(message.data)).filter(message => message.type === 'result');
    assert.equal(posted.at(-1).kind, 'text');
    assert.equal(posted.at(-1).value, 'ok');
    let evaluated;
    c.receive({ type: 'eval', code: '1 + 2' }, {}, value => { evaluated = value; });
    assert.equal(evaluated, '3');
});

test('content rejects commands for a replaced document and preserves eval', () => {
    const c = content();
    c.receive({ type: 'video_cmd', documentId: 'old-doc', cmd: 'toggle' });
    assert.equal(c.video.paused, true);
    let result;
    c.receive({ type: 'eval', code: '1 + 2' }, {}, value => { result = value; });
    assert.equal(result, '3');
});

test('periodic status reasserts activation and honors suppressUnmute', () => {
    const c = content();
    c.context.__pbSuppressUnmute = true;
    c.sent.length = 0;
    c.intervals[0]();
    assert.deepEqual(c.sent.map(message => JSON.parse(message.data).type), ['active', 'status']);
    assert.equal(JSON.parse(c.sent.at(-1).data).suppressUnmute, true);
});

test('pagehide retires video and BFCache restore resumes reporting', () => {
    const c = content();
    c.events.pagehide();
    assert.equal(JSON.parse(c.sent.at(-1).data).active, false);
    const count = c.sent.length;
    c.intervals[0]();
    c.receive({ type: 'video_cmd', documentId: 'doc-1', cmd: 'toggle' });
    assert.equal(c.sent.length, count);
    assert.equal(c.video.paused, true);
    c.events.pageshow();
    assert.equal(JSON.parse(c.sent.at(-1).data).active, true);
});

test('background adds trusted frame identity and forwards GeckoView tabs reported inactive', () => {
    const b = background();
    b.send(2, { type: 'active', active: true, score: 100, playing: false });
    assert.equal(b.sent[0].type, 'video');
    assert.equal(b.sent[0].tabId, 7);
    assert.equal(b.sent[0].frameId, 2);
    assert.equal(b.sent[0].documentId, 'doc-1');
    // GeckoView reports tab.active = false for the plugin's only session (seen on device).
    b.send(3, { type: 'status' }, 'other-doc', false);
    assert.equal(b.sent.length, 2);
    assert.equal(b.sent[1].frameId, 3);
});

test('eval reaches the session tab even though GeckoView reports no active tab', async () => {
    const b = background();
    const queries = [];
    b.context.browser.tabs.query = async filter => { queries.push(filter); return filter.active ? [] : [{ id: 7 }]; };
    await b.events.native({ type: 'eval', code: '1+2' });
    assert.equal(JSON.stringify(queries), "[{}]");
    assert.equal(b.delivered[0][0], 7);
    assert.equal(b.delivered[0][1].type, 'eval');
});

test('background targets native commands to one frame and keeps all-frame eval', async () => {
    const b = background();
    await b.events.native({ type: 'video_cmd', tabId: 7, frameId: 2, documentId: 'doc-1', cmd: 'seek,10' });
    assert.equal(b.delivered[0][0], 7);
    assert.equal(b.delivered[0][1].cmd, 'seek,10');
    assert.equal(b.delivered[0][2].frameId, 2);
    await b.events.native({ type: 'eval', code: '1+2' });
    assert.equal(b.delivered[1][1].type, 'eval');
    assert.equal(b.delivered[1].length, 2);
});

test('navigation retires a frame, top-level navigation retires all frames', () => {
    const b = background();
    b.send(0, { type: 'active', active: true });
    b.send(2, { type: 'active', active: true });
    b.send(3, { type: 'active', active: true });
    b.sent.length = 0;
    b.events.committed({ tabId: 7, frameId: 2 });
    assert.deepEqual(b.sent.map(message => message.frameId), [2]);
    assert.equal(JSON.parse(b.sent[0].data).active, false);
    b.sent.length = 0;
    b.events.committed({ tabId: 7, frameId: 0 });
    assert.deepEqual(b.sent.map(message => message.frameId), [0, 3]);
});

test('native reconnect replays live activation before status', () => {
    const b = background();
    b.send(2, { type: 'active', active: true });
    b.send(2, { type: 'status', state: 'playing' });
    b.events.disconnect();
    b.sent.length = 0;
    b.events.reconnect();
    assert.deepEqual(b.sent.map(message => JSON.parse(message.data).type), ['active', 'status']);
});

test('failed command retires its own document but leaves a replacement intact', async () => {
    const b = background();
    b.send(2, { type: 'active', active: true }, 'new-doc');
    b.context.browser.tabs.sendMessage = async () => { throw new Error('gone'); };
    b.sent.length = 0;
    await b.events.native({ type: 'video_cmd', tabId: 7, frameId: 2, documentId: 'old-doc', cmd: 'toggle' });
    assert.equal(b.sent.length, 0);
    await b.events.native({ type: 'video_cmd', tabId: 7, frameId: 2, documentId: 'new-doc', cmd: 'toggle' });
    assert.equal(b.sent.length, 1);
    assert.equal(JSON.parse(b.sent[0].data).active, false);
});

test('tab removal clears cached frames so reconnect cannot replay them', () => {
    const b = background();
    b.send(2, { type: 'active', active: true });
    b.send(2, { type: 'status', state: 'playing' });
    b.events.removed(7);
    b.events.disconnect();
    b.sent.length = 0;
    b.events.reconnect();
    assert.equal(b.sent.length, 0);
});

// Maximize tests load only the controller, with a page mock richer than content().
function stylable(extra) {
    const props = {};
    return Object.assign({
        style: { setProperty: (name, value) => { props[name] = value; } },
        _props: props,
        _styleAttr: null,
        _removedStyle: false,
        getAttribute(name) { return name === 'style' ? this._styleAttr : null; },
        setAttribute(name, value) { if (name === 'style') this._styleAttr = value; },
        removeAttribute(name) { if (name === 'style') { this._styleAttr = null; this._removedStyle = true; } },
        querySelectorAll() { return []; },
        contains(node) {
            for (let el = node; el; el = el.parentElement) if (el === this) return true;
            return false;
        },
    }, extra);
}

function loadMaximize(setup) {
    const posts = [];
    const parentPosts = [];
    const listeners = { window: {}, document: {} };
    const timers = [];
    const document = {
        title: 'Video',
        body: { tag: 'body' },
        documentElement: { clientWidth: 100, clientHeight: 100 },
        fullscreenElement: null,
        querySelectorAll: () => [],
        addEventListener: (name, fn) => { (listeners.document[name] ||= []).push(fn); },
        exitFullscreen() { document.fullscreenElement = null; },
    };
    const context = vm.createContext({
        document,
        Promise,
        navigator: { userActivation: { isActive: true } },
        setInterval: () => 0,
        setTimeout: (fn) => { timers.push(fn); return timers.length; },
        clearTimeout: (id) => { if (typeof id === 'number') timers[id - 1] = null; },
        innerWidth: 100,
        innerHeight: 100,
        addEventListener: (name, fn) => { (listeners.window[name] ||= []).push(fn); },
        pbVideoChannel: { onmessage: null, postMessage: data => posts.push(JSON.parse(data)) },
    });
    context.window = context;
    context.parent = { postMessage: message => parentPosts.push(message) };
    setup(context, document);
    // Successful native-request mocks expose the same DOM truth as a browser.
    const nodes = [...document.querySelectorAll('video'), ...document.querySelectorAll('iframe')];
    nodes.forEach(node => {
        for (let el = node; el && el !== document.body; el = el.parentElement) el.ownerDocument = document;
    });
    vm.runInContext(read(controller), context);
    const flushTimers = () => {
        const due = timers.splice(0, timers.length);
        due.forEach(fn => fn && fn());
    };
    return { context, document, posts, parentPosts, listeners, flushTimers };
}

function box(width, height, top = 0) {
    return () => ({ width, height, left: 0, top, right: width, bottom: top + height });
}

const resultsOf = posts => posts.filter(message => message.type === 'result');

async function settle() {
    for (let i = 0; i < 8; i++) await Promise.resolve();
}

test('maximize clicks the site fullscreen button and a repeat does not click again', () => {
    const calls = [];
    let clicks = 0;
    const page = loadMaximize((ctx, document) => {
        const button = {
            tagName: 'BUTTON',
            className: 'ytp-fullscreen-button',
            getAttribute: name => (name === 'aria-label' ? 'Full screen' : ''),
            click() {
                clicks++;
                document.fullscreenElement = shell;
            },
        };
        var shell = stylable({
            id: 'movie_player',
            className: 'html5-video-player',
            querySelectorAll: query => (query === 'button,[role="button"],a' ? [button] : []),
            requestFullscreen() { calls.push('shell'); this.ownerDocument.fullscreenElement = this; return Promise.resolve(); },
            getBoundingClientRect: box(100, 100),
        });
        shell.parentElement = document.body;
        const video = stylable({
            paused: false,
            parentElement: shell,
            getBoundingClientRect: box(100, 100),
            requestFullscreen() { calls.push('video'); this.ownerDocument.fullscreenElement = this; return Promise.resolve(); },
        });
        ctx.parent = ctx;
        document.querySelectorAll = query => (query === 'video' ? [video] : []);
        ctx.__video = video;
    });
    assert.equal(page.context.__pbvc.maximize(), 'ok_site');
    assert.equal(clicks, 1);
    assert.deepEqual(calls, []);
    assert.equal(resultsOf(page.posts).at(-1).value, 'ok_site');
    assert.equal(page.context.__video.__pbFit, undefined);
    assert.equal(page.context.__pbvc.maximize(), 'ok_held');
    assert.equal(clicks, 1);
    assert.equal(resultsOf(page.posts).at(-1).value, 'ok_held');
});

test('a site button that engages on the next turn still wins over requestFullscreen', () => {
    const calls = [];
    const page = loadMaximize((ctx, document) => {
        const button = {
            tagName: 'BUTTON',
            className: 'vjs-fullscreen-control',
            getAttribute: () => '',
            click() { clicks++; },
        };
        var clicks = 0;
        ctx.__clicks = () => clicks;
        const shell = stylable({
            className: 'video-js',
            querySelectorAll: query => (query === 'button,[role="button"],a' || query === 'button,[role="button"],input' ? [button] : []),
            requestFullscreen() { calls.push('shell'); this.ownerDocument.fullscreenElement = this; return Promise.resolve(); },
            getBoundingClientRect: box(100, 108),
        });
        shell.parentElement = document.body;
        button.click = () => { clicks++; };
        const video = stylable({
            parentElement: shell,
            getBoundingClientRect: box(100, 100),
            requestFullscreen() { calls.push('video'); this.ownerDocument.fullscreenElement = this; return Promise.resolve(); },
        });
        document.querySelectorAll = query => (query === 'video' ? [video] : []);
        ctx.__arm = () => { shell.className = 'video-js vjs-fullscreen'; document.fullscreenElement = shell; };
    });
    assert.equal(page.context.__pbvc.maximize(), 'pending');
    assert.deepEqual(calls, []);
    page.context.__arm();
    page.flushTimers();
    assert.equal(page.context.__clicks(), 1);
    assert.deepEqual(calls, []);
    assert.equal(resultsOf(page.posts).at(-1).value, 'ok_site');
});

test('fullscreen targets the player container that holds the controls', async () => {
    const calls = [];
    const seek = { tagName: 'BUTTON', className: 'vjs-play-control', getAttribute: () => '' };
    const shell = stylable({
        className: 'video-js',
        querySelectorAll: query => (query === 'button,[role="button"],input' || query === 'button,[role="button"],a' ? [seek] : []),
        requestFullscreen() { calls.push('shell'); this.ownerDocument.fullscreenElement = this; return Promise.resolve(); },
        getBoundingClientRect: box(100, 108),
    });
    const inner = stylable({
        className: 'html5-video-container',
        parentElement: shell,
        requestFullscreen() { calls.push('inner'); this.ownerDocument.fullscreenElement = this; return Promise.resolve(); },
        getBoundingClientRect: box(100, 100),
    });
    const video = stylable({
        tagName: 'VIDEO',
        controls: false,
        parentElement: inner,
        getBoundingClientRect: box(100, 100),
        requestFullscreen() { calls.push('video'); this.ownerDocument.fullscreenElement = this; return Promise.resolve(); },
    });
    const page = loadMaximize((ctx, document) => {
        shell.parentElement = document.body;
        ctx.parent = ctx;
        document.querySelectorAll = query => (query === 'video' ? [video] : []);
    });
    assert.equal(page.context.__pbvc.maximize(), 'pending');
    await settle();
    assert.deepEqual(calls, ['shell']);
    assert.equal(resultsOf(page.posts).at(-1).value, 'ok_video');
    assert.equal(video.__pbFit, undefined);
    assert.equal(inner.__pbFit, undefined);
    assert.equal(page.context.__pbvc.maximize(), 'ok_held');
    assert.deepEqual(calls, ['shell']);
});

test('a refused requestFullscreen fills the player container and restore leaves fullscreen alone', async () => {
    const childMessages = [];
    let exits = 0;
    const seek = { tagName: 'BUTTON', className: 'vjs-control', getAttribute: () => 'seek' };
    const shell = stylable({
        className: 'video-js',
        querySelectorAll: query => (query === 'button,[role="button"],input' || query === 'button,[role="button"],a' ? [seek] : []),
        requestFullscreen: () => Promise.reject(new Error('no-gesture')),
        getBoundingClientRect: box(80, 48),
    });
    const video = stylable({
        paused: true,
        controls: false,
        parentElement: shell,
        getBoundingClientRect: box(80, 40),
        requestFullscreen: () => Promise.reject(new Error('no-gesture')),
    });
    const iframe = stylable({
        contentWindow: { postMessage: message => childMessages.push(message) },
    });
    const page = loadMaximize((ctx, document) => {
        shell.parentElement = document.body;
        document.exitFullscreen = () => { exits++; document.fullscreenElement = null; };
        document.querySelectorAll = query => {
            if (query === 'video') return [video];
            if (query === 'iframe') return [iframe];
            return [];
        };
    });
    assert.equal(page.context.__pbvc.maximize(), 'pending');
    await settle();
    assert.equal(resultsOf(page.posts).at(-1).value, 'ok_fill');
    assert.equal(shell._props.position, 'fixed');
    assert.equal(shell._props.width, '100%');
    assert.equal(shell._props.height, '100%');
    assert.equal(shell._props['z-index'], '2147483647');
    assert.equal(video.__pbFit, undefined);
    assert.equal(video._props.position, undefined);
    assert.equal(page.parentPosts.at(-1).pb, 'pb-frame-fit');
    assert.equal(page.parentPosts.at(-1).op, 'enter');
    assert.equal(page.context.__pbvc.restore(), 'ok');
    assert.equal(exits, 0);
    assert.equal(shell.__pbFit, false);
    assert.equal(shell._removedStyle, true);
    assert.equal(page.parentPosts.at(-1).op, 'exit');
    assert.equal(childMessages.at(-1).pb, 'pb-frame-fit');
    assert.equal(childMessages.at(-1).op, 'exit-video');
    assert.equal(resultsOf(page.posts).at(-1).kind, 'restore');
    assert.equal(resultsOf(page.posts).at(-1).value, 'ok');
});

test('leaving a site fullscreen clicks the site button and skips exitFullscreen once it is gone', () => {
    let clicks = 0;
    let exits = 0;
    const page = loadMaximize((ctx, document) => {
        const button = {
            tagName: 'BUTTON',
            className: 'ytp-fullscreen-button',
            getAttribute: name => (name === 'aria-label' ? 'Full screen' : ''),
            click() {
                clicks++;
                document.fullscreenElement = clicks === 1 ? shell : null;
            },
        };
        var shell = stylable({
            className: 'html5-video-player',
            querySelectorAll: query => (query === 'button,[role="button"],a' ? [button] : []),
            getBoundingClientRect: box(100, 100),
        });
        shell.parentElement = document.body;
        const video = stylable({
            parentElement: shell,
            getBoundingClientRect: box(100, 100),
        });
        document.exitFullscreen = () => { exits++; document.fullscreenElement = null; };
        document.querySelectorAll = query => (query === 'video' ? [video] : []);
    });
    assert.equal(page.context.__pbvc.maximize(), 'ok_site');
    assert.equal(page.context.__pbvc.restore(), 'ok');
    assert.equal(clicks, 2);
    assert.equal(exits, 0);
    assert.equal(page.document.fullscreenElement, null);
});

test('a top frame with no video expands the largest iframe once', () => {
    const childMessages = [];
    let fullscreenCalls = 0;
    const iframe = stylable({
        offsetWidth: 200,
        offsetHeight: 80,
        contentWindow: { postMessage: message => childMessages.push(message) },
        requestFullscreen() { fullscreenCalls++; return Promise.resolve(); },
    });
    Object.defineProperty(iframe, 'contentDocument', { get() { throw new Error('cross-origin'); } });
    const page = loadMaximize((ctx, document) => {
        ctx.parent = ctx;
        document.querySelectorAll = query => (query === 'iframe' ? [iframe] : []);
    });
    assert.equal(page.context.__pbvc.maximize(), 'ok_iframe');
    assert.equal(iframe.__pbFit, true);
    assert.equal(fullscreenCalls, 1);
    assert.equal(childMessages.length, 1);
    assert.equal(childMessages[0].pb, 'pb-frame-fit');
    assert.equal(childMessages[0].op, 'fill-video');
    assert.equal(resultsOf(page.posts).at(-1).value, 'ok_iframe');
    assert.equal(page.context.__pbvc.maximize(), 'ok_held');
    assert.equal(fullscreenCalls, 1);
    assert.equal(childMessages.length, 1);
    assert.equal(resultsOf(page.posts).at(-1).value, 'ok_held');
});

test('a parent frame expands the iframe that sent enter and forwards the chain', () => {
    const source = {};
    let fullscreenCalls = 0;
    const iframe = stylable({
        contentWindow: source,
        requestFullscreen() { fullscreenCalls++; return Promise.resolve(); },
    });
    const page = loadMaximize((ctx, document) => {
        document.querySelectorAll = query => (query === 'iframe' ? [iframe] : []);
    });
    const deliver = event => page.listeners.window.message.forEach(fn => fn(event));
    deliver({ data: { pb: 'other', op: 'enter' }, source });
    assert.equal(iframe.__pbFit, undefined);
    deliver({ data: { pb: 'pb-frame-fit', op: 'enter' }, source });
    assert.equal(iframe.__pbFit, true);
    assert.equal(fullscreenCalls, 1);
    assert.equal(page.parentPosts.at(-1).pb, 'pb-frame-fit');
    assert.equal(page.parentPosts.at(-1).op, 'enter');
    deliver({ data: { pb: 'pb-frame-fit', op: 'enter' }, source });
    assert.equal(fullscreenCalls, 1);
    deliver({ data: { pb: 'pb-frame-fit', op: 'enter' }, source: {} });
    deliver({ data: { pb: 'pb-frame-fit', op: 'exit' }, source });
    assert.equal(iframe.__pbFit, false);
    assert.equal(iframe._removedStyle, true);
    assert.equal(page.parentPosts.at(-1).op, 'exit');
});

function latin1Escape(value) {
    let out = '';
    for (let i = 0; i < value.length; i++) {
        const code = value.charCodeAt(i) & 0xff;
        const ch = String.fromCharCode(code);
        if (/[A-Za-z0-9@*_+./-]/.test(ch)) out += ch;
        else out += '%' + code.toString(16).toUpperCase().padStart(2, '0');
    }
    return out;
}

function installTextGlobals(ctx) {
    function Ctor() {}
    Object.defineProperty(Ctor.prototype, 'value', {
        configurable: true,
        set(next) { this._nativeValue = next; },
    });
    ctx.HTMLInputElement = Ctor;
    ctx.HTMLTextAreaElement = Ctor;
    ctx.InputEvent = function (type, init) { this.type = type; this.init = init; this.kind = 'input'; };
    ctx.Event = function (type, init) { this.type = type; this.init = init; this.kind = 'event'; };
    ctx.KeyboardEvent = function (type, init) { this.type = type; this.init = init; this.kind = 'key'; };
    ctx.atob = encoded => Buffer.from(encoded, 'base64').toString('latin1');
    ctx.escape = latin1Escape;
}

function textField(extra) {
    return Object.assign({
        tagName: 'INPUT',
        _events: [],
        dispatchEvent(event) { this._events.push(event); },
        set value(next) { this._ownValue = next; },
    }, extra);
}

test('focused text replaces the field through the native setter and ignores other frames', () => {
    const input = textField();
    const page = loadMaximize((ctx, document) => {
        installTextGlobals(ctx);
        document.hasFocus = () => true;
        document.activeElement = input;
    });
    assert.equal(page.context.__pbvc.applyText('aGk='), 'ok');
    assert.equal(input._nativeValue, 'hi');
    assert.equal(input._ownValue, undefined);
    assert.equal(input._events[0].type, 'input');
    assert.equal(input._events[0].init.inputType, 'insertReplacementText');
    assert.equal(input._events[0].init.data, 'hi');
    assert.equal(input._events[1].type, 'change');
    assert.equal(resultsOf(page.posts).at(-1).kind, 'text');
    assert.equal(resultsOf(page.posts).at(-1).value, 'ok');

    page.context.document.hasFocus = () => false;
    assert.equal(page.context.__pbvc.applyText('YQ=='), 'no_focus');
    assert.equal(input._nativeValue, 'hi');

    page.context.document.hasFocus = () => true;
    page.context.document.activeElement = { tagName: 'IFRAME' };
    assert.equal(page.context.__pbvc.applyText('YQ=='), 'no_focus');
    assert.equal(input._nativeValue, 'hi');
});

test('text fills an open shadow input, a contenteditable, and clears on an empty payload', () => {
    const inner = textField();
    const host = { tagName: 'DIV', shadowRoot: { activeElement: inner } };
    const editable = textField({ tagName: 'DIV', isContentEditable: true });
    const page = loadMaximize((ctx, document) => {
        installTextGlobals(ctx);
        document.hasFocus = () => true;
        document.activeElement = host;
    });
    assert.equal(page.context.__pbvc.applyText('w6k='), 'ok');
    assert.equal(inner._nativeValue, 'é');
    assert.equal(editable.textContent, undefined);

    page.context.document.activeElement = editable;
    assert.equal(page.context.__pbvc.applyText(''), 'ok');
    assert.equal(editable.textContent, '');
    assert.equal(inner._nativeValue, 'é');
    page.context.pbVideoChannel.onmessage({ data: JSON.stringify({ type: 'text', b64: 'YQ==' }) });
    assert.equal(editable.textContent, 'a');
});

test('enter submits the focused form and a frame without focus does nothing', () => {
    let submitted = 0;
    const input = textField({ form: { requestSubmit() { submitted++; } } });
    const page = loadMaximize((ctx, document) => {
        installTextGlobals(ctx);
        document.hasFocus = () => true;
        document.activeElement = input;
    });
    assert.equal(page.context.__pbvc.submitFocused(), 'ok');
    assert.deepEqual(input._events.map(event => event.type), ['keydown', 'keypress', 'keyup']);
    assert.equal(input._events[0].init.key, 'Enter');
    assert.equal(submitted, 1);
    assert.equal(resultsOf(page.posts).at(-1).kind, 'enter');
    page.context.document.hasFocus = () => false;
    assert.equal(page.context.__pbvc.submitFocused(), 'no_focus');
    assert.equal(submitted, 1);
});

test('focus command is delivered to every reported frame', async () => {
    const broadcast = background();
    broadcast.events.allFrames = async () => [{ frameId: 0 }, { frameId: 4 }];
    await broadcast.events.native({ type: 'focus_cmd', op: 'text', b64: 'aGk=' });
    assert.equal(broadcast.delivered.length, 2);
    assert.deepEqual(broadcast.delivered.map(call => call[2].frameId).sort((a, b) => a - b), [0, 4]);
    assert.equal(broadcast.delivered[0][1].type, 'focus_cmd');
    assert.equal(broadcast.delivered[0][1].op, 'text');
    assert.equal(broadcast.delivered[0][1].b64, 'aGk=');
    assert.equal(broadcast.sent.length, 0);

    const known = background();
    known.events.allFrames = async () => { throw new Error('unavailable'); };
    known.send(3, { type: 'active', active: false });
    known.delivered.length = 0;
    await known.events.native({ type: 'focus_cmd', op: 'enter' });
    assert.equal(known.delivered.length, 1);
    assert.equal(known.delivered[0][2].frameId, 3);
    assert.equal(known.delivered[0][1].op, 'enter');

    const main = background();
    await main.events.native({ type: 'focus_cmd', op: 'enter' });
    assert.equal(main.delivered.length, 1);
    assert.equal(main.delivered[0][2].frameId, 0);
});


function remoteFullscreenPage(request = () => Promise.reject(new Error('no activation'))) {
    let clicks = 0;
    let requests = 0;
    const page = loadMaximize((ctx, document) => {
        ctx.parent = ctx;
        ctx.navigator.userActivation.isActive = false;
        const button = { tagName: 'BUTTON', className: 'vjs-fullscreen-control', getAttribute: () => '',
            click() { clicks++; } };
        const shell = stylable({ className: 'player fullscreen-wrapper',
            getBoundingClientRect: box(80, 80),
            querySelectorAll: query => query.includes('button') ? [button] : [],
            requestFullscreen() { requests++; return request.call(this, document); },
            parentElement: document.body });
        const video = stylable({ paused: false, parentElement: shell, getBoundingClientRect: box(80, 80) });
        document.querySelectorAll = query => query === 'video' ? [video] : [];
        ctx.__shell = shell;
        ctx.__video = video;
    });
    return Object.assign(page, { clicks: () => clicks, requests: () => requests });
}

test('fullscreen class names cannot claim native fullscreen; denied remote toggles repeat without a click', async () => {
    const page = remoteFullscreenPage();
    for (let i = 0; i < 3; i++) {
        assert.equal(page.context.__pbvc.maximize(), 'pending');
        assert.equal(page.context.__pbvc.maximize(), 'pending');
        await settle();
        assert.equal(resultsOf(page.posts).at(-1).value, 'ok_fill');
        assert.equal(page.context.__shell._props.position, 'fixed');
        assert.equal(page.context.__video.paused, false);
        assert.equal(page.context.__pbvc.maximize(), 'ok_held');
        assert.equal(page.context.__pbvc.restore(), 'ok');
        assert.equal(page.context.__shell.__pbFit, false);
        assert.equal(page.document.fullscreenElement, null);
        assert.equal(page.clicks(), 0);
        assert.equal(page.requests(), i + 1);
    }
});

test('restore with a stale site state cannot click a toggle into fullscreen', () => {
    let clicks = 0;
    const page = remoteFullscreenPage();
    page.context.navigator.userActivation.isActive = true;
    const shell = page.context.__shell;
    shell.querySelectorAll = () => [{ tagName: 'BUTTON', className: 'vjs-fullscreen-control', getAttribute: () => '',
        click() { clicks++; page.document.fullscreenElement = shell; } }];
    assert.equal(page.context.__pbvc.maximize(), 'ok_site');
    assert.equal(clicks, 1);
    page.document.fullscreenElement = null; // Browser/site exited before an event reached us.
    assert.equal(page.context.__pbvc.restore(), 'ok');
    assert.equal(clicks, 1);
    assert.equal(page.document.fullscreenElement, null);
});

test('a fulfilled request without a fullscreen element still uses CSS fill', async () => {
    const page = remoteFullscreenPage(() => Promise.resolve());
    page.context.__pbvc.maximize();
    await settle();
    assert.equal(resultsOf(page.posts).at(-1).value, 'ok_fill');
    assert.equal(page.document.fullscreenElement, null);
});

test('failed CSS fill cannot report fullscreen success', async () => {
    const page = remoteFullscreenPage();
    page.context.__shell.style.setProperty = () => { throw new Error('detached style'); };
    page.context.__pbvc.maximize();
    await settle();
    assert.equal(resultsOf(page.posts).at(-1).value, 'no_video');
});

test('restore cancels a pending denied request without ghost fullscreen', async () => {
    let reject;
    const page = remoteFullscreenPage(() => new Promise((resolve, fail) => { reject = fail; }));
    page.context.__pbvc.maximize();
    page.context.__pbvc.restore();
    reject(new Error('denied'));
    await settle();
    assert.equal(resultsOf(page.posts).at(-1).kind, 'restore');
    assert.equal(page.context.__shell.__pbFit, undefined);
});

test('restore exits a native request that settles late', async () => {
    let resolve;
    const page = remoteFullscreenPage(() => new Promise(done => { resolve = done; }));
    page.context.__pbvc.maximize();
    page.context.__pbvc.restore();
    page.document.fullscreenElement = page.context.__shell;
    resolve();
    await settle();
    assert.equal(page.document.fullscreenElement, null);
    assert.equal(resultsOf(page.posts).at(-1).kind, 'restore');
});

test('an existing real DOM fullscreen element reports confirmed native mode', () => {
    const page = remoteFullscreenPage();
    page.document.fullscreenElement = page.context.__shell;
    assert.equal(page.context.__pbvc.maximize(), 'ok_video');
    assert.equal(page.requests(), 0);
    assert.equal(page.clicks(), 0);
});

test('scroll retains playing title, duration, seek route and frame score while offscreen', () => {
    const c = content();
    c.video.paused = false;
    c.events.play();
    c.sent.length = 0;
    c.video.getBoundingClientRect = box(100, 100, -200);
    c.events.scroll();
    c.intervals[0]();
    const data = c.sent.map(message => JSON.parse(message.data));
    assert.ok(data.every(message => message.type !== 'active' || message.active));
    assert.ok(data.filter(message => message.type === 'active').every(message => message.score === 10000));
    const status = data.find(message => message.type === 'status');
    assert.equal(status.state, 'playing');
    assert.equal(status.title, 'Video');
    assert.equal(status.position, 20000);
    assert.equal(status.duration, 100000);
    c.receive({ type: 'video_cmd', documentId: 'doc-1', cmd: 'seekto,50' });
    assert.equal(c.video.currentTime, 50);
});

test('paused offscreen media can resume but removing the element clears status', () => {
    const c = content();
    c.video.getBoundingClientRect = box(100, 100, -200);
    c.events.scroll();
    c.intervals[0]();
    assert.equal(JSON.parse(c.sent.at(-1).data).state, 'paused');
    c.receive({ type: 'video_cmd', documentId: 'doc-1', cmd: 'toggle' });
    assert.equal(c.video.paused, false);
    c.sent.length = 0;
    c.context.document.querySelectorAll = () => [];
    c.events.scroll();
    assert.equal(JSON.parse(c.sent.at(-1).data).state, 'idle');
    assert.ok(c.sent.some(message => JSON.parse(message.data).active === false));
});

test('an unseen playing video cannot acquire control, and a visible ad cannot replace the offscreen incumbent', () => {
    const c = content();
    c.video.paused = false;
    c.events.play();
    c.video.getBoundingClientRect = box(100, 100, -200);
    const ad = { paused: false, currentTime: 1, duration: 5, getBoundingClientRect: box(100, 100) };
    c.context.document.querySelectorAll = query => query === 'video' ? [c.video, ad] : [];
    c.events.scroll();
    c.intervals[0]();
    assert.equal(JSON.parse(c.sent.at(-1).data).duration, 100000);
    c.context.document.querySelectorAll = query => query === 'video' ? [ad] : [];
    ad.getBoundingClientRect = box(100, 100, -200);
    c.events.scroll();
    assert.equal(JSON.parse(c.sent.at(-1).data).state, 'idle');
});

function zoomPageFixture(originalZoom = '', originalPriority = '') {
    const frames = new Map();
    const changes = [];
    let frameId = 0;
    let page;
    page = loadMaximize((ctx, document) => {
        ctx.parent = ctx;
        ctx.scrollX = 20; ctx.scrollY = 40;
        ctx.CSS = { supports: () => true };
        const props = new Map(originalZoom ? [['zoom', originalZoom]] : []);
        const priorities = new Map(originalZoom ? [['zoom', originalPriority]] : []);
        document.documentElement = {
            style: {
                setProperty(name, value, priority) { props.set(name, value); priorities.set(name, priority); changes.push(Number(value)); },
                removeProperty(name) { props.delete(name); priorities.delete(name); },
                getPropertyValue: name => props.get(name) || '',
                getPropertyPriority: name => priorities.get(name) || '',
            },
            getBoundingClientRect: () => ({ left: -ctx.scrollX, top: -ctx.scrollY }),
        };
        ctx.getComputedStyle = () => ({ zoom: originalZoom || '1' });
        ctx.requestAnimationFrame = fn => { frames.set(++frameId, fn); return frameId; };
        ctx.cancelAnimationFrame = id => frames.delete(id);
        ctx.scrollTo = point => { ctx.scrollX = point.left; ctx.scrollY = point.top; };
    });
    const flush = () => {
        let count = 0;
        while (frames.size) {
            assert.ok(++count < 500, 'zoom must settle');
            const due = [...frames.values()]; frames.clear(); due.forEach(fn => fn());
        }
    };
    return Object.assign(page, { frames, flush, changes });
}

test('zoom accumulates relative factors once per frame and converges smoothly to bounded targets', () => {
    const page = zoomPageFixture();
    assert.equal(page.context.__pbvc.zoomPage(2, 0.3, 0.75), 'ok');
    assert.equal(page.context.__pbvc.zoomPage(1.5, 0.3, 0.75), 'ok');
    assert.equal(page.frames.size, 1);
    assert.equal(page.changes.length, 0);
    page.flush();
    assert.equal(Number(page.document.documentElement.style.getPropertyValue('zoom')), 3);
    page.changes.forEach((value, i) => assert.ok(value / (page.changes[i - 1] || 1) <= Math.exp(0.12) + 1e-9));
    page.context.__pbvc.zoomPage(100, 0.5, 0.5); page.flush();
    assert.equal(Number(page.document.documentElement.style.getPropertyValue('zoom')), 3);
    page.context.__pbvc.zoomPage(0.001, 0.5, 0.5); page.flush();
    assert.equal(Number(page.document.documentElement.style.getPropertyValue('zoom')), 0.5);
});

test('zoom preserves the cursor focal point and a site-provided zoom on reset', () => {
    const page = zoomPageFixture();
    page.context.__pbvc.zoomPage(1.2, 0.3, 0.75); page.flush();
    assert.ok(Math.abs((page.context.scrollX + 30) / 1.2 - 50) < 1e-8);
    assert.ok(Math.abs((page.context.scrollY + 75) / 1.2 - 115) < 1e-8);
    page.context.__pbvc.resetPageZoom();
    assert.equal(page.document.documentElement.style.getPropertyValue('zoom'), '');
    const styled = zoomPageFixture('0.8', 'important');
    styled.context.__pbvc.zoomPage(1.5, 0.5, 0.5); styled.flush();
    assert.ok(Math.abs(Number(styled.document.documentElement.style.getPropertyValue('zoom')) - 1.2) < 1e-8);
    styled.context.__pbvc.resetPageZoom();
    assert.equal(styled.document.documentElement.style.getPropertyValue('zoom'), '0.8');
    assert.equal(styled.document.documentElement.style.getPropertyPriority('zoom'), 'important');
});

test('zoom rejects invalid factors, unsupported CSS and child-frame application', () => {
    const page = zoomPageFixture();
    for (const factor of [0, -1, NaN, Infinity, '1.5']) assert.equal(page.context.__pbvc.zoomPage(factor, 0.5, 0.5), 'invalid');
    assert.equal(page.context.__pbvc.zoomPage(1, NaN, 0), 'invalid');
    assert.equal(page.frames.size, 0);
    page.context.CSS.supports = () => false;
    assert.equal(page.context.__pbvc.zoomPage(1.5, 0.5, 0.5), 'unsupported');
    page.context.CSS.supports = () => true;
    page.context.parent = {};
    assert.equal(page.context.__pbvc.zoomPage(1.5, 0.5, 0.5), 'unsupported');
});

test('reset and pagehide cancel pending zoom without changing other styles', () => {
    const page = zoomPageFixture();
    page.document.documentElement.style.setProperty('color', 'red', '');
    page.context.__pbvc.zoomPage(1.5, 0.5, 0.5);
    const pending = [...page.frames.values()][0];
    page.context.__pbvc.resetPageZoom();
    pending(); // A stale callback that could not be cancelled must also be harmless.
    assert.equal(page.document.documentElement.style.getPropertyValue('zoom'), '');
    assert.equal(page.document.documentElement.style.getPropertyValue('color'), 'red');
    page.context.__pbvc.zoomPage(1.5, 0.5, 0.5); page.flush();
    page.listeners.window.pagehide.forEach(fn => fn());
    assert.equal(page.document.documentElement.style.getPropertyValue('zoom'), '');
    assert.equal(page.frames.size, 0);
});


test('a previously expanded ancestor iframe remains a successful fill without another native request', () => {
    let calls = 0;
    const child = { postMessage() {} };
    const iframe = stylable({ offsetWidth: 100, offsetHeight: 100, contentWindow: child,
        requestFullscreen() { calls++; return Promise.reject(new Error('denied')); } });
    const page = loadMaximize((ctx, document) => {
        ctx.parent = ctx;
        document.querySelectorAll = query => query === 'iframe' ? [iframe] : [];
    });
    page.listeners.window.message.forEach(fn => fn({ data: { pb: 'pb-frame-fit', op: 'enter' }, source: child }));
    assert.equal(calls, 1);
    assert.equal(page.context.__pbvc.maximize(), 'ok_iframe');
    assert.equal(calls, 1);
});

test('ending offscreen playback retires the playing pin and clears the seekbar', () => {
    const c = content();
    c.video.paused = false;
    c.events.play();
    c.video.getBoundingClientRect = box(100, 100, -200);
    c.events.scroll();
    c.sent.length = 0;
    c.video.ended = true;
    c.video.paused = true;
    c.events.ended();
    assert.ok(c.sent.some(message => JSON.parse(message.data).active === false));
    assert.equal(JSON.parse(c.sent.at(-1).data).state, 'idle');
});

test('legacy WebView status bridge also retains playing media after scroll', () => {
    const statuses = [], active = [], intervals = [], events = {};
    const video = { paused: false, currentTime: 12, duration: 90, muted: false, getBoundingClientRect: box(100, 100) };
    const ctx = vm.createContext({
        document: { title: 'Legacy video', documentElement: {},
            querySelectorAll: query => query === 'video' ? [video] : [],
            addEventListener(name, fn) { (events[name] ||= []).push(fn); } },
        innerWidth: 100, innerHeight: 100,
        PBVideoNative: { setActive: value => active.push(value), onStatus: value => statuses.push(JSON.parse(value)) },
        setInterval: fn => intervals.push(fn),
        addEventListener(name, fn) { (events[name] ||= []).push(fn); },
    });
    ctx.window = ctx; ctx.parent = ctx;
    vm.runInContext(read(controller), ctx);
    video.getBoundingClientRect = box(100, 100, -200);
    events.scroll.forEach(fn => fn());
    intervals[0]();
    assert.deepEqual(active, [true]);
    assert.equal(statuses.at(-1).title, 'Legacy video');
    assert.equal(statuses.at(-1).duration, 90000);
    assert.equal(statuses.at(-1).state, 'playing');
});
