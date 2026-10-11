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
            addEventListener: (name, fn) => { events[name] = fn; }
        },
        setInterval: fn => intervals.push(fn),
        innerWidth: 100, innerHeight: 100,
        addEventListener: (name, fn) => { events[name] = fn; }
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
            webNavigation: { onCommitted: { addListener: fn => { events.committed = fn; } } }
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

test('background adds trusted frame identity and only forwards active tabs', () => {
    const b = background();
    b.send(2, { type: 'active', active: true, score: 100, playing: false });
    assert.equal(b.sent[0].type, 'video');
    assert.equal(b.sent[0].tabId, 7);
    assert.equal(b.sent[0].frameId, 2);
    assert.equal(b.sent[0].documentId, 'doc-1');
    b.send(3, { type: 'status' }, 'other-doc', false);
    assert.equal(b.sent.length, 1);
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
