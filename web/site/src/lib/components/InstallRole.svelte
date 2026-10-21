<script lang="ts">
  import Icon from '$lib/icons/Icon.svelte';
  import {
    type InstallRole,
    type InstallProduct,
    type CliInstallPlatform,
    productsForRole,
    detailFor,
    productIdFromHash,
    DESKTOP_PLATFORMS,
    EXTENSION_BROWSERS
  } from '$lib/data/site';
  import { onMount, tick } from 'svelte';

  interface Props {
    role: InstallRole;
  }

  let { role }: Props = $props();

  const products = $derived(productsForRole(role));
  let activeId = $state('');
  let activeDesktopOS = $state<'macos' | 'windows' | 'linux'>('macos');
  let activeCliPlatform = $state<CliInstallPlatform>('unix');
  let activeBrowser = $state<'chrome' | 'firefox'>('chrome');
  let copyState = $state<'idle' | 'copied' | 'failed'>('idle');
  let panelEl = $state<HTMLElement | null>(null);
  let rootEl = $state<HTMLElement | null>(null);
  let tabsEl = $state<HTMLElement | null>(null);
  let fadeStart = $state(false);
  let fadeEnd = $state(false);

  function prefersReducedMotion(): boolean {
    return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
  }

  function applyHash(scroll = false) {
    if (typeof window === 'undefined') return;
    const id = productIdFromHash(window.location.hash, role);
    const list = productsForRole(role);
    if (id) {
      activeId = id;
      if (window.location.hash === '#chrome') activeBrowser = 'chrome';
      if (window.location.hash === '#firefox') activeBrowser = 'firefox';
    } else if (!activeId && list[0]) {
      activeId = list[0].id;
    }
    // Only move the page when the hash asked for a product; a bare /receivers stays at the top.
    // The installer's scroll-margin-top keeps the tab row clear of the sticky nav.
    if (scroll && id) {
      // Wait for the newly selected panel to render so the target position is final.
      void tick().then(() =>
        rootEl?.scrollIntoView({
          behavior: prefersReducedMotion() ? 'auto' : 'smooth',
          block: 'start'
        })
      );
    }
  }

  /** Edge fades on the tab row show there is more to scroll (narrow screens). */
  function updateTabFades() {
    const el = tabsEl;
    if (!el) return;
    const max = el.scrollWidth - el.clientWidth;
    fadeStart = el.scrollLeft > 2;
    fadeEnd = max - el.scrollLeft > 2;
  }

  /** Keep the active tab fully visible inside the horizontally scrolling tab row. */
  function revealActiveTab(smooth: boolean) {
    const el = tabsEl;
    if (!el || !activeId) return;
    const tab = el.querySelector<HTMLElement>(`#tab-${CSS.escape(activeId)}`);
    if (!tab) return;
    const pad = 32;
    const left = tab.offsetLeft;
    const right = left + tab.offsetWidth;
    let target = el.scrollLeft;
    if (left - pad < el.scrollLeft) target = Math.max(0, left - pad);
    else if (right + pad > el.scrollLeft + el.clientWidth) target = right + pad - el.clientWidth;
    if (target !== el.scrollLeft) {
      el.scrollTo({ left: target, behavior: smooth && !prefersReducedMotion() ? 'smooth' : 'auto' });
    }
  }

  // Animate the tab row only for clicks/arrow keys. Hash-driven selection jumps, because a
  // second smooth scroll running alongside the page's smooth scroll makes Chrome overshoot.
  let animateTabReveal = false;

  $effect(() => {
    void activeId;
    if (!tabsEl) return;
    revealActiveTab(animateTabReveal);
    animateTabReveal = false;
    updateTabFades();
  });

  onMount(() => {
    const ua = window.navigator.userAgent.toLowerCase();
    if (ua.includes('win')) {
      activeDesktopOS = 'windows';
      activeCliPlatform = 'windows';
    } else if (ua.includes('linux')) {
      activeDesktopOS = 'linux';
      activeCliPlatform = 'unix';
    } else {
      activeDesktopOS = 'macos';
      activeCliPlatform = 'unix';
    }
    if (ua.includes('firefox')) activeBrowser = 'firefox';

    applyHash(true);
    const onHash = () => applyHash(true);
    window.addEventListener('hashchange', onHash);

    revealActiveTab(false);
    updateTabFades();
    const ro = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(updateTabFades) : null;
    if (tabsEl) ro?.observe(tabsEl);

    return () => {
      window.removeEventListener('hashchange', onHash);
      ro?.disconnect();
    };
  });

  $effect(() => {
    const list = products;
    if (!list.length) return;
    if (!activeId || !list.some((p) => p.id === activeId)) {
      activeId = list[0].id;
    }
  });

  const product = $derived(
    (products.find((p) => p.id === activeId) ?? products[0]) as InstallProduct | undefined
  );

  const detail = $derived(product ? detailFor(product, role) : undefined);

  const desktopTab = $derived(
    DESKTOP_PLATFORMS.find((p) => p.id === activeDesktopOS) ?? DESKTOP_PLATFORMS[0]
  );

  const browserTab = $derived(
    EXTENSION_BROWSERS.find((b) => b.id === activeBrowser) ?? EXTENSION_BROWSERS[0]
  );

  const isExtension = $derived(product?.id === 'extension');
  const isDesktop = $derived(!!detail?.desktop);
  const isCliInstaller = $derived(product?.id === 'cli' && !!detail?.installCommands);
  const cliInstallCommand = $derived(
    isCliInstaller ? (detail?.installCommands?.[activeCliPlatform] ?? '') : ''
  );

  const title = $derived(
    isDesktop ? desktopTab.title : isExtension ? browserTab.title : (detail?.title ?? '')
  );

  const steps = $derived(
    isDesktop
      ? role === 'sender'
        ? desktopTab.senderSteps
        : desktopTab.receiverSteps
      : isExtension
        ? browserTab.steps
        : (detail?.steps ?? [])
  );

  const rawCmd = $derived(isDesktop ? desktopTab.cmd : (detail?.cmd ?? ''));
  const downloadUrl = $derived(
    isDesktop ? desktopTab.downloadUrl : isExtension ? browserTab.downloadUrl : detail?.downloadUrl
  );
  const playStoreUrl = $derived(detail?.playStoreUrl);
  const downloadStore = $derived(storeNameFor(downloadUrl));
  // Never show two buttons that open the same store listing.
  const cmd = $derived(
    rawCmd && downloadUrl && hostOf(rawCmd) === hostOf(downloadUrl) ? '' : rawCmd
  );
  const cmdIsReleases = $derived(hostOf(cmd) === 'github.com' && cmd.includes('/releases'));
  const meta = $derived(
    isDesktop ? desktopTab.meta : isExtension ? browserTab.meta : (detail?.meta ?? [])
  );

  const dual = $derived(!!(product?.sender && product?.receiver));
  const otherSetupHref = $derived(
    role === 'sender' ? `/receivers#${product?.id ?? ''}` : `/senders#${product?.id ?? ''}`
  );
  const otherSetupLabel = $derived(
    role === 'sender' ? 'View receiver setup' : 'View sender setup'
  );

  function selectProduct(id: string) {
    animateTabReveal = true;
    activeId = id;
    if (typeof history !== 'undefined') {
      history.replaceState(null, '', `#${id}`);
    }
    queueMicrotask(() => panelEl?.focus({ preventScroll: true }));
  }

  function onTabListKeydown(e: KeyboardEvent) {
    const list = products;
    if (!list.length) return;
    const idx = list.findIndex((p) => p.id === activeId);
    if (idx < 0) return;
    let next = idx;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
      e.preventDefault();
      next = (idx + 1) % list.length;
    } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
      e.preventDefault();
      next = (idx - 1 + list.length) % list.length;
    } else if (e.key === 'Home') {
      e.preventDefault();
      next = 0;
    } else if (e.key === 'End') {
      e.preventDefault();
      next = list.length - 1;
    } else {
      return;
    }
    selectProduct(list[next].id);
    const btn = document.getElementById(`tab-${list[next].id}`);
    btn?.focus();
  }

  function hrefForCmd(c: string): string {
    return c.startsWith('http') ? c : `https://${c}`;
  }

  function hostOf(url: string | undefined): string {
    if (!url || url.startsWith('/')) return '';
    try {
      return new URL(hrefForCmd(url)).hostname;
    } catch {
      return '';
    }
  }

  /** Human store name for a known store URL; '' for direct downloads. */
  function storeNameFor(url: string | undefined): string {
    const host = hostOf(url);
    if (host === 'chromewebstore.google.com') return 'Chrome Web Store';
    if (host === 'addons.mozilla.org') return 'Firefox Add-ons';
    if (host === 'play.google.com') return 'Google Play';
    return '';
  }

  async function copyInstallCommand(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      copyState = 'copied';
    } catch {
      copyState = 'failed';
    }
    setTimeout(() => {
      copyState = 'idle';
    }, 2000);
  }
