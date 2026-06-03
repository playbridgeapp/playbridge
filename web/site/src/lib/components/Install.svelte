<script lang="ts">
  import Icon from '$lib/icons/Icon.svelte';
  import { INSTALL_TABS, DESKTOP_PLATFORMS } from '$lib/data/site';
  import { onMount } from 'svelte';

  let active = $state('android');
  let copied = $state(false);
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

  const senderTabs = INSTALL_TABS.filter((t) => t.role === 'sender');
  const playerTabs = INSTALL_TABS.filter((t) => t.role === 'player');

  async function copy() {
    if (typeof navigator === 'undefined' || !navigator.clipboard) return;
    await navigator.clipboard.writeText(tab.cmd);
    copied = true;
    setTimeout(() => (copied = false), 1400);
  }
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
            <button
              type="button"
              class="btn btn--primary"
              onclick={() => window.open('https://github.com/playbridgeapp/PlayBridge/tree/main/tv/apple', '_blank', 'noopener,noreferrer')}
            >
              <Icon name="github" size={13} /> View tvOS Source
            </button>
          {:else}
            {#if tab.downloadUrl}
              <button
                type="button"
                class="btn btn--primary"
                onclick={() => window.open(tab.downloadUrl, '_blank', 'noopener,noreferrer')}
              >
                <Icon name="download" size={13} stroke={2.0} /> Direct Download
              </button>
            {/if}
            <button
              type="button"
              class="btn"
              class:btn--primary={!tab.downloadUrl}
              onclick={() => window.open('https://' + tab.cmd, '_blank', 'noopener,noreferrer')}
            >
              <Icon name="github" size={13} /> View on GitHub
            </button>
          {/if}
          <a class="btn" href="https://github.com/playbridgeapp/PlayBridge#readme" rel="noopener" target="_blank">Docs</a>
        </div>
      </div>
      <div>
        <div class="mono panel-label">
          {#if tab.id === 'appletv'}
            BUILD COMMAND
          {:else}
            GITHUB RELEASES
          {/if}
        </div>

        <div class="terminal-mock">
          <div class="terminal-header">
            <div class="terminal-dots">
              <span class="dot dot--red"></span>
              <span class="dot dot--yellow"></span>
              <span class="dot dot--green"></span>
            </div>
            <span class="terminal-title">bash</span>
          </div>
          <div class="terminal-body">
            <code class="terminal-code">{tab.cmd}</code>
            <button type="button" class="copy-btn" onclick={copy}>
              {copied ? '✓ COPIED' : 'COPY'}
            </button>
          </div>
        </div>

        <div class="installer__meta">
          {#each tab.meta as [k, v]}
            <div class="installer__meta__row">
              <span class="meta-k">{k}</span>
              <span>{v}</span>
            </div>
          {/each}
        </div>
      </div>
    </div>
  </div>
</section>

<style>
  .step-desc { color: var(--text-dim); }
  .cta-row { margin-top: 28px; display: flex; gap: 8px; }
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

  /* Terminal Mockup */
  .terminal-mock {
    background: rgba(0, 0, 0, 0.45);
    border: 1px solid var(--line);
    border-radius: 12px;
    overflow: hidden;
    box-shadow: 0 10px 30px rgba(0, 0, 0, 0.5);
  }
  .terminal-header {
    background: rgba(0, 0, 0, 0.2);
    border-bottom: 1px solid var(--line);
    padding: 8px 12px;
    display: flex;
    align-items: center;
    position: relative;
  }
  .terminal-dots {
    display: flex;
    gap: 6px;
  }
  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    display: inline-block;
  }
  .dot--red { background: #ff5f56; }
  .dot--yellow { background: #ffbd2e; }
  .dot--green { background: #27c93f; }
  .terminal-title {
    font-family: 'JetBrains Mono', monospace;
    font-size: 10px;
    color: var(--text-faint);
    position: absolute;
    left: 50%;
    transform: translateX(-50%);
  }
  .terminal-body {
    padding: 14px 16px;
    font-family: 'JetBrains Mono', monospace;
    font-size: 12px;
    color: var(--text-dim);
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .terminal-code {
    color: var(--text);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    user-select: all;
  }
</style>
