<script lang="ts">
  import { FAQ as FAQ_ITEMS } from '$lib/data/site';

  let open = $state(0);
</script>

<section class="section wrap" id="faq">
  <div class="section-head">
    <span class="eyebrow">FAQ</span>
    <h2>The fine print, plainly.</h2>
  </div>
  <div class="faq">
    {#each FAQ_ITEMS as [q, a], i}
      <div class="faq__item" class:faq__item--open={open === i}>
        <button
          type="button"
          class="faq__btn"
          onclick={() => (open = open === i ? -1 : i)}
          aria-expanded={open === i}
        >
          <span>{q}</span>
          <span class="faq__chev">+</span>
        </button>
        <div class="faq__panel"><p>{a}</p></div>
      </div>
    {/each}
  </div>
</section>
