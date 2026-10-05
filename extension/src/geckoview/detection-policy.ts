export interface DetectionOptions {
  videos: boolean;
  images: boolean;
  audio: boolean;
  subtitles: boolean;
  domScanning: boolean;
  networkDetection: boolean;
  responseScanning: boolean;
  navigationRescans: boolean;
  playerProbes: boolean;
  visibilityOverrides: boolean;
  detectInBridgedSites: boolean;
}

export function detectionOptions(options?: Partial<DetectionOptions>): DetectionOptions {
  return {
    videos: options?.videos !== false,
    images: options?.images !== false,
    audio: options?.audio !== false,
    subtitles: options?.subtitles !== false,
    domScanning: options?.domScanning !== false,
    networkDetection: options?.networkDetection !== false,
    responseScanning: options?.responseScanning !== false,
    navigationRescans: options?.navigationRescans !== false,
    playerProbes: options?.playerProbes !== false,
    visibilityOverrides: options?.visibilityOverrides !== false,
    detectInBridgedSites: options?.detectInBridgedSites === true,
  };
}

/** Native-owned policy. Unknown sessions stay idle until their content handshake. */
export interface DetectionPolicy {
  type: "detection_policy";
  revision: number;
  enabled: boolean;
  browserEnabled: boolean;
  bridgedAppOrigins: string[];
  options?: Partial<DetectionOptions>;
}

export const DETECTION_POLICY_PORT = "playbridge-detection-policy";

export function validDetectionPolicy(value: unknown): value is DetectionPolicy {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const policy = value as DetectionPolicy;
  return policy.type === "detection_policy" && Number.isSafeInteger(policy.revision) && policy.revision >= 0 &&
    typeof policy.enabled === "boolean" && typeof policy.browserEnabled === "boolean" &&
    Array.isArray(policy.bridgedAppOrigins) && policy.bridgedAppOrigins.every(origin => typeof origin === "string") &&
    (policy.options == null || (typeof policy.options === "object" && !Array.isArray(policy.options) &&
      Object.values(policy.options).every(option => typeof option === "boolean")));
}

export class TabDetectionPolicy {
  private revision = -1;
  private browserEnabled = false;
  private appOrigins = new Set<string>();
  private tabs = new Map<number, boolean>();
  options = detectionOptions();

  apply(policy: DetectionPolicy, tabId?: number): boolean {
    if (policy.revision < this.revision) return false;
    if (policy.revision === this.revision &&
        (tabId == null || this.tabs.get(tabId) === policy.enabled)) return false;
    this.revision = policy.revision;
    this.browserEnabled = policy.browserEnabled === true;
    this.appOrigins = new Set(policy.bridgedAppOrigins);
    this.options = detectionOptions(policy.options);
    if (tabId != null) this.tabs.set(tabId, policy.enabled === true);
    return true;
  }

  allows(tabId: number): boolean {
    return this.browserEnabled && this.tabs.get(tabId) === true;
  }

  scansResponses(): boolean {
    return this.options.responseScanning && (this.options.videos || this.options.audio || this.options.subtitles);
  }

  usesNetwork(): boolean {
    return this.options.networkDetection || this.scansResponses();
  }

  allowsMediaKind(kind?: string): boolean {
    switch (kind) {
      case "image": return this.options.images;
      case "audio": return this.options.audio;
      case "subtitle": return this.options.subtitles;
      default: return this.options.videos;
    }
  }

  allowsRequest(tabId: number, requestType?: string, url?: string): boolean {
    if (tabId < 0 || !this.browserEnabled) return false;
    // A newly recognized origin takes precedence over a tab's previous document
    // policy, including main-frame requests that race the next content handshake.
    if (requestType === "main_frame" && url) {
      try {
        if (!this.options.detectInBridgedSites && this.appOrigins.has(new URL(url).origin)) return false;
      } catch { return false; }
    }
    if (this.tabs.has(tabId)) return this.allows(tabId);
    // First main-frame responses precede content injection. Installed app origins
    // remain excluded until native session identity is known; subframes/resources
    // never use their own URLs to evade the owning tab's policy.
    if (requestType !== "main_frame" || !url) return false;
    try {
      const origin = new URL(url).origin;
      return this.options.detectInBridgedSites || !this.appOrigins.has(origin);
    } catch {
      return false;
    }
  }

  remove(tabId: number): void {
    this.tabs.delete(tabId);
  }
}
