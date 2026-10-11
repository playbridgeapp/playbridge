import browser from "./browser";
import * as bridge from "./native-bridge";
import { normalizeOrigin, originFromUrl } from "./core/page-origin";
import {
  getOrCreateInstallSecret,
  formatPlaybackDestination,
  destinationIdMatches,
} from "./core/page-privacy";
import {
  isOriginApproved,
  approveOrigin,
  revokeOrigin,
  getApprovedPrivateOrigins,
  getApprovedOrigins,
} from "./core/page-consent";
import {
  PAGE_API_PORT,
  validPageRequestId,
} from "./core/page-relay";
import {
  normalizePageCastPayload,
  normalizeLinkedPageCastPayload,
  normalizeLinkedAppendPayload,
  normalizeLinkedJumpPayload,
  normalizeLinkedSupplyPayload,
  pageCastHasSenderOnlyFields,
  pageCastRequestWithinLimit,
} from "./core/page-cast";

export interface ActiveWebsiteSession {
  sessionId: string;
  tabId: number;
  frameId: number;
  origin: string;
  navigationGeneration: number;
  port: any;
}

export class PageCoordinator {
  private activeSession: ActiveWebsiteSession | null = null;
  private tabNavGenerations = new Map<number, number>();
  private pagePorts = new Set<any>();
  private pendingConsentPrompts = new Map<
    string,
    {
      resolve: (allowed: boolean) => void;
      windowId?: number;
    }
  >();
  private fallbackTabMap = new Map<string, number>();
  private portFallbackTabMap = new WeakMap<object, number>();
  private nextFallbackTabId = 1_000_000;
  private unsubscribeBridgeState?: () => void;
  private unsubscribeLinkedEvents?: () => void;

  constructor() {
    this.init();
  }

  private init(): void {
    // Port listener for page scripts
    browser.runtime.onConnect.addListener((port) => {
      if (port.name === PAGE_API_PORT) {
        this.handlePagePortConnect(port);
      }
    });

    // Listen to bridge state changes to broadcast capabilities updates
    this.unsubscribeBridgeState = bridge.onState((state) => {
      this.broadcastCapabilities();
      // If receiver disconnected or changed, check if active session target changed
      if (
        this.activeSession &&
        !state.desktopConnected
      ) {
        this.endActiveSession("receiver_stopped", false);
      }
    });

    // Listen to linked wire events from app
    this.unsubscribeLinkedEvents = bridge.onLinkedEvent(
      (sessionId, event, detail) => {
        if (this.activeSession && this.activeSession.sessionId === sessionId) {
          try {
            this.activeSession.port.postMessage({
              type: "event",
              sessionId,
              event,
              detail,
            });
          } catch {
            // Port might be closed
          }
          if (event === "ended") {
            this.activeSession = null;
          }
        }
      }
    );

    // Track top-frame navigation commits
    if (browser.webNavigation && browser.webNavigation.onCommitted) {
      browser.webNavigation.onCommitted.addListener((details) => {
        if (details.frameId === 0) {
          const currentGen = (this.tabNavGenerations.get(details.tabId) ?? 0) + 1;
          this.tabNavGenerations.set(details.tabId, currentGen);

          if (this.activeSession && this.activeSession.tabId === details.tabId) {
            this.endActiveSession("navigation", true);
          }
        }
      });
    }

    // Track tab closures
    if (browser.tabs && browser.tabs.onRemoved) {
      browser.tabs.onRemoved.addListener((tabId) => {
        this.tabNavGenerations.delete(tabId);
        if (this.activeSession && this.activeSession.tabId === tabId) {
          this.endActiveSession("navigation", true);
        }
      });
    }

    // Track window close for consent prompt (closing = deny)
    if (browser.windows && browser.windows.onRemoved) {
      browser.windows.onRemoved.addListener((windowId) => {
        for (const [origin, pending] of this.pendingConsentPrompts) {
          if (pending.windowId === windowId) {
            this.pendingConsentPrompts.delete(origin);
            pending.resolve(false);
          }
        }
      });
    }
  }