</script>

{#if product && detail}
  <div class="installer" id="install" bind:this={rootEl}>
    <div
      class="installer__tabbar"
      class:installer__tabbar--fade-start={fadeStart}
      class:installer__tabbar--fade-end={fadeEnd}
    >
      <div
        class="installer__tabs"
        role="tablist"
        tabindex="-1"
        aria-label={role === 'sender' ? 'Sender products' : 'Receiver products'}
        onkeydown={onTabListKeydown}
        onscroll={updateTabFades}
        bind:this={tabsEl}
      >
        {#each products as t}
          <button
            type="button"
            class="installer__tab"
            class:installer__tab--active={activeId === t.id}
            onclick={() => selectProduct(t.id)}
            role="tab"
            aria-selected={activeId === t.id}
            aria-controls="panel-{t.id}"
            id="tab-{t.id}"
            tabindex={activeId === t.id ? 0 : -1}
          >
            <Icon name={t.icon} size={13} /> {t.label}
          </button>
        {/each}
      </div>
    </div>

    <div
      class="installer__panel"
      role="tabpanel"
      id="panel-{product.id}"
      aria-labelledby="tab-{product.id}"
      tabindex="-1"
      bind:this={panelEl}
    >
      <div class="installer__body">
        <h3>{title}</h3>

        {#if dual}
          <p class="role-note">
            <span class="role-pill">{role === 'sender' ? 'Sender' : 'Receiver'}</span>
            <span class="role-pill role-pill--muted"
              >Also a {role === 'sender' ? 'receiver' : 'sender'}</span
            >
            Same product, different job on this page.
            <a href={otherSetupHref}>{otherSetupLabel}</a>
          </p>
        {/if}

        {#if isDesktop}
          <div class="installer__sub-selector" role="group" aria-label="Desktop operating system">
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

        {#if isExtension}
          <div class="installer__sub-selector" role="group" aria-label="Browser">
            {#each EXTENSION_BROWSERS as b}
              <button
                type="button"
                class="sub-tab"
                class:sub-tab--active={activeBrowser === b.id}
                onclick={() => {
                  activeBrowser = b.id;
                  if (typeof history !== 'undefined') {
                    history.replaceState(null, '', `#extension`);
                  }
                }}
              >
                <Icon name={b.icon} size={12} /> {b.label}
              </button>
            {/each}
          </div>
        {/if}

        {#if isCliInstaller}
          <div class="installer__sub-selector" role="group" aria-label="CLI operating system">
            <button
              type="button"
              class="sub-tab"
              class:sub-tab--active={activeCliPlatform === 'unix'}
              onclick={() => {
                activeCliPlatform = 'unix';
                copyState = 'idle';
              }}
            >
              <Icon name="terminal" size={12} /> macOS &amp; Linux
            </button>
            <button
              type="button"
              class="sub-tab"
              class:sub-tab--active={activeCliPlatform === 'windows'}
              onclick={() => {
                activeCliPlatform = 'windows';
                copyState = 'idle';
              }}
            >
              <Icon name="windows" size={12} /> Windows
            </button>
          </div>
        {/if}

        {#if detail.notice}
          <div class="notice-box">
            <span class="notice-badge">{detail.notice.badge}</span>
            <p>{detail.notice.text}</p>
          </div>
        {/if}

        {#if cliInstallCommand}
          <div class="code-block">
            <div class="code-block__bar">
              <span class="code-block__label">
                Install ({activeCliPlatform === 'windows' ? 'Windows PowerShell' : 'macOS & Linux'})
              </span>
              <button
                type="button"
                class="code-block__copy"
                onclick={() => copyInstallCommand(cliInstallCommand)}
              >
                {#if copyState === 'copied'}
                  Copied
                {:else if copyState === 'failed'}
                  Copy failed
                {:else}
                  Copy
                {/if}
              </button>
            </div>
            <pre class="code-block__pre"><code>{cliInstallCommand}</code></pre>
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
          {#if product.id === 'dlna'}
            <a href="/senders#android" class="btn btn--primary">Get the Android sender</a>
          {:else if product.id === 'appletv'}
            <a
              href="https://github.com/playbridgeapp/PlayBridge/tree/main/tv/apple"
              target="_blank"
              rel="noopener noreferrer"
              class="btn btn--primary"
            >
              <Icon name="github" size={13} /> View tvOS source
            </a>
          {:else}
            {#if playStoreUrl}
              <a
                href={playStoreUrl}
                target="_blank"
                rel="noopener noreferrer"
                class="btn btn--primary"
              >
                <Icon name="googleplay" size={13} /> Get it on Google Play
              </a>
            {/if}
            {#if downloadUrl}
              <a
                href={downloadUrl}
                target="_blank"
                rel="noopener noreferrer"
                class="btn"
                class:btn--primary={!playStoreUrl}
              >
                {#if downloadStore}
                  {#if downloadStore === 'Firefox Add-ons'}
                    <Icon name="firefox" size={13} />
                  {:else}
                    <Icon name="link" size={13} stroke={2.0} />
                  {/if}
                  Get it on {downloadStore}
                {:else if playStoreUrl}
                  <Icon name="download" size={13} stroke={2.0} /> Download APK
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
                class:btn--primary={!playStoreUrl && !downloadUrl}
              >
                {#if cmdIsReleases}
                  <Icon name="github" size={13} /> GitHub Releases <span aria-hidden="true">↗</span>
                {:else if cmd.includes('github.com')}
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
              <a href="{downloadUrl}-v8a" target="_blank" rel="noopener noreferrer">64-bit (v8a)</a>
              ·
              <a href="{downloadUrl}-v7a" target="_blank" rel="noopener noreferrer">32-bit (v7a)</a
              ></span
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
  /* Hash jumps (/receivers#desktop) land with the tab row below the sticky nav. */
  .installer {
    scroll-margin-top: 88px;
  }

  /* Tab row: one line that scrolls sideways on narrow screens, with edge fades
     as the affordance and snap so a tab never rests half-cut. */
  .installer__tabbar {
    position: relative;
  }
  .installer__tabbar::before,
  .installer__tabbar::after {
    content: '';
    position: absolute;
    top: 0;
    bottom: 1px;
    width: 56px;
    pointer-events: none;
    opacity: 0;
    transition: opacity 0.15s ease;
    z-index: 1;
  }
  .installer__tabbar::before {
    left: 0;
    background: linear-gradient(to right, var(--surface-solid) 30%, transparent);
  }
  .installer__tabbar::after {
    right: 0;
    background: linear-gradient(to left, var(--surface-solid) 30%, transparent);
  }
  .installer__tabbar--fade-start::before,
  .installer__tabbar--fade-end::after {
    opacity: 1;
  }
  .installer__tabs {
    scroll-snap-type: x proximity;
    scroll-padding-inline: 16px;
    overscroll-behavior-x: contain;
    scrollbar-width: none;
  }
  .installer__tabs::-webkit-scrollbar {
    display: none;
  }
  .installer__tab {
    scroll-snap-align: start;
  }

  /* Panel content starts on the same left edge as the first tab label. */
  .installer__panel {
    max-width: none;
    margin: 0;
    padding: 40px 28px 40px;
  }
  .installer__body {
    max-width: 680px;
  }

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
    font-family: var(--font-mono-ui);
    font-size: 9px;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: var(--accent);
    background: rgba(74, 144, 226, 0.12);
    padding: 2px 8px;
    border-radius: 99px;
    font-weight: 600;
  }
  .role-pill--muted {
    color: var(--text-dim);
    background: rgba(200, 220, 255, 0.06);
  }

  .code-block {
    margin: 0 0 24px;
    border-radius: 8px;
    border: 1px solid var(--line);
    background: rgba(0, 0, 0, 0.2);
    overflow: hidden;
  }
  .code-block__bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 8px 12px;
    border-bottom: 1px solid var(--line);
    background: rgba(200, 220, 255, 0.03);
  }
  .code-block__label {
    font-family: var(--font-mono);
    font-size: 10px;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--text-faint);
  }
  .code-block__copy {
    border: 1px solid var(--line-strong);
    background: rgba(74, 144, 226, 0.12);
    color: var(--text);
    font-size: 12px;
    font-weight: 500;
    padding: 4px 10px;
    border-radius: 6px;
  }
  .code-block__copy:hover {
    border-color: rgba(74, 144, 226, 0.5);
  }
  .code-block__pre {
    margin: 0;
    padding: 14px 16px;
    overflow-x: auto;
    font-family: var(--font-mono);
    font-size: 12px;
    line-height: 1.5;
    color: var(--text);
    white-space: pre-wrap;
    word-break: break-all;
  }

  .installer__sub-selector {
    display: flex;
    padding: 0;
    border-radius: 0;
    border: 0;
    margin-bottom: 24px;
    gap: 8px;
    width: fit-content;
    flex-wrap: wrap;
  }
  .sub-tab {
    background: transparent;
    border: 1px solid var(--line);
    color: var(--text-faint);
    padding: 6px 12px;
    font-size: 12px;
    font-weight: 500;
    display: flex;
    align-items: center;
    gap: 6px;
    border-radius: 8px;
    transition:
      color 0.15s ease,
      border-color 0.15s ease;
  }
  .sub-tab:hover {
    color: var(--text);
  }
  .sub-tab--active {
    color: var(--text);
    border-color: color-mix(in oklab, var(--accent) 55%, transparent);
  }

  .notice-box {
    margin-bottom: 24px;
    padding: 16px;
    border-radius: 8px;
    background: transparent;
    border: 1px solid var(--line);
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .notice-badge {
    font-family: var(--font-mono-ui);
    font-size: 9px;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: var(--accent);
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
    border-radius: 8px;
    background: transparent;
    border: 1px solid var(--line);
    display: flex;
    gap: 14px;
    align-items: flex-start;
  }
  .plugin-badge {
    font-family: var(--font-mono-ui);
    font-size: 9px;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: var(--accent);
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
    font-family: var(--font-mono-ui);
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

  .installer__panel:focus {
    outline: none;
  }
  .installer__panel:focus-visible {
    box-shadow: inset 0 0 0 1px rgba(74, 144, 226, 0.45);
  }

  @media (max-width: 900px) {
    .installer__panel {
      padding: 28px 28px 32px;
    }
  }
  @media (max-width: 768px) {
    /* Tighter tabs: the four sender tabs fit a 390px phone without scrolling. */
    .installer__tabs {
      gap: 2px;
      padding: 0 14px;
    }
    .installer__tab {
      padding: 14px 8px;
    }
    .installer__panel {
      padding: 24px 22px 28px;
    }
  }

  @media (max-width: 600px) {
    .plugin-box {
      flex-direction: column;
      gap: 10px;
    }
  }
</style>
