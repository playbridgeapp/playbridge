<div class="scene">
  <div class="tv">
    <div class="tv__screen">
      <div class="tv__caption">
        <div class="mono caption">S02 · E04 · 47:21</div>
        <div class="title">The Long Way Home</div>
        <div class="bar"><div class="bar__fill"></div></div>
      </div>
    </div>
  </div>

  <div class="phone">
    <div class="phone__screen">
      <div class="mono time">9:41</div>
      <div class="mono search">⌕ long way home</div>
      {#each [1, 2, 3] as i}
        <div class="row" class:last={i === 3}>
          <div class="thumb"></div>
          <div class="lines">
            <div class="line"></div>
            <div class="line line--short"></div>
          </div>
          {#if i === 1}<div class="dot"></div>{/if}
        </div>
      {/each}
      <div class="mono casting">▶ CASTING</div>
    </div>
  </div>

  <svg class="beam" viewBox="0 0 500 220" preserveAspectRatio="none" aria-hidden="true">
    <path d="M 90 180 Q 250 80, 400 60" stroke="var(--accent)" stroke-width="1" fill="none" stroke-dasharray="3 4" opacity="0.5" />
  </svg>
</div>

<style>
  .scene { position: relative; width: 100%; aspect-ratio: 16 / 7; }
  .tv {
    position: absolute; right: 0; top: 0;
    width: 58%; aspect-ratio: 16 / 10;
    border-radius: 10px;
    background: var(--surface);
    border: 1px solid var(--line);
    padding: 10px;
  }
  .tv__screen { width: 100%; height: 100%; border-radius: 4px; background: rgba(231, 226, 255, 0.03); position: relative; }
  .tv__caption { position: absolute; left: 16px; bottom: 14px; right: 16px; }
  .caption { font-size: 9px; letter-spacing: 0.15em; color: var(--text-faint); margin-bottom: 4px; }
  .title { font-size: 13px; font-weight: 500; color: var(--text); }
  .bar { height: 1px; background: var(--line-strong); margin-top: 10px; position: relative; }
  .bar__fill { position: absolute; left: 0; top: -0.5px; width: 38%; height: 2px; background: var(--accent); }

  .phone {
    position: absolute; left: 0; bottom: 0;
    width: 20%; aspect-ratio: 9 / 19;
    border-radius: 12px;
    background: var(--surface);
    border: 1px solid var(--line);
    padding: 6px;
  }
  .phone__screen { width: 100%; height: 100%; border-radius: 8px; background: rgba(231, 226, 255, 0.03); padding: 10px 8px; position: relative; overflow: hidden; }
  .time { font-size: 7px; color: var(--text-faint); margin-bottom: 8px; }
  .search { font-size: 7px; color: var(--text-dim); padding: 4px 6px; background: rgba(231, 226, 255, 0.04); border-radius: 3px; margin-bottom: 8px; }
  .row { display: flex; gap: 5px; padding: 5px 0; border-bottom: 1px solid var(--line); }
  .row.last { border-bottom: none; }
  .thumb { width: 22px; height: 16px; background: rgba(231, 226, 255, 0.04); border-radius: 2px; }
  .lines { flex: 1; }
  .line { height: 4px; background: rgba(231, 226, 255, 0.08); border-radius: 1px; margin-bottom: 3px; }
  .line--short { height: 3px; width: 60%; background: rgba(231, 226, 255, 0.05); }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--accent); align-self: center; }
  .casting {
    position: absolute; left: 8px; right: 8px; bottom: 10px;
    font-size: 6px; color: var(--accent);
    text-align: center; padding: 4px;
    border: 1px solid rgba(158, 167, 255, 0.3);
    border-radius: 3px;
    letter-spacing: 0.1em;
  }

  .beam { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; }
</style>
