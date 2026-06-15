# EngineMiddleware Migration (Fenix-style session ownership)

Date: 2026-06-11. Not yet compiled/tested on-device — run a build first.

## What changed

`BrowserStore` is now created with `EngineMiddleware.create(engine, trimMemoryAutomatically = false)`
(`Components.kt`). The middleware owns the engine-session lifecycle:

- `EngineAction.CreateEngineSessionAction(tabId)` creates a session, restores
  `tab.engineState.engineSessionState` automatically, or loads the tab URL when
  there is no state (LinkingMiddleware).
- `EngineAction.SuspendEngineSessionAction(tabId)` hibernates a tab (state kept in store).
- Tab removal closes sessions (TabsRemovedMiddleware).
- Content-process crash/kill marks the tab crashed / suspends it (CrashMiddleware).
- EngineObserver syncs URL/title/back-forward/session-state into the store for
  EVERY tab (fixes the stale-background-tab-URL bug).

`TabManager` no longer creates, restores, reloads, or closes sessions. It now:

- mirrors `engineState.engineSession` → `sessions` map and `content.canGoBack/Forward`
  → `navigationStates` for Compose consumers (`start(store)`, called from
  `Components.initialize`),
- ensures the selected tab always has a live session: dispatches
  `CreateEngineSessionAction` when missing, `CrashAction.RestoreCrashedSessionAction`
  when crashed (automatic white-page recovery),
- keeps the LRU and hibernates evicted tabs via `SuspendEngineSessionAction`,
- serializes state with public APIs: `EngineSessionState.writeTo(JsonWriter)` /
  `GeckoEngineSessionState.fromJSON` — including a read-shim for legacy DB rows
  (raw gecko JSON without the `GECKO_STATE` envelope).

Deleted: `savedStates`, `engineStates`, `justRestored`, `needsReloadOnSelect`,
restore-then-reload hacks, all state-serialization reflection
(`getActualState`, `GeckoEngineSessionState` ctor), manual per-tab observers,
`handleCrashedSession`. Find-in-page now uses `EngineSession.findAll/findNext`.

Popups (`SessionObserverSetup` + BrowserActivity allow-path) link their session via
`EngineAction.LinkEngineSessionAction(tabId, session, skipLoading = true)`.

## Verify after building

1. Fresh start restores tabs from DB with history (legacy rows + new rows).
2. Tab switch beyond max-alive-tabs hibernates and restores with history, no reload flash.
3. Background kill (developer options → no background processes) → reselect tab → page restores.
4. Popup open (allowed + blocked-then-allowed) gets URL/title sync and persistence.
5. Close tab → reopen closed tab restores history.
6. Back/forward buttons reflect store state after restore.

## Known follow-ups

- Crash storms: recovery is automatic with no retry limit; if a page crashes the
  content process deterministically, the selected tab could loop
  (restore → create → crash). Consider a per-tab retry cap + error UI.
- `getGeckoSession` reflection remains (delegate proxies in SessionObserverSetup).
- Old `TabEntity.sessionState` rows are migrated on read; new writes use the AC format.
