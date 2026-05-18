<script lang="ts">
  import Icon from '$lib/icons/Icon.svelte';
  import { SENDERS, PLAYERS } from '$lib/data/site';
</script>

<section class="section wrap" id="platforms">
  <div class="section-head">
    <span class="eyebrow">Platforms</span>
    <h2>Senders and players.</h2>
    <p>
      Senders push content. Players receive and play it. They find each other on the local network.
    </p>
  </div>

  <div class="platforms__group">
    <div class="platforms__label">
      <span class="eyebrow">Senders</span>
      <p>Browse and pick on these — they push content to a player.</p>
    </div>
    <div class="platforms">
      {#each SENDERS as c}
        <div class="platform">
          <div class="platform__icon"><Icon name={c.icon} /></div>
          <div class="platform__body">
            <h3>{c.name}</h3>
            <p>{c.desc}</p>
            <a class="platform__link" href="#install">Install <Icon name="arrow" size={12} /></a>
          </div>
        </div>
      {/each}
    </div>
  </div>

  <div class="platforms__group">
    <div class="platforms__label">
      <span class="eyebrow">Players</span>
      <p>Install one of these on the device you actually watch on.</p>
    </div>
    <div class="platforms">
      {#each PLAYERS as c}
        <div class="platform">
          <div class="platform__icon"><Icon name={c.icon} /></div>
          <div class="platform__body">
            <h3>{c.name}</h3>
            <p>{c.desc}</p>
            <a class="platform__link" href="#install">Install <Icon name="arrow" size={12} /></a>
          </div>
        </div>
      {/each}
    </div>
  </div>
</section>
