<script lang="ts">
  import Frame from './Frame.svelte';
  const buttons = ['⏮', '⏸', '⏭', 'CC'];
</script>

<Frame w={240}>
  <div class="body">
    <div class="mono eyebrow">LIVING ROOM</div>
    <div class="title">The Long Way Home</div>
    <div class="pad">
      <div class="ok">OK</div>
      <span class="arrow arrow--up">▲</span>
      <span class="arrow arrow--left">◀</span>
      <span class="arrow arrow--right">▶</span>
      <span class="arrow arrow--down">▼</span>
    </div>
    <div class="grid">
      {#each buttons as c, i}
        <div class="btn" class:btn--active={i === 1} class:mono={c === 'CC'}>{c}</div>
      {/each}
    </div>
    <div class="vol">
      <span>VOL</span>
      <div class="vol__bar"><div class="vol__fill"></div></div>
      <span>62</span>
    </div>
  </div>
</Frame>

<style>
  .body { padding: 18px; }
  .eyebrow { font-size: 10px; color: var(--text-faint); letter-spacing: 0.14em; margin-bottom: 4px; }
  .title { font-size: 14px; font-weight: 500; margin-bottom: 16px; }
  .pad {
    aspect-ratio: 1.1;
    border: 1px solid var(--line);
    border-radius: 6px;
    position: relative;
    margin-bottom: 12px;
  }
  .ok {
    position: absolute; inset: 35%;
    border-radius: 50%;
    border: 1px solid var(--line-strong);
    display: grid; place-items: center;
    font-family: 'JetBrains Mono', monospace;
    font-size: 9px;
    color: var(--text-dim);
    letter-spacing: 0.15em;
  }
  .arrow { position: absolute; font-size: 10px; color: var(--text-faint); }
  .arrow--up    { top: 6px;    left: 50%; transform: translateX(-50%); }
  .arrow--left  { left: 6px;   top: 50%;  transform: translateY(-50%); }
  .arrow--right { right: 6px;  top: 50%;  transform: translateY(-50%); }
  .arrow--down  { bottom: 6px; left: 50%; transform: translateX(-50%); }
  .grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 4px; margin-bottom: 12px; }
  .btn {
    padding: 8px 0;
    border-radius: 4px;
    border: 1px solid var(--line);
    text-align: center;
    font-size: 11px;
    color: var(--text-dim);
  }
  .btn--active { color: var(--accent); }
  .vol {
    display: flex; align-items: center; gap: 10px;
    font-family: 'JetBrains Mono', monospace;
    font-size: 10px; color: var(--text-faint);
  }
  .vol__bar { flex: 1; height: 1px; background: var(--line-strong); position: relative; }
  .vol__fill { position: absolute; left: 0; top: -0.5px; width: 62%; height: 2px; background: var(--accent); }
</style>