  public destroy(): void {
    this.unsubscribeBridgeState?.();
    this.unsubscribeLinkedEvents?.();
    this.endActiveSession("unlinked", false);
    this.fallbackTabMap.clear();
  }

  public getActiveSession(): ActiveWebsiteSession | null {
    return this.activeSession;
  }

  public isLinkedPlaybackAvailable(): boolean {
    const state = bridge.getState();
    return Boolean(state.desktopConnected && state.capabilities?.linkedPlayback === 1);
  }

  private resolveTabId(sender: any, port: any): number {
    if (typeof sender?.tab?.id === "number" && sender.tab.id >= 0) {
      return sender.tab.id;
    }
    const key =
      (typeof sender?.documentId === "string" && sender.documentId) ||
      (typeof sender?.url === "string" && sender.url) ||
      (typeof sender?.tab?.url === "string" && sender.tab.url) ||
      "";
    if (key) {
      let id = this.fallbackTabMap.get(key);
      if (id === undefined) {
        id = this.nextFallbackTabId++;
        this.fallbackTabMap.set(key, id);
      }
      return id;
    }
    if (port && typeof port === "object") {
      let id = this.portFallbackTabMap.get(port);
      if (id === undefined) {
        id = this.nextFallbackTabId++;
        this.portFallbackTabMap.set(port, id);
      }
      return id;
    }
    return -1;
  }

  private broadcastCapabilities(): void {
    const available = this.isLinkedPlaybackAvailable();
    const msg = {
      type: "capabilities",
      capabilities: { playback: available ? 1 : 0 },
    };
    for (const port of this.pagePorts) {
      try {
        port.postMessage(msg);
      } catch {
        this.pagePorts.delete(port);
      }
    }
  }

  private handlePagePortConnect(port: any): void {
    const sender = port.sender;
    if (sender?.frameId != null && sender.frameId !== 0) {
      port.disconnect();
      return;
    }

    this.pagePorts.add(port);

    // Send current capabilities immediately
    const available = this.isLinkedPlaybackAvailable();
    try {
      port.postMessage({
        type: "capabilities",
        capabilities: { playback: available ? 1 : 0 },
      });
    } catch {
      // Ignored
    }

    port.onDisconnect.addListener(() => {
      this.pagePorts.delete(port);
      if (this.activeSession && this.activeSession.port === port) {
        this.endActiveSession("navigation", true);
      }
      const key =
        (typeof sender?.documentId === "string" && sender.documentId) ||
        (typeof sender?.url === "string" && sender.url) ||
        (typeof sender?.tab?.url === "string" && sender.tab.url) ||
        "";
      if (key) {
        this.fallbackTabMap.delete(key);
      }
    });

    port.onMessage.addListener(async (raw: unknown) => {
      if (!raw || typeof raw !== "object") return;
      const msg = raw as Record<string, unknown>;

      const tabId = this.resolveTabId(sender, port);
      const url = sender?.url ?? sender?.tab?.url ?? "";
      const origin = originFromUrl(url);

      if (msg.type === "cast") {
        if (tabId < 0 || !origin) return;
        await this.handleLegacyCast(msg, origin);
        return;
      }

      if (msg.type === "linked") {
        const rawPageRequestId = msg.pageRequestId;
        if (rawPageRequestId !== undefined && !validPageRequestId(rawPageRequestId)) {
          try {
            port.postMessage({
              type: "response",
              pageRequestId: String(rawPageRequestId ?? ""),
              response: { ok: false, error: "invalid_request" },
            });
          } catch {
            // Port disconnected
          }
          return;
        }

        if (!validPageRequestId(rawPageRequestId)) {
          return;
        }

        const pageRequestId = rawPageRequestId;

        if (tabId < 0 || !origin) {
          try {
            port.postMessage({
              type: "response",
              pageRequestId,
              response: { ok: false, error: "unavailable" },
            });
          } catch {
            // Port disconnected
          }
          return;
        }

        let response: Record<string, unknown>;
        try {
          response = await this.handleLinkedRequest(msg, origin, tabId, port);
        } catch {
          response = { ok: false, error: "unavailable" };
        }



        try {
          port.postMessage({
            type: "response",
            pageRequestId,
            response,
          });
        } catch {
          // Port disconnected
        }
      }
    });
  }

