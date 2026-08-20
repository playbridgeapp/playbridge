<script lang="ts">
  import Frame from './Frame.svelte';
  const rows: Array<[string, string, boolean]> = [
    ['Watchlist', '12 titles', true],
    ['Continue watching', '3 in progress', false],
    ['Collection · Weekend', '8 titles', false],
    ['On this phone', 'Local files', false]
  ];
</script>

<Frame w={360}>
  <div class="body">
    <div class="mono eyebrow">Library</div>
    {#each rows as [name, meta, active]}
      <div class="row">
        <div class="bar" class:bar--active={active}></div>
        <div class="copy">
          <div class="name" class:name--active={active}>{name}</div>
          <div class="mono tag">{meta}</div>
        </div>
      </div>
    {/each}
  </div>
</Frame>

<style>
  .body { padding: 20px; }
  .eyebrow { font-size: 10px; letter-spacing: 0.14em; text-transform: uppercase; color: var(--text-faint); margin-bottom: 14px; }
  .row { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-top: 1px solid var(--line); }
  .bar { width: 2px; height: 28px; background: var(--line-strong); }
  .bar--active { background: var(--accent); }
  .copy { flex: 1; }
  .name { font-size: 13px; color: var(--text-dim); }
  .name--active { color: var(--text); font-weight: 600; }
  .tag { font-size: 10px; color: var(--text-faint); margin-top: 3px; letter-spacing: 0.1em; }
</style>
