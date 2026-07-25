<script lang="ts">
  import Icon from '$lib/icons/Icon.svelte';
  import { SENDERS, RECEIVERS } from '$lib/data/site';
</script>

<section class="section wrap" id="platforms">
  <div class="section-head">
    <span class="eyebrow">Platforms</span>
    <h2>Senders and receivers.</h2>
    <p>
      Senders push content. Receivers play it. Desktop and the CLI do both — install guides are split
      by role so the steps stay clear.
    </p>
  </div>

  <div class="platforms__group">
    <div class="platforms__label">
      <span class="eyebrow">Senders</span>
      <p>Browse and pick on these — they push content to a receiver.</p>
    </div>
    <div class="platforms">
      {#each SENDERS as c}
        <div class="platform">
          <div class="platform__icon"><Icon name={c.icon} /></div>
          <div class="platform__body">
            <h3>{c.name}</h3>
            <p>{c.desc}</p>
            <a class="platform__link" href={c.href}>Install <Icon name="arrow" size={12} /></a>
          </div>
        </div>
      {/each}
    </div>
    <div class="platforms__more">
      <a class="btn" href="/senders">All senders <Icon name="arrow" size={12} /></a>
    </div>
  </div>

  <div class="platforms__group">
    <div class="platforms__label">
      <span class="eyebrow">Receivers</span>
      <p>Install one of these on the device you actually watch on.</p>
    </div>
    <div class="platforms">
      {#each RECEIVERS as c}
        <div class="platform">
          <div class="platform__icon"><Icon name={c.icon} /></div>
          <div class="platform__body">
            <h3>{c.name}</h3>
            <p>{c.desc}</p>
            <a class="platform__link" href={c.href}>Install <Icon name="arrow" size={12} /></a>
          </div>
        </div>
      {/each}
    </div>
    <div class="platforms__more">
      <a class="btn btn--primary" href="/receivers">All receivers <Icon name="arrow" size={12} /></a>
    </div>
  </div>
</section>

<style>
  .platforms__more {
    margin-top: 20px;
  }
</style>
