<script lang="ts">
  import type { Snippet } from 'svelte';
  interface Props { w?: number; ratio?: string; children: Snippet; }
  let { w = 320, ratio = 'auto', children }: Props = $props();
</script>

<div class="frame" style:max-width="{w}px" style:aspect-ratio={ratio}>
  {@render children()}
</div>

<style>
  .frame {
    width: 100%;
    border: 1px solid var(--line);
    border-radius: 8px;
    background: var(--surface);
    overflow: hidden;
  }
</style>
