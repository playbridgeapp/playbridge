<script lang="ts">
  import Frame from './Frame.svelte';
  const episodes = ['Cold Open', 'The Long Way Home', 'Resolution', 'Static', 'Heavy Rain'];
</script>

<Frame w={360}>
  <div class="body">
    <div class="head">
      <div>
        <div class="mono eyebrow">SEASON 02 QUEUE</div>
        <div class="title">The Long Way Home</div>
      </div>
      <div class="mono count">8 / 10</div>
    </div>
    {#each episodes as ep, i}
      <div class="row" class:row--watched={i === 0}>
        <span class="mono code">E0{i + 3}</span>
        <div class="name" class:name--now={i === 1}>{ep}</div>
        <span class="mono status" class:status--now={i === 1}>
          {i === 0 ? 'WATCHED' : i === 1 ? 'NOW' : 'READY'}
        </span>
      </div>
    {/each}
  </div>
</Frame>

<style>
  .body { padding: 20px; }
  .head { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 16px; }
  .eyebrow { font-size: 10px; letter-spacing: 0.14em; color: var(--text-faint); }
  .title { font-size: 15px; font-weight: 500; margin-top: 2px; }
  .count { font-size: 11px; color: var(--accent); }
  .row {
    display: flex; align-items: center; gap: 12px;
    padding: 10px 0;
    border-top: 1px solid var(--line);
  }
  .row--watched { opacity: 0.5; }
  .code { font-size: 10px; color: var(--text-faint); width: 26px; }
  .name { flex: 1; font-size: 13px; color: var(--text-dim); }
  .name--now { color: var(--text); font-weight: 500; }
  .status { font-size: 10px; color: var(--text-faint); }
  .status--now { color: var(--accent); }
</style>
