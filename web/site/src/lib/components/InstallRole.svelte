<script lang="ts">
  import Icon from '$lib/icons/Icon.svelte';
  import {
    type InstallRole,
    type InstallProduct,
    productsForRole,
    detailFor,
    DESKTOP_PLATFORMS
  } from '$lib/data/site';
  import { onMount } from 'svelte';

  interface Props {
    role: InstallRole;
  }

  let { role }: Props = $props();

  const products = $derived(productsForRole(role));
  let activeId = $state(productsForRole(role)[0]?.id ?? '');
  let activeDesktopOS = $state<'macos' | 'windows' | 'linux'>('macos');

  onMount(() => {
    if (typeof window === 'undefined') return;
    const ua = window.navigator.userAgent.toLowerCase();
    if (ua.includes('win')) activeDesktopOS = 'windows';
    else if (ua.includes('linux')) activeDesktopOS = 'linux';
    else activeDesktopOS = 'macos';

    const fromHash = window.location.hash.replace(/^#/, '');
    if (fromHash && products.some((p) => p.id === fromHash)) {
      activeId = fromHash;
    }
  });

  // Keep selection valid if role/products change
  $effect(() => {
    if (activeId && !products.some((p) => p.id === activeId) && products[0]) {
      activeId = products[0].id;
    }
  });

  const product = $derived(
    (products.find((p) => p.id === activeId) ?? products[0]) as InstallProduct | undefined
  );

  const detail = $derived(product ? detailFor(product, role) : undefined);

  const desktopTab = $derived(
    DESKTOP_PLATFORMS.find((p) => p.id === activeDesktopOS) ?? DESKTOP_PLATFORMS[0]
  );

  const title = $derived(
    detail?.desktop ? desktopTab.title : (detail?.title ?? '')
  );

  const steps = $derived(
    detail?.desktop
      ? role === 'sender'
        ? desktopTab.senderSteps
        : desktopTab.receiverSteps
      : (detail?.steps ?? [])
  );

  const cmd = $derived(detail?.desktop ? desktopTab.cmd : (detail?.cmd ?? ''));
  const downloadUrl = $derived(
    detail?.desktop ? desktopTab.downloadUrl : detail?.downloadUrl
  );
  const meta = $derived(detail?.desktop ? desktopTab.meta : (detail?.meta ?? []));

  const otherRole = $derived(role === 'sender' ? 'receiver' : 'sender');
  const otherHref = $derived(role === 'sender' ? '/receivers' : '/senders');
  const otherLabel = $derived(role === 'sender' ? 'Receivers' : 'Senders');
  const dual = $derived(!!(product?.sender && product?.receiver));

  function selectProduct(id: string) {
    activeId = id;
    if (typeof history !== 'undefined') {
      history.replaceState(null, '', `#${id}`);
    }
  }

  function hrefForCmd(c: string): string {
    return c.startsWith('http') ? c : `https://${c}`;
  }
</script>

{#if product && detail}
  <div class="installer" id="install">
    <div class="installer__tabs" role="tablist" aria-label={role === 'sender' ? 'Senders' : 'Receivers'}>
      {#each products as t}
        <button
          type="button"
          class="installer__tab"
          class:installer__tab--active={activeId === t.id}
          onclick={() => selectProduct(t.id)}
          role="tab"
          aria-selected={activeId === t.id}
          id="tab-{t.id}"
        >
          <Icon name={t.icon} size={13} /> {t.label}
        </button>
      {/each}
    </div>

    <div class="installer__panel" role="tabpanel" aria-labelledby="tab-{product.id}">
      <div>
        <h3>{title}</h3>

        {#if dual}
          <p class="role-note">
            <span class="role-pill">{role === 'sender' ? 'Sender' : 'Receiver'}</span>
            <span class="role-pill role-pill--muted">Also a {otherRole}</span>
            Same product, different job on this page.
            <a href="{otherHref}#{product.id}">View as {otherLabel.slice(0, -1).toLowerCase()}</a>
          </p>
        {/if}

        {#if detail.desktop}
          <div class="installer__sub-selector">
            {#each DESKTOP_PLATFORMS as p}
              <button
                type="button"
                class="sub-tab"
                class:sub-tab--active={activeDesktopOS === p.id}
                onclick={() => (activeDesktopOS = p.id)}
              >
                <Icon name={p.icon} size={12} /> {p.label}
              </button>
            {/each}
          </div>
        {/if}

        {#if detail.notice}
          <div class="notice-box">
            <span class="notice-badge">{detail.notice.badge}</span>
            <p>{detail.notice.text}</p>
          </div>
        {/if}

        <ol class="installer__steps">
          {#each steps as [k, v], i}
            <li>
              <span class="n">{String(i + 1).padStart(2, '0')}</span>
              <span>
                <strong>{k}</strong>
                <span class="step-desc">{v}</span>
              </span>
            </li>
          {/each}
        </ol>

        <div class="cta-row">
          {#if product.id === 'appletv'}
            <a
              href="https://github.com/playbridgeapp/PlayBridge/tree/main/tv/apple"
              target="_blank"
              rel="noopener noreferrer"
              class="btn btn--primary"
            >
              <Icon name="github" size={13} /> View tvOS source
            </a>
          {:else}
            {#if downloadUrl}
              <a
                href={downloadUrl}
                target="_blank"
                rel="noopener noreferrer"
                class="btn btn--primary"
              >
                {#if downloadUrl.startsWith('https://')}
                  <Icon name="link" size={13} stroke={2.0} /> Get extension
                {:else}
                  <Icon name="download" size={13} stroke={2.0} /> Download
                {/if}
              </a>
            {/if}
            {#if cmd}
              <a
                href={hrefForCmd(cmd)}
                target="_blank"
                rel="noopener noreferrer"
                class="btn"
                class:btn--primary={!downloadUrl}
              >
                {#if cmd.includes('github.com')}
                  <Icon name="github" size={13} /> View on GitHub
                {:else if cmd.startsWith('git ')}
                  <Icon name="github" size={13} /> Clone
                {:else}
                  <Icon name="link" size={13} /> Open store
                {/if}
              </a>
            {/if}
            {#if product.id === 'cli'}
              <a
                href="https://github.com/playbridgeapp/playbridge/blob/main/cli/README.md"
                target="_blank"
                rel="noopener noreferrer"
                class="btn"
              >
                <Icon name="github" size={13} /> CLI docs
              </a>
            {/if}
          {/if}
        </div>

        {#if downloadUrl && (product.id === 'android' || product.id === 'androidtv')}
          <div class="arch-select">
            <span>Universal APK (all CPUs) downloaded by default.</span>
            <span
              >Or download:
              <a href="{downloadUrl}-v8a" target="_blank" rel="noopener">64-bit (v8a)</a>
              ·
              <a href="{downloadUrl}-v7a" target="_blank" rel="noopener">32-bit (v7a)</a></span
            >
          </div>
        {/if}

        {#if detail.plugin}
          <div class="plugin-box">
            <span class="plugin-badge">Plugin</span>
            <div class="plugin-body">
              <strong>{detail.plugin.title}</strong>
              <p>{detail.plugin.body}</p>
              {#if detail.plugin.downloadUrl}
                <a
                  href={detail.plugin.downloadUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  class="plugin-link"
                >
                  <Icon name="download" size={12} stroke={2.0} /> Download plugin
                </a>
              {/if}
            </div>
          </div>
        {/if}

        {#if meta.length}
          <dl class="meta-row">
            {#each meta as [k, v]}
              <div>
                <dt>{k}</dt>
                <dd>{v}</dd>
              </div>
            {/each}
          </dl>
        {/if}
      </div>
    </div>
  </div>
{/if}

<style>
  .step-desc {
    color: var(--text-dim);
  }

  .role-note {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    margin: 0 0 18px;
    font-size: 13px;
    line-height: 1.45;
    color: var(--text-dim);
  }
  .role-note a {
    color: var(--accent);
    font-weight: 500;
  }
  .role-note a:hover {
    text-decoration: underline;
  }
  .role-pill {
    font-family: 'JetBrains Mono', monospace;
    font-size: 9px;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: #4a90e2;
    background: rgba(74, 144, 226, 0.15);
    padding: 2px 8px;
    border-radius: 99px;
    font-weight: 600;
  }
  .role-pill--muted {
    color: var(--text-dim);
    background: rgba(200, 220, 255, 0.06);
  }

  .installer__sub-selector {
    display: flex;
    background: rgba(0, 0, 0, 0.25);
    padding: 4px;
    border-radius: 8px;
    border: 1px solid var(--line);
    margin-bottom: 24px;
    gap: 2px;
    width: fit-content;
  }
  .sub-tab {
    background: transparent;
    border: 0;
    color: var(--text-faint);
    padding: 6px 14px;
    font-size: 12px;
    font-weight: 500;
    display: flex;
    align-items: center;
    gap: 6px;
    border-radius: 6px;
    transition:
      color 0.15s ease,
      background-color 0.15s ease;
  }
  .sub-tab:hover {
    color: var(--text);
  }
  .sub-tab--active {
    color: var(--text);
    background: rgba(74, 144, 226, 0.12);
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.05);
  }

  .notice-box {
    margin-bottom: 24px;
    padding: 16px;
    border-radius: 10px;
    background: rgba(74, 144, 226, 0.04);
    border: 1px solid rgba(74, 144, 226, 0.15);
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .notice-badge {
    font-family: 'JetBrains Mono', monospace;
    font-size: 9px;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: #4a90e2;
    background: rgba(74, 144, 226, 0.15);
    padding: 2px 8px;
    border-radius: 99px;
    width: fit-content;
    font-weight: 600;
  }
  .notice-box p {
    font-size: 13px;
    line-height: 1.45;
    color: var(--text-dim);
    margin: 0;
  }

  .arch-select {
    margin-top: 16px;
    font-size: 11px;
    color: var(--text-faint);
    display: flex;
    flex-direction: column;
    gap: 4px;
    align-items: flex-start;
    line-height: 1.4;
  }
  .arch-select a {
    color: var(--accent);
    text-decoration: none;
    font-weight: 500;
  }
  .arch-select a:hover {
    text-decoration: underline;
  }

  .plugin-box {
    margin-top: 24px;
    padding: 16px;
    border-radius: 10px;
    background: rgba(74, 144, 226, 0.04);
    border: 1px solid rgba(74, 144, 226, 0.15);
    display: flex;
    gap: 14px;
    align-items: flex-start;
  }
  .plugin-badge {
    font-family: 'JetBrains Mono', monospace;
    font-size: 9px;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: #4a90e2;
    background: rgba(74, 144, 226, 0.15);
    padding: 3px 8px;
    border-radius: 99px;
    font-weight: 600;
    flex: 0 0 auto;
    margin-top: 2px;
  }
  .plugin-body {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .plugin-body strong {
    color: var(--text);
    font-weight: 500;
    font-size: 14px;
  }
  .plugin-body p {
    font-size: 13px;
    line-height: 1.45;
    color: var(--text-dim);
    margin: 0;
  }
  .plugin-link {
    margin-top: 4px;
    font-size: 13px;
    color: var(--accent);
    font-weight: 500;
    display: inline-flex;
    align-items: center;
    gap: 5px;
    width: fit-content;
  }
  .plugin-link:hover {
    text-decoration: underline;
  }

  .meta-row {
    margin: 28px 0 0;
    padding: 0;
    display: flex;
    flex-wrap: wrap;
    gap: 16px 28px;
  }
  .meta-row div {
    min-width: 100px;
  }
  .meta-row dt {
    font-family: 'JetBrains Mono', monospace;
    font-size: 10px;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: var(--text-faint);
    margin: 0 0 4px;
  }
  .meta-row dd {
    margin: 0;
    font-size: 13px;
    color: var(--text-dim);
  }

  @media (max-width: 600px) {
    .plugin-box {
      flex-direction: column;
      gap: 10px;
    }
  }
</style>
