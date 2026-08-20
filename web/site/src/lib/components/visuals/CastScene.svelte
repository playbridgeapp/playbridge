<div class="scene">
  <div class="beam" aria-hidden="true"></div>

  <div class="tv">
    <div class="tv__bezel">
      <div class="tv__screen">
        <div class="tv__glow"></div>
        <div class="tv__meta">
          <div class="mono tv__room">Living room</div>
          <div class="tv__title">The Long Way Home</div>
          <div class="mono tv__ep">S02 · E04 · 47:21</div>
          <div class="tv__bar" aria-hidden="true"><span></span></div>
        </div>
      </div>
    </div>
  </div>

  <div class="phone" aria-hidden="true">
    <div class="phone__screen">
      <div class="mono phone__time">9:41</div>
      <div class="mono phone__search">long way home</div>
      <div class="phone__row phone__row--live">
        <div class="phone__thumb"></div>
        <div class="phone__lines">
          <div class="phone__line"></div>
          <div class="phone__line phone__line--short"></div>
        </div>
        <div class="phone__dot"></div>
      </div>
      <div class="phone__row">
        <div class="phone__thumb"></div>
        <div class="phone__lines">
          <div class="phone__line"></div>
          <div class="phone__line phone__line--short"></div>
        </div>
      </div>
      <div class="phone__row">
        <div class="phone__thumb"></div>
        <div class="phone__lines">
          <div class="phone__line"></div>
          <div class="phone__line phone__line--short"></div>
        </div>
      </div>
      <div class="mono phone__cast">Casting</div>
    </div>
  </div>
</div>