  public endActiveSession(reason: string, notifyNative = true): void {
    if (!this.activeSession) return;
    const session = this.activeSession;
    this.activeSession = null;

    try {
      session.port.postMessage({
        type: "event",
        sessionId: session.sessionId,
        event: "ended",
        detail: { reason },
      });
    } catch {
      // Port may be gone
    }

    if (notifyNative) {
      bridge
        .sendLinkedCommand(
          {
            type: "linked_unlink",
            sessionId: session.sessionId,
            reason,
          },
          10_000,
        )
        .catch(() => {});
    }
  }

  public async promptConsent(origin: string): Promise<boolean> {
    const norm = originFromUrl(origin);
    if (!norm) return false;

    const existing = this.pendingConsentPrompts.get(norm);
    if (existing) {
      return new Promise<boolean>((resolve) => {
        const prevResolve = existing.resolve;
        existing.resolve = (allowed) => {
          prevResolve(allowed);
          resolve(allowed);
        };
      });
    }

    return new Promise<boolean>(async (resolve) => {
      const entry = { resolve, windowId: undefined as number | undefined };
      this.pendingConsentPrompts.set(norm, entry);

      try {
        const popupUrl = browser.runtime.getURL(
          `ui/page-access.html?origin=${encodeURIComponent(norm)}&mode=consent`,
        );
        const win = await browser.windows.create({
          url: popupUrl,
          type: "popup",
          width: 420,
          height: 560,
        });
        entry.windowId = win.id;
      } catch (err) {
        this.pendingConsentPrompts.delete(norm);
        resolve(false);
      }
    });
  }

  public getPendingConsentPromptWindowId(origin: string): number | undefined {
    const norm = originFromUrl(origin);
    if (!norm) return undefined;
    return this.pendingConsentPrompts.get(norm)?.windowId;
  }

  public resolveConsentPrompt(origin: string, allowed: boolean): void {
    const norm = originFromUrl(origin);
    if (!norm) return;
    const entry = this.pendingConsentPrompts.get(norm);
    if (entry) {
      this.pendingConsentPrompts.delete(norm);
      entry.resolve(allowed);
    }
  }

  public async openPicker(origin: string): Promise<void> {
    const norm = originFromUrl(origin) ?? origin;
    try {
      const popupUrl = browser.runtime.getURL(
        `ui/page-access.html?origin=${encodeURIComponent(norm)}&mode=picker`,
      );
      await browser.windows.create({
        url: popupUrl,
        type: "popup",
        width: 420,
        height: 560,
      });
    } catch {
      // Ignore window creation failure
    }
  }

  public async revokeWebsiteConsent(origin: string): Promise<void> {
    const norm = normalizeOrigin(origin);
    if (!norm) return;
    await revokeOrigin(browser.storage.local, norm);
    if (this.activeSession && this.activeSession.origin === norm) {
      this.endActiveSession("permission_reset", true);
    }
  }

  private async handleLegacyCast(
    msg: Record<string, unknown>,
    origin: string,
  ): Promise<void> {
    const payload = msg.payload;
    if (pageCastHasSenderOnlyFields(payload) || pageCastHasSenderOnlyFields(msg)) {
      return;
    }
    const normalized = normalizePageCastPayload(payload);
    if (!normalized || normalized.items.length === 0) return;

    let consented = await isOriginApproved(browser.storage.local, origin);
    if (!consented) {
      // Prompt only with attested gesture, else drop silently
      if (msg.userActivation !== true) return;
      const allowed = await this.promptConsent(origin);
      if (!allowed) return;
      await approveOrigin(browser.storage.local, origin);
    }

    const firstItem = normalized.items[normalized.startIndex || 0];
    await bridge.cast(
      firstItem.url,
      firstItem.headers,
      firstItem.title,
      firstItem.contentType,
    );
  }

