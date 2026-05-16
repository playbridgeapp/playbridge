<script lang="ts">
  import Frame from './Frame.svelte';
  const sources: Array<[string, string, string, string, boolean]> = [
    ['Real-Debrid', '4K HDR', 'REMUX', '18.2 GB', true],
    ['AllDebrid', '1080p', 'WEB-DL', '4.1 GB', false],
    ['Stremio', '1080p', 'BLURAY', '6.8 GB', false],
    ['Stremio', '720p', 'WEB', '1.4 GB', false]
  ];
</script>

<Frame w={360}>
  <div class="body">
    <div class="mono eyebrow">STREAM SOURCES · 23 FOUND</div>
    {#each sources as [name, q, tag, size, best]}
      <div class="row">
        <div class="bar" class:bar--best={best}></div>
        <div class="copy">
          <div class="name" class:name--best={best}>{name} · <span class="q">{q}</span></div>
          <div class="mono tag">{tag} · {size}</div>
        </div>
        {#if best}<span class="mono best">BEST</span>{/if}
      </div>
    {/each}
  </div>
</Frame>

<style>
  .body { padding: 20px; }
  .eyebrow { font-size: 10px; letter-spacing: 0.14em; color: var(--text-faint); margin-bottom: 14px; }
  .row { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-top: 1px solid var(--line); }
  .bar { width: 2px; height: 28px; background: var(--line-strong); }
  .bar--best { background: var(--accent); }
  .copy { flex: 1; }
  .name { font-size: 13px; color: var(--text); }
  .name--best { font-weight: 500; }
  .q { color: var(--text-dim); }
  .tag { font-size: 10px; color: var(--text-faint); margin-top: 3px; letter-spacing: 0.1em; }
  .best { font-size: 10px; color: var(--accent); letter-spacing: 0.14em; }
</style>
