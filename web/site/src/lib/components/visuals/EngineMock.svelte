<script lang="ts">
  import Frame from './Frame.svelte';
  const rows: Array<[string, string]> = [
    ['Android TV / Fire TV', 'ExoPlayer · MPV'],
    ['Apple TV', 'AVPlayer · VLC · MPV'],
    ['Desktop', 'MPV'],
    ['DLNA / UPnP', 'The TV’s own player']
  ];
</script>

<Frame w={340}>
  <div class="body">
    <div class="mono eyebrow">Where it plays</div>
    <div class="title">Any screen on the LAN</div>
    {#each rows as [name, engines]}
      <div class="row">
        <div class="name">{name}</div>
        <div class="mono sub">{engines}</div>
      </div>
    {/each}
  </div>
</Frame>

<style>
  .body { padding: 20px; }
  .eyebrow { font-size: 10px; letter-spacing: 0.14em; text-transform: uppercase; color: var(--text-faint); margin-bottom: 4px; }
  .title { font-size: 16px; font-weight: 600; margin-bottom: 16px; }
  .row {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
    gap: 12px;
    padding: 11px 0;
    border-top: 1px solid var(--line);
  }
  .name { font-size: 13px; color: var(--text); }
  .sub { font-size: 10px; letter-spacing: 0.06em; color: var(--text-faint); text-align: right; }
</style>
