<script lang="ts">
  import Frame from './Frame.svelte';
</script>

<Frame w={320}>
  <div class="body">
    <div class="mono eyebrow">Pair this sender</div>
    <div class="title">Enter this code</div>
    <div class="mono code">4 8 2 1 5 9</div>
    <p class="hint">Shown on the receiver. Confirm it on the phone to encrypt the link.</p>
    <div class="mono meta">wss · local network</div>
  </div>
</Frame>

<style>
  .body { padding: 22px; }
  .eyebrow { font-size: 10px; letter-spacing: 0.14em; text-transform: uppercase; color: var(--text-faint); margin-bottom: 6px; }
  .title { font-size: 16px; font-weight: 600; margin-bottom: 18px; }
  .code {
    font-size: 22px;
    letter-spacing: 0.18em;
    color: var(--text);
    padding: 14px 0;
    border-top: 1px solid var(--line);
    border-bottom: 1px solid var(--line);
  }
  .hint {
    margin: 16px 0 12px;
    font-size: 13px;
    line-height: 1.45;
    color: var(--text-dim);
  }
  .meta { font-size: 10px; letter-spacing: 0.12em; text-transform: uppercase; color: var(--text-faint); }
</style>