<style>
  .scene {
    --device: #0a0c10;
    --device-screen: #07090e;
    --device-line: rgba(200, 220, 255, 0.12);
    --device-text: #e8eef6;
    --device-dim: #94afd0;
    --device-faint: #8aa0b8;
    --device-accent: #4a90e2;
    position: relative;
    display: flex;
    align-items: flex-end;
    gap: clamp(12px, 3vw, 28px);
    width: 100%;
  }

  .beam {
    position: absolute;
    left: 16%;
    right: 18%;
    top: 28%;
    bottom: 22%;
    background: linear-gradient(
      108deg,
      color-mix(in oklab, var(--device-accent) 28%, transparent),
      transparent 62%
    );
    clip-path: polygon(0 78%, 100% 8%, 100% 28%, 0 100%);
    opacity: 0.22;
    pointer-events: none;
    animation: beam 4.8s ease-in-out infinite;
  }

  .tv {
    position: relative;
    flex: 1 1 auto;
    min-width: 0;
  }
  .tv__bezel {
    border-radius: 14px;
    background: var(--device);
    border: 1px solid #2a3140;
    padding: 10px;
    box-shadow: 0 24px 60px -36px rgba(0, 0, 0, 0.8);
  }
  .tv__screen {
    position: relative;
    aspect-ratio: 16 / 9;
    border-radius: 4px;
    overflow: hidden;
    background:
      linear-gradient(180deg, rgba(8, 16, 36, 0.2), #05070c 78%),
      radial-gradient(80% 70% at 70% 20%, color-mix(in oklab, var(--device-accent) 28%, transparent), transparent 55%),
      var(--device-screen);
  }
  .tv__glow {
    position: absolute;
    inset: -20%;
    background: radial-gradient(
      45% 40% at 62% 38%,
      color-mix(in oklab, var(--device-accent) 28%, transparent),
      transparent 70%
    );
    animation: screen 10s ease-in-out infinite alternate;
    pointer-events: none;
  }
  .tv__meta {
    position: absolute;
    left: 20px;
    right: 20px;
    bottom: 18px;
    z-index: 1;
  }
  .tv__room {
    font-size: 10px;
    letter-spacing: 0.14em;
    text-transform: uppercase;
    color: var(--device-faint);
    margin-bottom: 6px;
  }
  .tv__title {
    font-family: var(--font-display);
    font-size: clamp(16px, 2vw, 24px);
    font-weight: 600;
    letter-spacing: -0.02em;
    color: var(--device-text);
  }
  .tv__ep {
    margin-top: 6px;
    font-size: 11px;
    color: var(--device-dim);
  }
  .tv__bar {
    margin-top: 12px;
    height: 2px;
    background: var(--device-line);
  }
  .tv__bar span {
    display: block;
    width: 38%;
    height: 100%;
    background: var(--device-accent);
    box-shadow: 0 0 10px color-mix(in oklab, var(--device-accent) 50%, transparent);
    animation: scrub 14s ease-in-out infinite alternate;
  }

  .phone {
    position: relative;
    order: -1;
    flex: 0 0 auto;
    width: min(22%, 148px);
    aspect-ratio: 9 / 18;
    border-radius: 18px;
    background: var(--device);
    border: 1px solid #2a3140;
    padding: 7px;
    box-shadow: 0 18px 40px -24px rgba(0, 0, 0, 0.9);
  }
  .phone__screen {
    position: relative;
    width: 100%;
    height: 100%;
    border-radius: 12px;
    overflow: hidden;
    padding: 12px 10px;
    background: var(--device-screen);
    color: var(--device-text);
  }
  .phone__time {
    font-size: 8px;
    color: var(--device-faint);
    margin-bottom: 10px;
  }
  .phone__search {
    font-size: 8px;
    color: var(--device-dim);
    padding: 5px 7px;
    border: 1px solid var(--device-line);
    border-radius: 6px;
    margin-bottom: 10px;
  }
  .phone__row {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 6px 0;
    border-bottom: 1px solid var(--device-line);
  }
  .phone__row--live {
    background: color-mix(in oklab, var(--device-accent) 12%, transparent);
    margin: 0 -10px;
    padding: 6px 10px;
    border-bottom-color: color-mix(in oklab, var(--device-accent) 28%, transparent);
  }
  .phone__row--live .phone__line {
    background: color-mix(in oklab, var(--device-accent) 45%, transparent);
  }
  .phone__thumb {
    width: 22px;
    height: 16px;
    border-radius: 2px;
    background: rgba(200, 220, 255, 0.08);
    flex: 0 0 auto;
  }
  .phone__row--live .phone__thumb {
    background: color-mix(in oklab, var(--device-accent) 32%, transparent);
  }
  .phone__lines {
    flex: 1;
    min-width: 0;
  }
  .phone__line {
    height: 4px;
    border-radius: 1px;
    background: rgba(200, 220, 255, 0.1);
    margin-bottom: 3px;
  }
  .phone__line--short {
    width: 58%;
    height: 3px;
    background: rgba(200, 220, 255, 0.06);
  }
  .phone__dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--device-accent);
    flex: 0 0 auto;
    box-shadow: 0 0 0 0 color-mix(in oklab, var(--device-accent) 50%, transparent);
    animation: live 1.8s ease-out infinite;
  }
  .phone__cast {
    position: absolute;
    left: 10px;
    right: 10px;
    bottom: 10px;
    font-size: 8px;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    text-align: center;
    color: var(--device-accent);
    padding: 5px 0;
    border: 1px solid color-mix(in oklab, var(--device-accent) 40%, transparent);
    border-radius: 6px;
    animation: cast 2.4s ease-in-out infinite;
  }

  @keyframes scrub {
    from { width: 34%; }
    to { width: 54%; }
  }
  @keyframes screen {
    from { transform: translate3d(0, 0, 0) scale(1); opacity: 0.7; }
    to { transform: translate3d(-3%, 2%, 0) scale(1.06); opacity: 1; }
  }
  @keyframes beam {
    0%, 100% { opacity: 0.14; }
    50% { opacity: 0.3; }
  }
  @keyframes live {
    0% { box-shadow: 0 0 0 0 color-mix(in oklab, var(--device-accent) 45%, transparent); }
    100% { box-shadow: 0 0 0 8px transparent; }
  }
  @keyframes cast {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.62; }
  }

  @media (max-width: 700px) {
    .scene {
      flex-direction: column;
      align-items: stretch;
    }
    .scene { gap: 20px; }
    .phone {
      order: 0;
      width: 96px;
      align-self: center;
      margin-top: 0;
    }
    .phone__row:not(.phone__row--live) { display: none; }
    .beam { display: none; }
    .tv__bezel { padding: 6px; border-radius: 10px; }
    .tv__meta { left: 10px; right: 10px; bottom: 10px; }
    .tv__title { font-size: 13px; }
    .tv__ep, .tv__room { font-size: 9px; }
    .tv__bar { margin-top: 8px; }
  }

  @media (prefers-reduced-motion: reduce) {
    .beam,
    .tv__glow,
    .tv__bar span,
    .phone__dot,
    .phone__cast {
      animation: none;
    }
    .tv__bar span { width: 38%; }
    .beam { opacity: 0.18; }
  }
</style>
