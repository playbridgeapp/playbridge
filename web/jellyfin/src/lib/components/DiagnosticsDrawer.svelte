<script lang="ts">
  import { isDiagnosticsOpen, clearAppCache } from '../stores/appState';
  import {
    bridgeStatus,
    diagnosticLogs,
    activeCastPayload,
    activeLinkedSession
  } from '../cast/playbridge';
  import {
    X,
    Activity,
    Trash2,
    Copy,
    Check,
    Radio,
    Terminal,
    Code,
    Sparkles,
    Database,
    RotateCcw
  } from 'lucide-svelte';

  let copied = false;

  function close() {
    $isDiagnosticsOpen = false;
  }

  function clearLogs() {
    $diagnosticLogs = [];
  }

  function copyDiagnostics() {
    const report = {
      bridgeStatus: $bridgeStatus,
      activeLinkedSession: $activeLinkedSession ? $activeLinkedSession.sessionId : null,
      lastPayload: $activeCastPayload,
      logs: $diagnosticLogs
    };
    navigator.clipboard.writeText(JSON.stringify(report, null, 2));
    copied = true;
    setTimeout(() => (copied = false), 2000);
  }
</script>

{#if $isDiagnosticsOpen}
  <div class="drawer-backdrop" on:click={close}>
    <div class="drawer-panel" on:click|stopPropagation>
      <!-- Header -->
      <div class="drawer-header">
        <div class="header-title-row">
          <Activity size={18} class="accent-icon" />
          <h3>PlayBridge Cast Inspector</h3>
        </div>
        <div class="header-actions">
          <button class="icon-btn" on:click={copyDiagnostics} title="Copy Diagnostic JSON">
            {#if copied}
              <Check size={16} class="copied-icon" />
            {:else}
              <Copy size={16} />
            {/if}
          </button>
          <button class="icon-btn" on:click={clearLogs} title="Clear Log Stream">
            <Trash2 size={16} />
          </button>
          <button class="icon-btn" on:click={close} title="Close Inspector">
            <X size={18} />
          </button>
        </div>
      </div>

      <!-- Bridge Status Card -->
      <div class="status-summary">
        <div class="status-indicator-box">
          <div class="status-dot-row">
            {#if $bridgeStatus.available}
              <span class="pulsing-dot"></span>
              <span class="status-title">Bridge Online</span>
            {:else}
              <Radio size={14} />
              <span class="status-title status-warn">Bridge Inactive (Web Fallback)</span>
            {/if}
          </div>
          <p class="status-sub">
            {$bridgeStatus.available
              ? 'Native PlayBridge bridge detected in window.playbridge'
              : 'Running in standard browser. Cast requests will output payloads below.'}
          </p>
        </div>

        <div class="cap-grid">
          <div class="cap-pill" class:active={$bridgeStatus.available}>
            <span class="cap-dot"></span>
            <span>Direct Cast</span>
          </div>
          <div class="cap-pill" class:active={$bridgeStatus.linkedCast}>
            <span class="cap-dot"></span>
            <span>Linked Queue Cast</span>
          </div>
        </div>
      </div>

      <!-- Active / Last Cast Payload Inspector -->
      {#if $activeCastPayload}
        <div class="payload-box">
          <div class="payload-header">
            <Code size={14} />
            <span>Latest Dispatched Cast Payload</span>
          </div>
          <pre class="json-code">{JSON.stringify($activeCastPayload, null, 2)}</pre>
        </div>
      {/if}

      <!-- Cache & Storage Management -->
      <div class="cache-box">
        <div class="cache-header">
          <Database size={14} />
          <span>Client Storage & Cache</span>
        </div>
        <p class="cache-desc">IndexedDB and in-memory SWR cache for 0ms instant offline loads.</p>
        <div class="cache-actions">
          <button class="btn-secondary cache-btn" on:click={() => clearAppCache(false)}>
            <RotateCcw size={14} />
            <span>Clear SWR Cache & Re-sync</span>
          </button>
          <button class="btn-danger-outline cache-btn" on:click={() => clearAppCache(true)}>
            <Trash2 size={14} />
            <span>Factory Reset App Data</span>
          </button>
        </div>
      </div>

      <!-- Event Stream Log -->
      <div class="stream-container">
        <div class="stream-header">
          <Terminal size={14} />
          <span>Real-time Event Log</span>
          <span class="log-count">({$diagnosticLogs.length} events)</span>
        </div>

        <div class="stream-logs">
          {#if $diagnosticLogs.length === 0}
            <div class="empty-logs">No events logged yet. Trigger a Cast or Play action.</div>
          {:else}
            {#each $diagnosticLogs as log (log.id)}
              <div class="log-row log-{log.type}">
                <span class="log-time">{log.time}</span>
                <span class="log-type-tag">{log.type.toUpperCase()}</span>
                <span class="log-msg">{log.message}</span>
                {#if log.data}
                  <pre class="log-data-inline">{typeof log.data === 'string' ? log.data : JSON.stringify(log.data)}</pre>
                {/if}
              </div>
            {/each}
          {/if}
        </div>
      </div>
    </div>
  </div>
{/if}

<style>
  .drawer-backdrop {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.6);
    backdrop-filter: blur(4px);
    z-index: 250;
    display: flex;
    justify-content: flex-end;
  }

  .drawer-panel {
    background: var(--bg-surface);
    border-left: 1px solid var(--border);
    width: 100%;
    max-width: 460px;
    height: 100vh;
    display: flex;
    flex-direction: column;
    box-shadow: var(--shadow-lg);
    animation: slideIn 0.25s cubic-bezier(0.16, 1, 0.3, 1);
  }

  @keyframes slideIn {
    from { transform: translateX(100%); }
    to { transform: translateX(0); }
  }

  .drawer-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 18px 20px;
    border-bottom: 1px solid var(--border);
  }

  .header-title-row {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .header-title-row h3 {
    font-size: 0.98rem;
    font-weight: 700;
    color: #fff;
  }

  :global(.accent-icon) {
    color: var(--jf-blue);
  }

  .header-actions {
    display: flex;
    align-items: center;
    gap: 4px;
  }

  .icon-btn {
    color: var(--text-secondary);
    padding: 6px;
    border-radius: var(--radius-sm);
  }

  .icon-btn:hover {
    color: #fff;
    background: rgba(255, 255, 255, 0.08);
  }

  :global(.copied-icon) {
    color: var(--status-ok);
  }

  .status-summary {
    padding: 16px 20px;
    border-bottom: 1px solid var(--border);
    background: var(--bg-surface-elevated);
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .status-dot-row {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;
    font-size: 0.9rem;
    color: var(--status-ok);
  }

  .status-warn {
    color: var(--status-warn);
  }

  .status-sub {
    font-size: 0.78rem;
    color: var(--text-muted);
    margin-top: 4px;
    line-height: 1.4;
  }

  .cap-grid {
    display: flex;
    gap: 8px;
  }

  .cap-pill {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 0.75rem;
    padding: 4px 10px;
    border-radius: var(--radius-full);
    background: rgba(255, 255, 255, 0.05);
    border: 1px solid var(--border);
    color: var(--text-muted);
  }

  .cap-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--text-muted);
  }

  .cap-pill.active {
    background: rgba(0, 164, 220, 0.12);
    border-color: rgba(0, 164, 220, 0.3);
    color: var(--jf-blue);
  }

  .cap-pill.active .cap-dot {
    background: var(--jf-blue);
  }

  .payload-box {
    padding: 12px 20px;
    border-bottom: 1px solid var(--border);
    background: #06070a;
  }

  .payload-header {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 0.78rem;
    font-weight: 600;
    color: var(--jf-indigo);
    margin-bottom: 6px;
  }

  .json-code {
    font-family: var(--font-mono);
    font-size: 0.72rem;
    color: var(--text-secondary);
    max-height: 140px;
    overflow-y: auto;
    white-space: pre-wrap;
    word-break: break-all;
    background: #0d1017;
    padding: 8px 10px;
    border-radius: var(--radius-sm);
    border: 1px solid var(--border-subtle);
  }

  .cache-box {
    padding: 12px 20px;
    background: rgba(255, 255, 255, 0.02);
    border-bottom: 1px solid var(--border);
  }

  .cache-header {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 0.78rem;
    font-weight: 600;
    color: var(--jf-blue);
    margin-bottom: 4px;
  }

  .cache-desc {
    font-size: 0.74rem;
    color: var(--text-muted);
    margin-bottom: 10px;
  }

  .cache-actions {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }

  .cache-btn {
    padding: 6px 12px;
    font-size: 0.75rem;
    display: flex;
    align-items: center;
    gap: 6px;
    border-radius: var(--radius-sm);
  }

  .btn-danger-outline {
    background: transparent;
    border: 1px solid rgba(235, 87, 87, 0.4);
    color: var(--status-error);
  }

  .btn-danger-outline:hover {
    background: rgba(235, 87, 87, 0.12);
  }

  .stream-container {
    flex: 1;
    display: flex;
    flex-direction: column;
    min-height: 0;
  }

  .stream-header {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 12px 20px;
    font-size: 0.82rem;
    font-weight: 600;
    color: var(--text-secondary);
    border-bottom: 1px solid var(--border-subtle);
  }

  .log-count {
    font-size: 0.75rem;
    color: var(--text-muted);
    font-weight: normal;
  }

  .stream-logs {
    flex: 1;
    overflow-y: auto;
    padding: 12px 20px;
    font-family: var(--font-mono);
    font-size: 0.74rem;
    display: flex;
    flex-direction: column;
    gap: 8px;
    background: #08090d;
  }

  .empty-logs {
    color: var(--text-muted);
    font-style: italic;
    text-align: center;
    padding: 30px 0;
  }

  .log-row {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 6px 8px;
    border-radius: var(--radius-sm);
    background: var(--bg-surface);
    border-left: 3px solid var(--border);
  }

  .log-row.log-success { border-left-color: var(--status-ok); }
  .log-row.log-cast { border-left-color: var(--jf-blue); }
  .log-row.log-feedback { border-left-color: var(--jf-purple); }
  .log-row.log-warn { border-left-color: var(--status-warn); }
  .log-row.log-error { border-left-color: var(--status-error); }

  .log-time {
    color: var(--text-muted);
    font-size: 0.68rem;
  }

  .log-type-tag {
    font-size: 0.66rem;
    font-weight: 700;
    color: var(--text-secondary);
  }

  .log-msg {
    color: var(--text-primary);
  }

  .log-data-inline {
    font-size: 0.68rem;
    color: var(--text-muted);
    background: rgba(0, 0, 0, 0.3);
    padding: 4px 6px;
    border-radius: 3px;
    margin-top: 4px;
    white-space: pre-wrap;
    word-break: break-all;
  }
</style>
