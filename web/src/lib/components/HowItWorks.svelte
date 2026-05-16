<script lang="ts">
  import { STEPS } from '$lib/data/site';
</script>

<section class="section wrap" id="how">
  <div class="section-head">
    <span class="eyebrow">How it works</span>
    <h2>Three taps to a bigger screen.</h2>
  </div>
  <div class="steps">
    {#each STEPS as [n, t]}
      <div class="step">
        <div class="step__num">{n}</div>
        <h3>{t}</h3>
      </div>
    {/each}
  </div>
</section>