  private async handleLinkedRequest(
    msg: Record<string, unknown>,
    origin: string,
    tabId: number,
    port: any,
  ): Promise<Record<string, unknown>> {
    const operation = String(msg.operation ?? "");
    const sessionId = typeof msg.sessionId === "string" ? msg.sessionId : null;
    const rawPayload = msg.payload;
    const userActivation = msg.userActivation as boolean | null | undefined;
    const currentGen = this.tabNavGenerations.get(tabId) ?? 0;

    // Check payload sender-only fields (progressWebhook)
    if (
      pageCastHasSenderOnlyFields(msg) ||
      pageCastHasSenderOnlyFields(rawPayload)
    ) {
      return {
        ok: false,
        error: "invalid_request",
        message: "progressWebhook is not available to websites",
      };
    }

    // Check payload size
    if (rawPayload && !pageCastRequestWithinLimit(rawPayload)) {
      return { ok: false, error: "resource_limit" };
    }

    // Destination queries
    if (operation === "destination") {
      if (!this.isLinkedPlaybackAvailable()) {
        return { ok: false, error: "unavailable" };
      }
      const consented = await isOriginApproved(browser.storage.local, origin);
      const secret = await getOrCreateInstallSecret(browser.storage.local);
      const dest = await formatPlaybackDestination(
        bridge.getState(),
        origin,
        consented,
        secret,
      );
      return { ok: true, destination: dest };
    }

    if (operation === "choose_destination") {
      if (!this.isLinkedPlaybackAvailable()) {
        return { ok: false, error: "unavailable" };
      }

      // Gesture attestation must be positive (choose_destination always requires userActivation === true)
      if (userActivation !== true) {
        return { ok: false, error: "user_gesture_required" };
      }

      const payloadObj = (rawPayload && typeof rawPayload === "object" ? rawPayload : {}) as Record<string, unknown>;
      const hasDestinationId = payloadObj.destinationId != null;
      const destinationId = payloadObj.destinationId;

      if (hasDestinationId && destinationId !== "this-device") {
        return { ok: false, error: "invalid_request" };
      }

      let consented = await isOriginApproved(browser.storage.local, origin);
      if (!consented) {
        const allowed = await this.promptConsent(origin);
        if (!allowed) {
          return { ok: false, error: "not_allowed" };
        }
        await approveOrigin(browser.storage.local, origin);
        consented = true;
      }

      // Re-read destination state after consent prompt
      const st = bridge.getState();
      const liveReceiverConnected =
        st.selection?.kind === "tv" && st.selection?.status === "connected";

      if (hasDestinationId) {
        if (liveReceiverConnected) {
          // Never disconnect a live TV; open the picker instead
          void this.openPicker(origin);
        } else {
          await bridge.selectLocal();
        }
      } else {
        void this.openPicker(origin);
      }

      // Respond immediately with the CURRENT destination
      const secret = await getOrCreateInstallSecret(browser.storage.local);
      const currentDest = await formatPlaybackDestination(
        bridge.getState(),
        origin,
        true,
        secret,
      );
      return { ok: true, destination: currentDest };
    }

    // Playback operations
    if (operation === "play") {
      if (!this.isLinkedPlaybackAvailable()) {
        return { ok: false, error: "unavailable" };
      }

      const norm = normalizeLinkedPageCastPayload(rawPayload);
      const payloadObj = (rawPayload && typeof rawPayload === "object" ? rawPayload : {}) as Record<string, unknown>;
      const destinationId = payloadObj.destinationId;

      if (!norm || typeof destinationId !== "string" || !destinationId || destinationId.length > 256) {
        return { ok: false, error: "invalid_request" };
      }

      const consented = await isOriginApproved(browser.storage.local, origin);
      if (!consented) {
        return { ok: false, error: "not_allowed" };
      }

      const secret = await getOrCreateInstallSecret(browser.storage.local);
      const state = bridge.getState();
      const matches = await destinationIdMatches(destinationId, origin, state.selection, secret);
      if (!matches) {
        return { ok: false, error: "destination_changed" };
      }

      const targetEndpoint =
        !state.selection || state.selection.kind === "local"
          ? "local"
          : state.selection.id;
      const allowedPrivateOrigins = await getApprovedPrivateOrigins(
        browser.storage.local,
        origin,
      );

      const wireMsg = {
        type: "linked_play",
        origin,
        destination: targetEndpoint,
        payload: {
          items: norm.items,
          startIndex: norm.startIndex,
          ...(norm.metadata ? { metadata: norm.metadata } : {}),
          ...(norm.skipPreplay != null ? { skipPreplay: norm.skipPreplay } : {}),
        },
        allowedPrivateOrigins,
      };

      const result = await bridge.sendLinkedCommand(wireMsg, 600_000);
      if (!result.ok) {
        return result;
      }

      // Supersede old session if exists
      if (this.activeSession) {
        bridge
          .sendLinkedCommand(
            {
              type: "linked_unlink",
              sessionId: this.activeSession.sessionId,
              reason: "superseded",
            },
            10_000,
          )
          .catch(() => {});
        this.activeSession = null;
      }

      const newSessionId = String(result.sessionId);
      this.activeSession = {
        sessionId: newSessionId,
        tabId,
        frameId: 0,
        origin,
        navigationGeneration: currentGen,
        port,
      };

      return { ok: true, sessionId: newSessionId };
    }

    if (operation === "open") {
      if (!this.isLinkedPlaybackAvailable()) {
        return { ok: false, error: "unavailable" };
      }

      const norm = normalizeLinkedPageCastPayload(rawPayload);
      if (!norm) {
        return { ok: false, error: "invalid_request" };
      }

      let consented = await isOriginApproved(browser.storage.local, origin);
      if (!consented) {
        if (userActivation !== true) {
          return { ok: false, error: "user_gesture_required" };
        }
        const allowed = await this.promptConsent(origin);
        if (!allowed) {
          return { ok: false, error: "not_allowed" };
        }
        await approveOrigin(browser.storage.local, origin);
        consented = true;
      }

      const allowedPrivateOrigins = await getApprovedPrivateOrigins(
        browser.storage.local,
        origin,
      );

      const wireMsg = {
        type: "linked_open",
        origin,
        payload: {
          items: norm.items,
          startIndex: norm.startIndex,
          ...(norm.metadata ? { metadata: norm.metadata } : {}),
          ...(norm.skipPreplay != null ? { skipPreplay: norm.skipPreplay } : {}),
        },
        allowedPrivateOrigins,
      };

      const result = await bridge.sendLinkedCommand(wireMsg, 600_000);
      if (!result.ok) {
        return result;
      }

      if (this.activeSession) {
        bridge
          .sendLinkedCommand(
            {
              type: "linked_unlink",
              sessionId: this.activeSession.sessionId,
              reason: "superseded",
            },
            10_000,
          )
          .catch(() => {});
        this.activeSession = null;
      }

      const newSessionId = String(result.sessionId);
      this.activeSession = {
        sessionId: newSessionId,
        tabId,
        frameId: 0,
        origin,
        navigationGeneration: currentGen,
        port,
      };

      return { ok: true, sessionId: newSessionId };
    }

    // Bound session operations
    if (!sessionId || !this.activeSession || this.activeSession.sessionId !== sessionId) {
      return { ok: false, error: "session_ended" };
    }

    if (
      this.activeSession.tabId !== tabId ||
      this.activeSession.origin !== origin ||
      this.activeSession.navigationGeneration !== currentGen ||
      this.activeSession.port !== port
    ) {
      return { ok: false, error: "session_ended" };
    }

    const consented = await isOriginApproved(browser.storage.local, origin);
    if (!consented) {
      this.endActiveSession("permission_reset", true);
      return { ok: false, error: "not_allowed" };
    }

    if (operation === "ping") {
      const res = await bridge.sendLinkedCommand({
        type: "linked_ping",
        sessionId,
      });
      return res.ok ? { ok: true } : res;
    }

    if (operation === "unlink") {
      this.endActiveSession("unlinked", true);
      return { ok: true };
    }

    if (operation === "replace") {
      const norm = normalizeLinkedPageCastPayload(rawPayload);
      if (!norm) return { ok: false, error: "invalid_request" };
      const allowedPrivateOrigins = await getApprovedPrivateOrigins(
        browser.storage.local,
        origin,
      );
      return bridge.sendLinkedCommand({
        type: "linked_replace",
        sessionId,
        payload: {
          items: norm.items,
          startIndex: norm.startIndex,
          ...(norm.metadata ? { metadata: norm.metadata } : {}),
        },
        allowedPrivateOrigins,
      });
    }

    if (operation === "append") {
      const norm = normalizeLinkedAppendPayload(rawPayload);
      if (!norm) return { ok: false, error: "invalid_request" };
      const allowedPrivateOrigins = await getApprovedPrivateOrigins(
        browser.storage.local,
        origin,
      );
      return bridge.sendLinkedCommand({
        type: "linked_append",
        sessionId,
        payload: { items: norm.items },
        allowedPrivateOrigins,
      });
    }

    if (operation === "jump") {
      const norm = normalizeLinkedJumpPayload(rawPayload);
      if (!norm) return { ok: false, error: "invalid_request" };
      return bridge.sendLinkedCommand({
        type: "linked_jump",
        sessionId,
        payload: { index: norm.index },
      });
    }

    if (operation === "supply") {
      const norm = normalizeLinkedSupplyPayload(rawPayload);
      if (!norm) return { ok: false, error: "invalid_request" };
      const allowedPrivateOrigins = await getApprovedPrivateOrigins(
        browser.storage.local,
        origin,
      );
      return bridge.sendLinkedCommand({
        type: "linked_supply",
        sessionId,
        payload: {
          requestId: norm.requestId,
          items: norm.items,
          endOfList: norm.endOfList,
        },
        allowedPrivateOrigins,
      });
    }

    return { ok: false, error: "unsupported_operation" };
  }
}

