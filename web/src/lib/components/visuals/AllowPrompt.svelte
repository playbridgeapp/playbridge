<script lang="ts">
  import Frame from './Frame.svelte';
  import Icon from '$lib/icons/Icon.svelte';
</script>

<Frame w={320}>
  <div class="body">
    <div class="mono eyebrow">INCOMING CONNECTION</div>
    <div class="title">Allow this device?</div>

    <div class="row">
      <div class="badge"><Icon name="phone" size={14} /></div>
      <div class="info">
        <div class="name">Pixel 8</div>
        <div class="mono meta">192.168.1.27 · LAN</div>
      </div>
    </div>

    <div class="mono fp">
      Fingerprint<br />
      <span class="fp__val">4f:a1:c2:b9:7e:08</span>
    </div>

    <div class="actions">
      <button class="btn-reject">Reject</button>
      <button class="btn-allow">Allow</button>
    </div>
  </div>
</Frame>

<style>
  .body { padding: 22px; }
  .eyebrow { font-size: 10px; letter-spacing: 0.14em; color: var(--text-faint); margin-bottom: 4px; }
  .title { font-size: 15px; font-weight: 500; margin-bottom: 18px; }
  .row {
    display: flex; align-items: center; gap: 12px;
    padding: 12px 0;
    border-top: 1px solid var(--line);
    border-bottom: 1px solid var(--line);
  }
  .badge {
    width: 32px; height: 32px;
    border-radius: 4px;
    border: 1px solid var(--line-strong);
    display: grid; place-items: center;
    color: var(--accent);
  }
  .info { flex: 1; }
  .name { font-size: 13px; color: var(--text); }
  .meta { font-size: 10px; color: var(--text-faint); margin-top: 2px; letter-spacing: 0.1em; }
  .fp { font-size: 11px; color: var(--text-dim); margin: 14px 0 16px; line-height: 1.6; }
  .fp__val { color: var(--text); }
  .actions { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
  .btn-reject, .btn-allow {
    padding: 10px;
    border-radius: 4px;
    font-size: 12px;
    font-family: inherit;
    font-weight: 500;
    cursor: pointer;
  }
  .btn-reject {
    border: 1px solid var(--line-strong);
    background: transparent;
    color: var(--text-dim);
  }
  .btn-allow {
    border: 0;
    background: var(--accent);
    color: var(--bg);
  }
</style>
