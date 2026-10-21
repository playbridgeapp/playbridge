<!--
  Hero device scene: real PlayBridge captures in the fixed dark device kit.
  Phone left, TV right on desktop; TV above a centred phone on small screens.
  One loop: cast beam + TV scrub + “Casting” pulse. Reduced motion shows the end state.
-->
<script lang="ts">
  import Device from './Device.svelte';
  import { SHOTS } from './screens';
</script>

<figure class="scene">
  <div class="beam" aria-hidden="true"></div>

  <Device
    kind="tv"
    class="scene__tv"
    shot={SHOTS.heroTv}
    priority
    sizes="(min-width: 1024px) min(560px, 40vw), (min-width: 701px) calc(100vw - 240px), calc(100vw - 52px)"
  >
    <div class="cast" aria-hidden="true">
      <span class="cast__dot"></span>
      <span class="cast__label">Casting</span>
    </div>
    <div class="scrub" aria-hidden="true"><span></span></div>
  </Device>

  <Device
    kind="phone"
    class="scene__phone"
    shot={SHOTS.heroPhone}
    eager
    sizes="(min-width: 701px) 148px, 132px"
  />

  <figcaption class="scene__credit">Big Buck Bunny © Blender Foundation · CC BY 3.0</figcaption>
</figure>

<style>
  .scene {
    --device-accent: #4a90e2;
    position: relative;
    display: flex;
    flex-wrap: wrap;
    align-items: flex-end;
    gap: clamp(12px, 3vw, 28px);
    width: 100%;
    margin: 0;
  }

  .beam {
    position: absolute;
    left: 14%;
    right: 20%;
    top: 26%;
    bottom: 30%;
    background: linear-gradient(
      108deg,
      color-mix(in oklab, var(--device-accent) 30%, transparent),
      transparent 62%
    );
    clip-path: polygon(0 78%, 100% 8%, 100% 28%, 0 100%);
    opacity: 0.22;
    pointer-events: none;
    animation: beam 4.8s ease-in-out infinite;
  }

  .scene :global(.scene__tv) {
    flex: 1 1 0;
    min-width: 0;
  }
  .scene :global(.scene__phone) {
    order: -1;
    flex: 0 0 auto;
    width: min(22%, 148px);
  }

  /* On-screen overlay: small, dark-backed, high contrast over any capture. */
  .cast {
    position: absolute;
    top: 4.5%;
    left: 3.5%;
    display: inline-flex;
    align-items: center;
    gap: 7px;
    padding: 4px 9px 4px 8px;
    border-radius: 6px;
    background: rgba(6, 9, 14, 0.78);
    border: 1px solid color-mix(in oklab, var(--device-accent) 45%, transparent);
    font-family: var(--font-mono-ui);
    font-size: 10px;
    letter-spacing: 0.14em;
    text-transform: uppercase;
    color: #e8eef6;
    line-height: 1.2;
  }
  .cast__dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--device-accent);
    animation: cast 2.4s ease-in-out infinite;
  }

  .scrub {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    height: 3px;
    background: rgba(232, 238, 246, 0.16);
  }
  .scrub span {
    display: block;
    width: 38%;
    height: 100%;
    background: var(--device-accent);
    animation: scrub 14s ease-in-out infinite alternate;
  }

  .scene__credit {
    flex: 1 0 100%;
    margin-top: -4px;
    font-family: var(--font-mono-ui);
    font-size: 11px;
    letter-spacing: 0.04em;
    color: var(--text-dim);
    text-align: right;
  }

  @keyframes scrub {
    from { width: 34%; }
    to { width: 54%; }
  }
  @keyframes beam {
    0%, 100% { opacity: 0.14; }
    50% { opacity: 0.3; }
  }
  @keyframes cast {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.35; }
  }

  @media (max-width: 700px) {
    .scene {
      flex-direction: column;
      align-items: stretch;
      flex-wrap: nowrap;
      gap: 20px;
    }
    .scene :global(.scene__tv) {
      flex: 0 0 auto;
    }
    .scene :global(.scene__phone) {
      order: 0;
      width: 132px;
      align-self: center;
    }
    .beam { display: none; }
    .cast {
      padding: 3px 7px 3px 6px;
      font-size: 9px;
      gap: 5px;
    }
    .scene__credit {
      text-align: center;
      margin-top: -8px;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .beam,
    .scrub span,
    .cast__dot {
      animation: none;
    }
    .scrub span { width: 54%; }
    .beam { opacity: 0.22; }
  }
</style>
