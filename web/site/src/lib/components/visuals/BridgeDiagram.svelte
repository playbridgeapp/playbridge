<script lang="ts">
  import Icon from '$lib/icons/Icon.svelte';
</script>

<div class="bridge" role="img" aria-label="Your phone sends over the same Wi-Fi network to your TV.">
  <div class="bridge__rail">
    <div class="bridge__icon"><Icon name="phone" size={24} /></div>
    <div class="bridge__wire">
      <span class="bridge__pulse"></span>
      <span class="bridge__wifi mono">Same Wi-Fi</span>
    </div>
    <div class="bridge__icon bridge__icon--screen"><Icon name="tv" size={24} /></div>
  </div>
  <div class="bridge__captions">
    <div class="bridge__cap">
      <span class="bridge__role">Sender</span>
      <span class="bridge__device">Your phone</span>
    </div>
    <div class="bridge__cap bridge__cap--right">
      <span class="bridge__role">Player</span>
      <span class="bridge__device">Your TV</span>
    </div>
  </div>
</div>

<style>
  .bridge { max-width: 520px; margin: 0 auto; }

  .bridge__rail {
    display: flex;
    align-items: center;
    gap: 16px;
  }

  .bridge__icon {
    flex: 0 0 auto;
    width: 56px;
    height: 56px;
    display: grid;
    place-items: center;
    color: var(--accent);
    border: 1px solid var(--line-strong);
    border-radius: 14px;
    background: linear-gradient(180deg, rgba(74, 144, 226, 0.12), rgba(74, 144, 226, 0.02));
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.06),
      0 10px 28px -18px color-mix(in oklab, var(--accent) 80%, transparent);
  }
  .bridge__icon--screen { width: 72px; }

  .bridge__wire {
    flex: 1;
    position: relative;
    height: 0;
    border-top: 1.5px dashed var(--line-strong);
  }

  .bridge__pulse {
    position: absolute;
    top: 50%;
    left: 0;
    width: 8px;
    height: 8px;
    margin-top: -4px;
    border-radius: 50%;
    background: var(--accent);
    box-shadow: 0 0 12px 2px color-mix(in oklab, var(--accent) 70%, transparent);
    animation: bridgeFlow 2.6s ease-in-out infinite;
  }

  @keyframes bridgeFlow {
    0%   { left: 0;    opacity: 0; }
    12%  { opacity: 1; }
    88%  { opacity: 1; }
    100% { left: 100%; opacity: 0; }
  }

  .bridge__wifi {
    position: absolute;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    font-size: 9px;
    letter-spacing: 0.16em;
    text-transform: uppercase;
    color: var(--text-faint);
    background: var(--bg);
    padding: 4px 10px;
    border: 1px solid var(--line);
    border-radius: 99px;
    white-space: nowrap;
  }

  .bridge__captions {
    display: flex;
    justify-content: space-between;
    margin-top: 14px;
    gap: 16px;
  }
  .bridge__cap { display: flex; flex-direction: column; gap: 2px; }
  .bridge__cap--right { text-align: right; }
  .bridge__role {
    font-family: 'JetBrains Mono', monospace;
    font-size: 10px;
    letter-spacing: 0.14em;
    text-transform: uppercase;
    color: var(--accent);
  }
  .bridge__device { font-size: 13px; color: var(--text-dim); }

  @media (max-width: 480px) {
    .bridge__icon { width: 48px; height: 48px; }
    .bridge__icon--screen { width: 60px; }
  }

  @media (prefers-reduced-motion: reduce) {
    .bridge__pulse { animation: none; left: 50%; opacity: 1; }
  }
</style>
