<script lang="ts">
  import Icon from '$lib/icons/Icon.svelte';
  import { INSTALL_TABS, DESKTOP_PLATFORMS } from '$lib/data/site';
  import { onMount } from 'svelte';

  let active = $state('android');
  let activeDesktopOS = $state<'macos' | 'windows' | 'linux'>('macos');

  // Detect client operating system on load to pre-select desktop OS
  onMount(() => {
    if (typeof window !== 'undefined') {
      const userAgent = window.navigator.userAgent.toLowerCase();
      if (userAgent.includes('win')) {
        activeDesktopOS = 'windows';
      } else if (userAgent.includes('linux')) {
        activeDesktopOS = 'linux';
      } else {
        activeDesktopOS = 'macos';
      }
    }
  });

  // Resolve the active desktop platform details if 'desktop' is selected
  const desktopTab = $derived(
    DESKTOP_PLATFORMS.find((p) => p.id === activeDesktopOS) ?? DESKTOP_PLATFORMS[0]
  );

  // Derived tab properties, injecting desktop details dynamically if active is 'desktop'
  const tab = $derived(
    active === 'desktop'
      ? {
          ...INSTALL_TABS.find((t) => t.id === 'desktop')!,
          title: desktopTab.title,
          steps: desktopTab.steps,
          cmd: desktopTab.cmd,
          meta: desktopTab.meta,
          icon: desktopTab.icon
        }
      : (INSTALL_TABS.find((t) => t.id === active) ?? INSTALL_TABS[0])
  );

  const senderTabs = INSTALL_TABS.filter((t) => t.role === 'sender' && !t.hidden);
  const playerTabs = INSTALL_TABS.filter((t) => t.role === 'player' && !t.hidden);

  // The ad-blocked TV browser is presented as a GeckoView plugin of the Android TV
  // player rather than a standalone player tab.
  const browserPlugin = INSTALL_TABS.find((t) => t.id === 'tvbrowser');
</script>

<section class="section wrap" id="install">
  <div class="section-head">
    <span class="eyebrow">Get started</span>
    <h2>Install on every device you own.</h2>
  </div>
  <div class="installer">
    <div class="installer__tabs" role="tablist">
      <div class="installer__group">
        <span class="installer__group-label">Senders</span>
        {#each senderTabs as t}
          <button
            type="button"
            class="installer__tab"
            class:installer__tab--active={active === t.id}
            onclick={() => (active = t.id)}
            role="tab"
            aria-selected={active === t.id}
          >
            <Icon name={t.icon} size={13} /> {t.label}
          </button>
        {/each}
      </div>
      <div class="installer__divider" aria-hidden="true"></div>
      <div class="installer__group">
        <span class="installer__group-label">Players</span>
        {#each playerTabs as t}
          <button
            type="button"
            class="installer__tab"
            class:installer__tab--active={active === t.id}
            onclick={() => (active = t.id)}
            role="tab"
            aria-selected={active === t.id}
          >
            <Icon name={t.icon} size={13} /> {t.label}
          </button>
        {/each}
      </div>
    </div>
    <div class="installer__panel">
      <div>
        <h3>{tab.title}</h3>

        {#if active === 'desktop'}
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

        {#if tab.id === 'appletv'}
          <div class="notice-box">
            <span class="notice-badge">Pre-Release</span>
            <p>
              The Apple TV application is in active development and not yet published on TestFlight.
              Developers and testers can try it by building from source using Xcode.
            </p>
          </div>
        {/if}

        <ol class="installer__steps">
          {#each tab.steps as [k, v], i}
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
          {#if tab.id === 'appletv'}
            <a
              href="https://github.com/playbridgeapp/PlayBridge/tree/main/tv/apple"
              target="_blank"
              rel="noopener noreferrer"
              class="btn btn--primary"
            >
              <Icon name="github" size={13} /> View tvOS Source
            </a>
          {:else}
            {#if tab.downloadUrl}
              <a
                href={tab.downloadUrl}
                target="_blank"
                rel="noopener noreferrer"
                class="btn btn--primary"
              >
                <Icon name="download" size={13} stroke={2.0} /> Download
              </a>
            {/if}
            <a
              href={'https://' + tab.cmd}
              target="_blank"
              rel="noopener noreferrer"
              class="btn"
              class:btn--primary={!tab.downloadUrl}
            >
              <Icon name="github" size={13} /> View on GitHub
            </a>
          {/if}
        </div>
        {#if tab.downloadUrl && (tab.id === 'android' || tab.id === 'androidtv')}
          <div class="arch-select">
            <span>Universal APK (all CPUs) downloaded by default.</span>
            <span>Or download: <a href="{tab.downloadUrl}-v8a" target="_blank" rel="noopener">64-bit (v8a)</a> • <a href="{tab.downloadUrl}-v7a" target="_blank" rel="noopener">32-bit (v7a)</a></span>
          </div>
        {/if}

        {#if active === 'androidtv' && browserPlugin}
          <div class="plugin-box">
            <span class="plugin-badge">Plugin</span>
            <div class="plugin-body">
              <strong>GeckoView + uBlock Origin</strong>
              <p>
                The Android TV player already comes with the built-in System WebView. This optional plugin adds Mozilla's
                GeckoView engine with uBlock Origin.
              </p>
              {#if browserPlugin.downloadUrl}
                <a
                  href={browserPlugin.downloadUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  class="plugin-link"
                >
                  <Icon name="download" size={12} stroke={2.0} /> Download GeckoView plugin
                </a>
              {/if}
            </div>
          </div>
        {/if}
      </div>
    </div>
  </div>
</section>

<style>
  .arch-select {
    margin-top: 16px;
    font-size: 11px;
    color: var(--text-faint);
    display: flex;
    flex-direction: column;
    gap: 4px;
    align-items: center;
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

  .step-desc { color: var(--text-dim); }
  .panel-label { font-size: 10px; letter-spacing: 0.16em; color: var(--text-faint); margin-bottom: 8px; }
  .meta-k { color: var(--text-faint); }

  .installer__group {
    display: flex;
    align-items: flex-end;
    gap: 4px;
  }
  .installer__group-label {
    font-family: 'JetBrains Mono', monospace;
    font-size: 10px;
    letter-spacing: 0.16em;
    text-transform: uppercase;
    color: var(--text-faint);
    padding: 0 6px 15px;
    white-space: nowrap;
  }
  .installer__divider {
    width: 1px;
    background: var(--line-strong);
    align-self: stretch;
    margin: 8px 4px;
    flex-shrink: 0;
  }

  /* Desktop Sub-selector Styling */
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
    transition: color .15s ease, background-color .15s ease;
  }
  .sub-tab:hover {
    color: var(--text);
  }
  .sub-tab--active {
    color: var(--text);
    background: rgba(74, 144, 226, 0.12);
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.05);
  }

  /* Pre-Release Notice Box */
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

  /* GeckoView browser plugin callout (Android TV tab) */
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
  .plugin-body { display: flex; flex-direction: column; gap: 6px; }
  .plugin-body strong { color: var(--text); font-weight: 500; font-size: 14px; }
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
  .plugin-link:hover { text-decoration: underline; }

  @media (max-width: 600px) {
    .plugin-box { flex-direction: column; gap: 10px; }
  }
</style>
