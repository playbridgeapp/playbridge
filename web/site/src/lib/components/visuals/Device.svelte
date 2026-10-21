<!--
  Real product capture inside the fixed dark device kit (same in both themes).
  `priority` is for the hero TV only (eager + fetchpriority=high); `eager` skips lazy-loading
  without raising priority. Everything else lazy-loads.
-->
<script lang="ts">
  import type { Snippet } from 'svelte';
  import { dims, fallbackSrc, webpSrcset, type Shot } from './screens';

  interface Props {
    shot: Shot;
    kind: 'tv' | 'phone';
    sizes: string;
    priority?: boolean;
    eager?: boolean;
    class?: string;
    children?: Snippet;
  }
  let { shot, kind, sizes, priority = false, eager = false, class: klass = '', children }: Props = $props();
  const { width, height } = $derived(dims(shot));
</script>

<div class="device device--{kind} {klass}">
  <div class="device__screen">
    <picture>
      <source type="image/webp" srcset={webpSrcset(shot)} {sizes} />
      <img
        src={fallbackSrc(shot)}
        {width}
        {height}
        alt={shot.alt}
        decoding="async"
        loading={priority || eager ? 'eager' : 'lazy'}
        fetchpriority={priority ? 'high' : 'auto'}
      />
    </picture>
    {@render children?.()}
  </div>
</div>

<style>
  .device {
    --device: #0a0c10;
    --device-edge: #2a3140;
    --device-screen: #07090e;
    position: relative;
    background: var(--device);
    border: 1px solid var(--device-edge);
    min-width: 0;
  }
  .device--tv {
    border-radius: 12px;
    padding: clamp(5px, 1.1%, 10px);
  }
  .device--phone {
    border-radius: 18px;
    padding: clamp(4px, 4.5%, 7px);
  }
  .device__screen {
    position: relative;
    overflow: hidden;
    background: var(--device-screen);
    /* Missing-media fallback: the alt text shows on a dark screen. */
    color: #94afd0;
    font-size: 12px;
    line-height: 1.4;
  }
  .device--tv .device__screen {
    aspect-ratio: 16 / 9;
    border-radius: 4px;
  }
  .device--phone .device__screen {
    aspect-ratio: 1 / 2;
    border-radius: 12px;
  }
  picture,
  img {
    display: block;
    width: 100%;
    height: 100%;
  }
  img {
    object-fit: cover;
    padding: 0;
  }
</style>
