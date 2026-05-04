package server

import (
	"fmt"
	"net/http"
)

// handleConfigure serves GET /configure
// Renders a plain HTML page for managing source addons. Stremio opens this
// when the user clicks "Configure" on the addon card.
func (s *Server) handleConfigure(w http.ResponseWriter, r *http.Request) {
	s.mu.RLock()
	baseURL := s.cfg.BaseURL
	s.mu.RUnlock()

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprint(w, configurePage(baseURL))
}

func configurePage(baseURL string) string {
	return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Stream Resolver — Configure</title>
  <style>
    *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      background: #0f0f0f; color: #e0e0e0;
      min-height: 100vh; padding: 2rem 1rem;
    }
    .container { max-width: 680px; margin: 0 auto; }
    h1 { font-size: 1.5rem; font-weight: 600; margin-bottom: 0.25rem; color: #fff; }
    .subtitle { font-size: 0.85rem; color: #888; margin-bottom: 2rem; }
    h2 { font-size: 1rem; font-weight: 600; color: #ccc; margin-bottom: 1rem; }
    .card {
      background: #1a1a1a; border: 1px solid #2a2a2a;
      border-radius: 10px; padding: 1.25rem; margin-bottom: 1.5rem;
    }
    .addon-row {
      display: flex; align-items: center; gap: 0.75rem;
      padding: 0.6rem 0; border-bottom: 1px solid #252525;
    }
    .addon-row:last-child { border-bottom: none; }
    .addon-info { flex: 1; min-width: 0; }
    .addon-name { font-weight: 500; font-size: 0.9rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .addon-url  { font-size: 0.75rem; color: #666; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .priority-badge {
      font-size: 0.7rem; background: #2a2a2a; color: #888;
      border-radius: 4px; padding: 2px 6px; white-space: nowrap;
    }
    .btn-remove {
      background: none; border: 1px solid #3a1a1a; color: #c0392b;
      border-radius: 6px; padding: 4px 10px; font-size: 0.75rem;
      cursor: pointer; white-space: nowrap;
    }
    .btn-remove:hover { background: #3a1a1a; }
    .empty { color: #555; font-size: 0.875rem; padding: 0.5rem 0; }
    .form-row { display: flex; gap: 0.5rem; flex-wrap: wrap; margin-bottom: 0.75rem; }
    .form-row:last-child { margin-bottom: 0; }
    input[type="text"], input[type="number"] {
      background: #111; border: 1px solid #333; color: #e0e0e0;
      border-radius: 6px; padding: 0.5rem 0.75rem; font-size: 0.875rem;
      outline: none;
    }
    input[type="text"]:focus, input[type="number"]:focus { border-color: #555; }
    input.url-input { flex: 1; min-width: 200px; }
    input.name-input { width: 140px; }
    input.priority-input { width: 80px; }
    input.timeout-input { width: 90px; }
    .btn-add {
      background: #1a3a1a; border: 1px solid #2d6a2d; color: #4caf50;
      border-radius: 6px; padding: 0.5rem 1.1rem; font-size: 0.875rem;
      cursor: pointer; white-space: nowrap;
    }
    .btn-add:hover { background: #2d5a2d; }
    .notice {
      font-size: 0.75rem; color: #666; margin-top: 0.5rem;
    }
    .install-bar {
      background: #1a1a1a; border: 1px solid #2a2a2a;
      border-radius: 10px; padding: 1.25rem;
      display: flex; align-items: center; gap: 0.75rem; flex-wrap: wrap;
    }
    .install-url {
      flex: 1; font-size: 0.8rem; color: #888;
      background: #111; border: 1px solid #2a2a2a;
      border-radius: 6px; padding: 0.4rem 0.75rem;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .btn-install {
      background: #7b2fbe; border: none; color: #fff;
      border-radius: 6px; padding: 0.5rem 1.1rem;
      font-size: 0.875rem; cursor: pointer; white-space: nowrap;
      text-decoration: none;
    }
    .btn-install:hover { background: #9b4fde; }
    #status { font-size: 0.8rem; margin-top: 0.5rem; min-height: 1.2em; }
    #meta-status { font-size: 0.8rem; margin-top: 0.5rem; min-height: 1.2em; }
    .ok  { color: #4caf50; }
    .err { color: #c0392b; }
    .ttl-row { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 0.75rem; flex-wrap: wrap; }
    .ttl-row:last-of-type { margin-bottom: 0; }
    .ttl-label { width: 160px; font-size: 0.875rem; color: #ccc; flex-shrink: 0; }
    .ttl-input { width: 100px; }
    .ttl-unit  { font-size: 0.8rem; color: #666; }
    .toggle-row { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 0.75rem; }
    .toggle-label { width: 160px; font-size: 0.875rem; color: #ccc; flex-shrink: 0; }
    .toggle { position: relative; display: inline-block; width: 40px; height: 22px; flex-shrink: 0; }
    .toggle input { opacity: 0; width: 0; height: 0; }
    .toggle-slider {
      position: absolute; cursor: pointer; inset: 0;
      background: #333; border-radius: 22px; transition: background 0.2s;
    }
    .toggle-slider::before {
      content: ""; position: absolute;
      width: 16px; height: 16px; left: 3px; bottom: 3px;
      background: #aaa; border-radius: 50%; transition: transform 0.2s, background 0.2s;
    }
    .toggle input:checked + .toggle-slider { background: #2d6a2d; }
    .toggle input:checked + .toggle-slider::before { transform: translateX(18px); background: #4caf50; }
    #probing-status { font-size: 0.8rem; margin-top: 0.5rem; min-height: 1.2em; }
    .cache-actions { display: flex; gap: 0.5rem; flex-wrap: wrap; margin-top: 1rem; }
    .btn-clear {
      background: none; border: 1px solid #333; color: #888;
      border-radius: 6px; padding: 0.4rem 0.9rem; font-size: 0.8rem;
      cursor: pointer; white-space: nowrap;
    }
    .btn-clear:hover { background: #222; color: #ccc; }
    .btn-save {
      background: #1a2a3a; border: 1px solid #2d5a8a; color: #5b9bd5;
      border-radius: 6px; padding: 0.5rem 1.1rem; font-size: 0.875rem;
      cursor: pointer; white-space: nowrap;
    }
    .btn-save:hover { background: #2d4a6a; }
    #cache-status { font-size: 0.8rem; margin-top: 0.5rem; min-height: 1.2em; }
  </style>
</head>
<body>
<div class="container">
  <h1>Stream Resolver</h1>
  <p class="subtitle">Aggregate, rank, and validate streams from multiple Stremio addons.</p>

  <div class="card">
    <h2>Source Addons</h2>
    <div id="addon-list"><p class="empty">Loading…</p></div>
  </div>

  <div class="card">
    <h2>Add Source Addon</h2>
    <div class="form-row">
      <input id="in-url"      class="url-input"      type="text"   placeholder="Addon manifest URL or base URL" />
      <input id="in-name"     class="name-input"     type="text"   placeholder="Name (optional)" />
      <input id="in-priority" class="priority-input" type="number" placeholder="Priority" value="0" min="0" />
      <input id="in-timeout"  class="timeout-input"  type="number" placeholder="Timeout ms" value="8000" min="1000" />
    </div>
    <div class="form-row">
      <button class="btn-add" onclick="addAddon()">Add Addon</button>
    </div>
    <p class="notice">Lower priority number = tried first. AIOStreams should be 0. Increase timeout for slow addons.</p>
    <p id="status"></p>
  </div>

  <div class="card">
    <h2>Meta Addons</h2>
    <p class="notice" style="margin-bottom:1rem;">Used to look up runtime for probing validation. Queried in order — first match wins. Must support the <code>meta</code> resource (e.g. Cinemeta).</p>
    <div id="meta-addon-list"><p class="empty">Loading…</p></div>
  </div>

  <div class="card">
    <h2>Add Meta Addon</h2>
    <div class="form-row">
      <input id="meta-in-url"     class="url-input"     type="text"   placeholder="Meta addon manifest URL or base URL" />
      <input id="meta-in-name"    class="name-input"    type="text"   placeholder="Name (optional)" />
      <input id="meta-in-timeout" class="timeout-input" type="number" placeholder="Timeout ms" value="5000" min="1000" />
    </div>
    <div class="form-row">
      <button class="btn-add" onclick="addMetaAddon()">Add Meta Addon</button>
    </div>
    <p class="notice">Cinemeta: <code>https://v3-cinemeta.strem.io</code></p>
    <p id="meta-status"></p>
  </div>

  <div class="card">
    <h2>Cache</h2>
    <div class="ttl-row">
      <span class="ttl-label">Stream list TTL</span>
      <input id="ttl-streams" class="ttl-input" type="number" min="0" placeholder="300" />
      <span class="ttl-unit">seconds</span>
    </div>
    <div class="ttl-row">
      <span class="ttl-label">Play result TTL</span>
      <input id="ttl-play" class="ttl-input" type="number" min="0" placeholder="3600" />
      <span class="ttl-unit">seconds</span>
    </div>
    <div class="ttl-row">
      <span class="ttl-label">Meta (runtime) TTL</span>
      <input id="ttl-meta" class="ttl-input" type="number" min="0" placeholder="86400" />
      <span class="ttl-unit">seconds</span>
    </div>
    <div style="margin-top:0.75rem;">
      <button class="btn-save" onclick="saveCacheTTLs()">Save TTLs</button>
    </div>
    <div class="cache-actions">
      <button class="btn-clear" onclick="clearCache('streams')">Clear stream cache</button>
      <button class="btn-clear" onclick="clearCache('play')">Clear play cache</button>
      <button class="btn-clear" onclick="clearCache('meta')">Clear meta cache</button>
      <button class="btn-clear" onclick="clearCache('all')">Clear all</button>
    </div>
    <p id="cache-status"></p>
  </div>

  <div class="card">
    <h2>Probing</h2>
    <div class="toggle-row">
      <span class="toggle-label">Enable probing</span>
      <label class="toggle">
        <input type="checkbox" id="probing-enabled" />
        <span class="toggle-slider"></span>
      </label>
      <span style="font-size:0.8rem;color:#666;">Uses ffprobe to validate stream duration before playback</span>
    </div>
    <div class="ttl-row">
      <span class="ttl-label">Max attempts</span>
      <input id="probing-max-attempts" class="ttl-input" type="number" min="1" placeholder="5" />
      <span class="ttl-unit">streams</span>
    </div>
    <div class="ttl-row">
      <span class="ttl-label">Probe timeout</span>
      <input id="probing-timeout" class="ttl-input" type="number" min="1" placeholder="5000" />
      <span class="ttl-unit">ms</span>
    </div>
    <div style="margin-top:0.75rem;">
      <button class="btn-save" onclick="saveProbingConfig()">Save</button>
    </div>
    <p id="probing-status"></p>
  </div>

  <div class="install-bar">
    <span class="install-url" id="manifest-url"></span>
    <a class="btn-install" id="install-link" href="#">Install in Stremio</a>
  </div>
</div>

<script>
  const BASE      = '` + baseURL + `';
  const API       = BASE + '/api/addons';
  const META_API  = BASE + '/api/meta-addons';

  document.getElementById('manifest-url').textContent = BASE + '/manifest.json';
  document.getElementById('install-link').href =
    'stremio://' + BASE.replace(/^https?:\/\//, '') + '/manifest.json';

  // ── Source Addons ──────────────────────────────────────────────────────────

  async function loadAddons() {
    const res = await fetch(API);
    const addons = await res.json();
    const el = document.getElementById('addon-list');
    if (!addons || addons.length === 0) {
      el.innerHTML = '<p class="empty">No source addons configured yet.</p>';
      return;
    }
    el.innerHTML = addons.map(a => ` + "`" + `
      <div class="addon-row">
        <div class="addon-info">
          <div class="addon-name">${esc(a.name)}</div>
          <div class="addon-url">${esc(a.url)}</div>
        </div>
        <span class="priority-badge">priority ${a.priority}</span>
        <span class="priority-badge">${a.timeout_ms || 8000}ms</span>
        <button class="btn-remove" onclick="removeAddon('${esc(a.url)}')">Remove</button>
      </div>` + "`" + `).join('');
  }

  async function addAddon() {
    const url      = document.getElementById('in-url').value.trim();
    const name     = document.getElementById('in-name').value.trim();
    const priority   = parseInt(document.getElementById('in-priority').value) || 0;
    const timeout_ms = parseInt(document.getElementById('in-timeout').value)  || 8000;

    if (!url) { setStatus('URL is required.', false); return; }

    const res = await fetch(API, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url, name, priority, timeout_ms }),
    });

    if (res.ok) {
      setStatus('Addon added.', true);
      document.getElementById('in-url').value = '';
      document.getElementById('in-name').value = '';
      loadAddons();
    } else {
      const msg = await res.text();
      setStatus('Error: ' + msg.trim(), false);
    }
  }

  async function removeAddon(url) {
    if (!confirm('Remove ' + url + '?')) return;
    const res = await fetch(API + '?url=' + encodeURIComponent(url), { method: 'DELETE' });
    if (res.ok) loadAddons();
    else setStatus('Failed to remove addon.', false);
  }

  function setStatus(msg, ok) {
    const el = document.getElementById('status');
    el.textContent = msg;
    el.className = ok ? 'ok' : 'err';
  }

  // ── Meta Addons ────────────────────────────────────────────────────────────

  async function loadMetaAddons() {
    const res = await fetch(META_API);
    const addons = await res.json();
    const el = document.getElementById('meta-addon-list');
    if (!addons || addons.length === 0) {
      el.innerHTML = '<p class="empty">No meta addons configured. Add Cinemeta to enable runtime validation.</p>';
      return;
    }
    el.innerHTML = addons.map((a, i) => ` + "`" + `
      <div class="addon-row">
        <div class="addon-info">
          <div class="addon-name">${esc(a.name)}</div>
          <div class="addon-url">${esc(a.url)}</div>
        </div>
        <span class="priority-badge">order ${i + 1}</span>
        <span class="priority-badge">${a.timeout_ms || 5000}ms</span>
        <button class="btn-remove" onclick="removeMetaAddon('${esc(a.url)}')">Remove</button>
      </div>` + "`" + `).join('');
  }

  async function addMetaAddon() {
    const url  = document.getElementById('meta-in-url').value.trim();
    const name       = document.getElementById('meta-in-name').value.trim();
    const timeout_ms = parseInt(document.getElementById('meta-in-timeout').value) || 5000;

    if (!url) { setMetaStatus('URL is required.', false); return; }

    const res = await fetch(META_API, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url, name, timeout_ms }),
    });

    if (res.ok) {
      setMetaStatus('Meta addon added.', true);
      document.getElementById('meta-in-url').value = '';
      document.getElementById('meta-in-name').value = '';
      loadMetaAddons();
    } else {
      const msg = await res.text();
      setMetaStatus('Error: ' + msg.trim(), false);
    }
  }

  async function removeMetaAddon(url) {
    if (!confirm('Remove ' + url + '?')) return;
    const res = await fetch(META_API + '?url=' + encodeURIComponent(url), { method: 'DELETE' });
    if (res.ok) loadMetaAddons();
    else setMetaStatus('Failed to remove meta addon.', false);
  }

  function setMetaStatus(msg, ok) {
    const el = document.getElementById('meta-status');
    el.textContent = msg;
    el.className = ok ? 'ok' : 'err';
  }

  // ── Cache ──────────────────────────────────────────────────────────────────

  async function loadCacheTTLs() {
    const res = await fetch(BASE + '/api/config/cache');
    if (!res.ok) return;
    const cc = await res.json();
    document.getElementById('ttl-streams').value = cc.stream_list_ttl_seconds || '';
    document.getElementById('ttl-play').value    = cc.play_result_ttl_seconds || '';
    document.getElementById('ttl-meta').value    = cc.meta_ttl_seconds        || '';
  }

  async function saveCacheTTLs() {
    const body = {
      stream_list_ttl_seconds: parseInt(document.getElementById('ttl-streams').value) || 0,
      play_result_ttl_seconds: parseInt(document.getElementById('ttl-play').value)    || 0,
      meta_ttl_seconds:        parseInt(document.getElementById('ttl-meta').value)    || 0,
    };
    const res = await fetch(BASE + '/api/config/cache', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (res.ok) {
      setCacheStatus('TTLs saved.', true);
      loadCacheTTLs();
    } else {
      const msg = await res.text();
      setCacheStatus('Error: ' + msg.trim(), false);
    }
  }

  async function clearCache(type) {
    const res = await fetch(BASE + '/api/cache?type=' + type, { method: 'DELETE' });
    if (res.ok) setCacheStatus('Cleared ' + type + ' cache.', true);
    else setCacheStatus('Failed to clear ' + type + ' cache.', false);
  }

  function setCacheStatus(msg, ok) {
    const el = document.getElementById('cache-status');
    el.textContent = msg;
    el.className = ok ? 'ok' : 'err';
  }

  // ── Probing ────────────────────────────────────────────────────────────────

  async function loadProbingConfig() {
    const res = await fetch(BASE + '/api/config/probing');
    if (!res.ok) return;
    const pc = await res.json();
    document.getElementById('probing-enabled').checked    = pc.enabled      || false;
    document.getElementById('probing-max-attempts').value = pc.max_attempts || '';
    document.getElementById('probing-timeout').value      = pc.timeout_ms   || '';
  }

  async function saveProbingConfig() {
    const body = {
      enabled:      document.getElementById('probing-enabled').checked,
      max_attempts: parseInt(document.getElementById('probing-max-attempts').value) || 0,
      timeout_ms:   parseInt(document.getElementById('probing-timeout').value)      || 0,
    };
    const res = await fetch(BASE + '/api/config/probing', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (res.ok) {
      setProbingStatus('Saved.', true);
      loadProbingConfig();
    } else {
      const msg = await res.text();
      setProbingStatus('Error: ' + msg.trim(), false);
    }
  }

  function setProbingStatus(msg, ok) {
    const el = document.getElementById('probing-status');
    el.textContent = msg;
    el.className = ok ? 'ok' : 'err';
  }

  // ── Shared ─────────────────────────────────────────────────────────────────

  function esc(s) {
    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  loadAddons();
  loadMetaAddons();
  loadCacheTTLs();
  loadProbingConfig();
</script>
</body>
</html>`
}