export let pageCoordinator: PageCoordinator | null = null;

export function initPageCoordinator(): PageCoordinator {
  if (!pageCoordinator) {
    pageCoordinator = new PageCoordinator();
  }
  return pageCoordinator;
}

export function isExtensionPageSender(sender?: { url?: string }): boolean {
  try {
    const extBase = browser.runtime.getURL("");
    return typeof sender?.url === "string" && sender.url.startsWith(extBase);
  } catch {
    return false;
  }
}

export function handlePrivilegedRuntimeMessage(
  msg: Record<string, unknown>,
  sender: { url?: string; tab?: { id?: number; windowId?: number } },
  sendResponse: (res: unknown) => void,
  coordInstance?: PageCoordinator,
): true {
  if (!isExtensionPageSender(sender)) {
    return true;
  }

  if (msg.action === "pageAccessConsentResponse") {
    const origin = msg.origin as string;
    const coord = coordInstance ?? initPageCoordinator();
    const pendingWindowId = coord.getPendingConsentPromptWindowId(origin);
    if (
      pendingWindowId == null ||
      sender.tab?.windowId == null ||
      sender.tab.windowId !== pendingWindowId
    ) {
      return true;
    }
    const allowed = Boolean(msg.allowed);
    coord.resolveConsentPrompt(origin, allowed);
    sendResponse({ ok: true });
    return true;
  }

  if (msg.action === "getPageConsentOrigins") {
    getApprovedOrigins(browser.storage.local)
      .then((origins) => sendResponse({ origins }))
      .catch(() => sendResponse({ origins: [] }));
    return true;
  }

  if (msg.action === "revokePageConsentOrigin") {
    const origin = msg.origin as string;
    const coord = coordInstance ?? initPageCoordinator();
    coord
      .revokeWebsiteConsent(origin)
      .then(() => sendResponse({ ok: true }))
      .catch(() => sendResponse({ ok: false }));
    return true;
  }

  return true;
}
