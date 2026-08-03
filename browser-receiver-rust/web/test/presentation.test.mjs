import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createReceiverPresentation } from '../src/shared/presentation.js';

function element() {
  const classes = new Set();
  return {
    style: {},
    hidden: false,
    textContent: '',
    src: '',
    classList: {
      add: (...names) => names.forEach((name) => classes.add(name)),
      remove: (...names) => names.forEach((name) => classes.delete(name)),
      toggle: (name, force) => force ? classes.add(name) : classes.delete(name),
      contains: (name) => classes.has(name)
    }
  };
}

function receiverDocument() {
  const elements = new Map([
    'setup',
    'instructions',
    'code',
    'device-name',
    'player-wrap',
    'media-overlay',
    'media-artwork',
    'media-title',
    'status'
  ].map((id) => [id, element()]));
  return {
    body: { dataset: {} },
    getElementById: (id) => elements.get(id),
    elements
  };
}

test('Cast presentation keeps normal status hidden but exposes errors', () => {
  const document = receiverDocument();
  const presentation = createReceiverPresentation(document, { mode: 'cast' });
  const status = document.elements.get('status');

  presentation.showReady();
  assert.equal(document.elements.get('setup').style.display, 'block');
  assert.equal(document.elements.get('player-wrap').style.display, 'none');
  assert.equal(status.classList.contains('hidden'), true);

  presentation.showMedia({ title: 'Example' }, 'buffering');
  assert.equal(document.elements.get('setup').style.display, 'none');
  assert.equal(document.elements.get('player-wrap').style.display, 'block');
  assert.equal(status.classList.contains('hidden'), true);

  presentation.showStatus('Playback failed', true);
  assert.equal(status.textContent, 'Playback failed');
  assert.equal(status.classList.contains('hidden'), false);
  assert.equal(status.classList.contains('error'), true);

  presentation.showMedia({ title: 'Replacement' }, 'playing');
  assert.equal(status.classList.contains('hidden'), true);
  assert.equal(status.classList.contains('error'), false);
});

test('Cast shell keeps branding on ready and disables CAF playback branding', async () => {
  const html = await readFile(new URL('../index.html', import.meta.url), 'utf8');
  assert.match(html, /--splash-image:\s*none/);
  assert.match(html, /--watermark-image:\s*none/);
  assert.match(html, /data-receiver-mode="cast"\] #brand-logo \{ display: block; \}/);
  assert.doesNotMatch(html, /data-receiver-mode="cast"\] #status/);
});
