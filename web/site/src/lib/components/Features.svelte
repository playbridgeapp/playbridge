<script lang="ts">
  import { FEATURES } from '$lib/data/site';
  import AllowPrompt from './visuals/AllowPrompt.svelte';
  import RemoteMock from './visuals/RemoteMock.svelte';
  import QueueMock from './visuals/QueueMock.svelte';
  import EngineMock from './visuals/EngineMock.svelte';
  import DebridMock from './visuals/DebridMock.svelte';
  import BrowserMock from './visuals/BrowserMock.svelte';
</script>

<section class="section wrap" id="features">
  <div class="section-head">
    <span class="eyebrow">Features</span>
    <h2>The details that make it ambient.</h2>
  </div>
  <div class="features">
    {#each FEATURES as item}
      <div class="feature">
        <div class="feature__tag">{item.tag}</div>
        <div class="feature__copy">
          <h3>{item.title}</h3>
          <p>{item.desc}</p>
        </div>
        <div class="feature__visual">
          {#if item.visual === 'allow'}<AllowPrompt />
          {:else if item.visual === 'remote'}<RemoteMock />
          {:else if item.visual === 'queue'}<QueueMock />
          {:else if item.visual === 'engine'}<EngineMock />
          {:else if item.visual === 'debrid'}<DebridMock />
          {:else if item.visual === 'browser'}<BrowserMock />
          {/if}
        </div>
      </div>
    {/each}
  </div>
</section>
