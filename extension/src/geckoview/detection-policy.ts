/** Native-owned policy. Unknown sessions stay idle until their content handshake. */
export interface DetectionPolicy {
  type: "detection_policy";
  revision: number;
  enabled: boolean;
  browserEnabled: boolean;
  bridgedAppOrigins: string[];
}

export class TabDetectionPolicy {
  private revision = -1;
  private browserEnabled = false;
  private appOrigins = new Set<string>();
  private tabs = new Map<number, boolean>();

  apply(policy: DetectionPolicy, tabId?: number): boolean {
    if (policy.revision < this.revision) return false;
    if (policy.revision === this.revision &&
        (tabId == null || this.tabs.get(tabId) === policy.enabled)) return false;
    this.revision = policy.revision;
    this.browserEnabled = policy.browserEnabled === true;
    this.appOrigins = new Set(policy.bridgedAppOrigins);
    if (tabId != null) this.tabs.set(tabId, policy.enabled === true);
    return true;
  }

  allows(tabId: number): boolean {
    return this.browserEnabled && this.tabs.get(tabId) === true;
  }

  allowsRequest(tabId: number, requestType?: string, url?: string): boolean {
    if (tabId < 0 || !this.browserEnabled) return false;
    if (this.tabs.has(tabId)) return this.allows(tabId);
    // First main-frame responses precede content injection. Installed app origins
    // remain excluded until native session identity is known; subframes/resources
    // never use their own URLs to evade the owning tab's policy.
    if (requestType !== "main_frame" || !url) return false;
    try {
      const origin = new URL(url).origin;
      return !this.appOrigins.has(origin);
    } catch {
      return false;
    }
  }

  remove(tabId: number): void {
    this.tabs.delete(tabId);
  }
}
