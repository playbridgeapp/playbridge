<script lang="ts">
  import Frame from './Frame.svelte';
  const widths = [100, 90, 75];
</script>

<Frame w={400}>
  <div>
    <div class="bar">
      <div class="dots">
        {#each [1, 2, 3] as _}<div class="dot"></div>{/each}
      </div>
      <div class="mono url">example.com/watch</div>
      <div class="mono blocked">347 BLOCKED</div>
    </div>
    <div class="body">
      <div class="title">Article title</div>
      {#each widths as w}
        <div class="line" style:width="{w}%"></div>
      {/each}
      <div class="ad">
        <span class="mono ad__label">AD · BLOCKED</span>
      </div>
    </div>
  </div>
</Frame>

<style>
  .bar { padding: 10px 14px; border-bottom: 1px solid var(--line); display: flex; align-items: center; gap: 10px; }
  .dots { display: flex; gap: 4px; }
  .dot { width: 6px; height: 6px; border-radius: 50%; background: var(--line-strong); }
  .url { flex: 1; font-size: 11px; color: var(--text-faint); padding: 4px 0; }
  .blocked { font-size: 9px; color: var(--accent); letter-spacing: 0.1em; }
  .body { padding: 20px; min-height: 180px; }
  .title { font-size: 16px; font-weight: 500; margin-bottom: 12px; }
  .line { height: 6px; background: rgba(231, 226, 255, 0.06); border-radius: 1px; margin-bottom: 6px; }
  .ad {
    margin-top: 20px;
    padding: 18px;
    border: 1px dashed var(--line-strong);
    border-radius: 4px;
    text-align: center;
  }
  .ad__label { font-size: 10px; color: var(--text-faint); letter-spacing: 0.16em; }
</style>
