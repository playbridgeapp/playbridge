<script lang="ts">
  import {
    isServerModalOpen,
    connectToJellyfinServer,
    loadDemoMode,
    isLoadingLibrary,
    serverError,
    savedAccounts,
    switchAccount,
    removeSavedAccount,
    serverConfig
  } from '../stores/appState';
  import {
    X,
    Server,
    Sparkles,
    Lock,
    User,
    Globe,
    AlertCircle,
    Loader2,
    Plus,
    Trash2,
    Check
  } from 'lucide-svelte';

  let serverUrl = 'http://10.8.0.6:8096';
  let username = '';
  let password = '';
  let activeView: 'saved' | 'add' = 'add';

  $: currentServerId = $serverConfig.url ? `${$serverConfig.url}_${$serverConfig.userId}` : '';
  $: hasSavedAccounts = $savedAccounts.length > 0;

  function close() {
    $isServerModalOpen = false;
  }

  async function handleConnect() {
    if (!serverUrl) return;
    await connectToJellyfinServer(serverUrl, username, password);
  }

  function handleSwitch(acc: any) {
    switchAccount(acc);
    close();
  }

  function handleDemo() {
    loadDemoMode();
    close();
  }
</script>

{#if $isServerModalOpen}
  <div class="modal-backdrop" on:click={close}>
    <div class="modal-container" on:click|stopPropagation>
      <button class="modal-close-btn" on:click={close}>
        <X size={20} />
      </button>

      <div class="modal-header">
        <div class="header-icon">
          <Server size={28} />
        </div>
        <h2 class="modal-title">Manage Jellyfin Servers & Profiles</h2>
        <p class="modal-desc">
          Add and switch between multiple servers or user accounts seamlessly.
        </p>

        {#if hasSavedAccounts}
          <div class="tab-pill-group">
            <button
              class="tab-pill"
              class:active={activeView === 'saved'}
              on:click={() => (activeView = 'saved')}
            >
              Saved Profiles ({$savedAccounts.length})
            </button>
            <button
              class="tab-pill"
              class:active={activeView === 'add'}
              on:click={() => (activeView = 'add')}
            >
              <Plus size={14} />
              Add Server / User
            </button>
          </div>
        {/if}
      </div>

      {#if $serverError}
        <div class="error-banner">
          <AlertCircle size={16} />
          <span>{$serverError}</span>
        </div>
      {/if}

      {#if activeView === 'saved' && hasSavedAccounts}
        <!-- Saved Profiles List -->
        <div class="saved-modal-list">
          {#each $savedAccounts as acc (acc.id)}
            <div class="saved-card" class:active-account={acc.id === currentServerId}>
              <div class="saved-avatar">
                {acc.username.slice(0, 2).toUpperCase()}
              </div>

              <div class="saved-meta" on:click={() => handleSwitch(acc)}>
                <div class="saved-user-row">
                  <span class="saved-name">{acc.username}</span>
                  {#if acc.id === currentServerId}
                    <span class="active-badge">ACTIVE</span>
                  {/if}
                </div>
                <span class="saved-server">{acc.serverName}</span>
                <span class="saved-url">{acc.url}</span>
              </div>

              <div class="saved-actions">
                {#if acc.id !== currentServerId}
                  <button class="btn-secondary switch-btn" on:click={() => handleSwitch(acc)}>
                    Switch
                  </button>
                  <button
                    class="del-btn"
                    on:click={() => removeSavedAccount(acc.id)}
                    title="Remove saved account"
                  >
                    <Trash2 size={15} />
                  </button>
                {:else}
                  <span class="current-indicator">
                    <Check size={16} />
                  </span>
                {/if}
              </div>
            </div>
          {/each}
        </div>
      {:else}
        <!-- Add Server / User Form -->
        <form class="connect-form" on:submit|preventDefault={handleConnect}>
          <div class="form-group">
            <label for="server-url">Server Address</label>
            <div class="input-wrapper">
              <Globe size={16} class="input-icon" />
              <input
                id="server-url"
                type="text"
                placeholder="http://10.8.0.6:8096 or https://jellyfin.domain.com"
                bind:value={serverUrl}
                required
              />
            </div>
          </div>

          <div class="form-group">
            <label for="username">Username</label>
            <div class="input-wrapper">
              <User size={16} class="input-icon" />
              <input
                id="username"
                type="text"
                placeholder="Username"
                bind:value={username}
                required
              />
            </div>
          </div>

          <div class="form-group">
            <label for="password">Password (Optional)</label>
            <div class="input-wrapper">
              <Lock size={16} class="input-icon" />
              <input
                id="password"
                type="password"
                placeholder="••••••••"
                bind:value={password}
              />
            </div>
          </div>

          <div class="form-actions">
            <button type="submit" class="btn-primary form-btn" disabled={$isLoadingLibrary}>
              {#if $isLoadingLibrary}
                <Loader2 size={16} class="spinner" />
                <span>Connecting...</span>
              {:else}
                <span>Connect & Save Server</span>
              {/if}
            </button>

            <button type="button" class="btn-secondary form-btn" on:click={handleDemo}>
              <Sparkles size={16} />
              <span>Use Demo Mode</span>
            </button>
          </div>
        </form>
      {/if}
    </div>
  </div>
{/if}

<style>
  .modal-backdrop {
    position: fixed;
    inset: 0;
    background-color: rgba(0, 0, 0, 0.8);
    backdrop-filter: blur(12px);
    -webkit-backdrop-filter: blur(12px);
    z-index: 100;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 20px;
    animation: fadeIn 0.2s ease;
  }

  @keyframes fadeIn {
    from { opacity: 0; }
    to { opacity: 1; }
  }

  .modal-container {
    background-color: var(--bg-surface-elevated);
    border: 1px solid var(--border);
    border-radius: var(--radius-xl);
    width: 100%;
    max-width: 500px;
    max-height: 90vh;
    overflow-y: auto;
    position: relative;
    padding: 32px;
    box-shadow: var(--shadow-lg);
  }

  .modal-close-btn {
    position: absolute;
    top: 18px;
    right: 18px;
    width: 32px;
    height: 32px;
    border-radius: 50%;
    color: var(--text-muted);
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .modal-close-btn:hover {
    color: #ffffff;
    background-color: rgba(255, 255, 255, 0.1);
  }

  .modal-header {
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
    margin-bottom: 20px;
  }

  .header-icon {
    width: 52px;
    height: 52px;
    border-radius: var(--radius-lg);
    background: rgba(0, 164, 220, 0.15);
    color: var(--jf-blue);
    display: flex;
    align-items: center;
    justify-content: center;
    margin-bottom: 14px;
    border: 1px solid rgba(0, 164, 220, 0.3);
  }

  .modal-title {
    font-size: 1.35rem;
    font-weight: 700;
    color: #ffffff;
    letter-spacing: -0.02em;
  }

  .modal-desc {
    font-size: 0.82rem;
    color: var(--text-secondary);
    margin-top: 6px;
    line-height: 1.45;
  }

  .tab-pill-group {
    display: flex;
    gap: 6px;
    background: var(--bg-surface);
    padding: 4px;
    border-radius: var(--radius-full);
    border: 1px solid var(--border);
    margin-top: 14px;
  }

  .tab-pill {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 6px 14px;
    border-radius: var(--radius-full);
    font-size: 0.78rem;
    font-weight: 600;
    color: var(--text-muted);
  }

  .tab-pill.active {
    background: var(--jf-blue);
    color: #fff;
  }

  .error-banner {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 14px;
    background-color: rgba(235, 87, 87, 0.12);
    border: 1px solid rgba(235, 87, 87, 0.3);
    color: var(--status-error);
    border-radius: var(--radius-md);
    font-size: 0.82rem;
    margin-bottom: 16px;
  }

  /* Saved list */
  .saved-modal-list {
    display: flex;
    flex-direction: column;
    gap: 10px;
    max-height: 300px;
    overflow-y: auto;
  }

  .saved-card {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px 14px;
    background: var(--bg-surface);
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    transition: all 0.15s ease;
  }

  .saved-card.active-account {
    border-color: var(--jf-blue);
    background: rgba(0, 164, 220, 0.08);
  }

  .saved-avatar {
    width: 38px;
    height: 38px;
    border-radius: 50%;
    background: linear-gradient(135deg, var(--jf-blue), var(--jf-purple));
    color: #fff;
    font-size: 0.85rem;
    font-weight: 700;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
  }

  .saved-meta {
    flex: 1;
    display: flex;
    flex-direction: column;
    min-width: 0;
    cursor: pointer;
  }

  .saved-user-row {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .saved-name {
    font-size: 0.92rem;
    font-weight: 700;
    color: #fff;
  }

  .active-badge {
    font-size: 0.62rem;
    font-weight: 800;
    color: var(--jf-blue);
    background: rgba(0, 164, 220, 0.2);
    padding: 1px 6px;
    border-radius: var(--radius-full);
  }

  .saved-server {
    font-size: 0.76rem;
    color: var(--jf-indigo);
  }

  .saved-url {
    font-size: 0.68rem;
    color: var(--text-muted);
    font-family: var(--font-mono);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .saved-actions {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .switch-btn {
    padding: 5px 12px;
    font-size: 0.78rem;
  }

  .del-btn {
    padding: 6px;
    color: var(--text-muted);
    border-radius: 50%;
  }

  .del-btn:hover {
    color: #e74c3c;
    background: rgba(231, 76, 60, 0.15);
  }

  .current-indicator {
    color: var(--jf-blue);
    padding: 6px;
  }

  /* Form */
  .connect-form {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .form-group {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .form-group label {
    font-size: 0.8rem;
    font-weight: 600;
    color: var(--text-secondary);
  }

  .input-wrapper {
    position: relative;
    display: flex;
    align-items: center;
  }

  :global(.input-icon) {
    position: absolute;
    left: 12px;
    color: var(--text-muted);
    pointer-events: none;
  }

  .input-wrapper input {
    width: 100%;
    height: 42px;
    padding: 0 14px 0 38px;
    background-color: var(--bg-surface);
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    color: #ffffff;
    font-size: 0.88rem;
    transition: all 0.15s ease;
  }

  .input-wrapper input:focus {
    outline: none;
    border-color: var(--jf-blue);
    background-color: rgba(255, 255, 255, 0.05);
    box-shadow: 0 0 0 3px rgba(0, 164, 220, 0.15);
  }

  .form-actions {
    display: flex;
    flex-direction: column;
    gap: 10px;
    margin-top: 8px;
  }

  .form-btn {
    width: 100%;
    height: 42px;
    border-radius: var(--radius-md);
    font-size: 0.9rem;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
  }
</style>
