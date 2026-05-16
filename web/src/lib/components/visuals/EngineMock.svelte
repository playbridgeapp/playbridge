<script lang="ts">
  import Frame from './Frame.svelte';
  const engines: Array<[string, string, boolean]> = [
    ['ExoPlayer', 'Native · HLS', true],
    ['MPV', 'Hardware accel', false],
    ['VLC', 'Fallback', false],
    ['AVPlayer', 'tvOS native', false]
  ];
</script>

<Frame w={340}>
  <div class="body">
    <div class="mono eyebrow">PLAYBACK ENGINE</div>
    <div class="title">ExoPlayer</div>
    {#each engines as [name, sub, active]}
      <div class="row">
        <div class="name" class:name--active={active}>{name}</div>
        <div class="mono sub" class:sub--active={active}>{sub}</div>
      </div>
    {/each}
    <div class="meta">
      <div class="meta__k">QUALITY</div><div class="meta__v">2160p · HDR10</div>
      <div class="meta__k">AUDIO</div><div class="meta__v">5.1 · TrueHD</div>
      <div class="meta__k">SUBS</div><div class="meta__v">en · forced</div>
    </div>
  </div>
</Frame>

<style>
  .body { padding: 20px; }
  .eyebrow { font-size: 10px; letter-spacing: 0.14em; color: var(--text-faint); margin-bottom: 4px; }
  .title { font-size: 18px; font-weight: 500; margin-bottom: 18px; }
  .row {
    display: flex; justify-content: space-between; align-items: center;
    padding: 11px 0; border-top: 1px solid var(--line);
  }
  .name { font-size: 13px; color: var(--text-dim); }
  .name--active { color: var(--text); }
  .sub { font-size: 10px; color: var(--text-faint); letter-spacing: 0.1em; }
  .sub--active { color: var(--accent); }
  .meta {
    margin-top: 20px; padding-top: 16px;
    border-top: 1px solid var(--line);
    display: grid; grid-template-columns: auto auto;
    gap: 6px 16px;
    font-family: 'JetBrains Mono', monospace;
    font-size: 11px;
  }
  .meta__k { color: var(--text-faint); }
  .meta__v { color: var(--text); }
</style>
