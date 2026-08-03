export interface DetectorPageVersion {
  detectorEpoch: number;
  navigationGeneration: number;
}

interface PendingMainFrameDetection<T> {
  url: string;
  value: T;
}

function documentUrl(url: string): string {
  return url.split("#")[0];
}

export function shouldStageMainFrameDetection(
  requestType: string,
  requestUrl: string,
  committedUrl: string | undefined,
  navigationPending: boolean,
): boolean {
  return requestType === "main_frame" &&
    (navigationPending ||
      !committedUrl ||
      documentUrl(committedUrl) !== documentUrl(requestUrl));
}

/**
 * Holds top-level response detections until Gecko confirms that their document
 * replaced the current page. A failed or superseded navigation can therefore
 * be discarded without erasing media owned by the still-active document.
 */
export class MainFrameDetectionGate<T> {
  private readonly navigatingTabs = new Set<number>();
  private readonly detections = new Map<number, PendingMainFrameDetection<T>[]>();

  begin(tabId: number): void {
    this.navigatingTabs.add(tabId);
    this.detections.delete(tabId);
  }

  isNavigating(tabId: number): boolean {
    return this.navigatingTabs.has(tabId);
  }

  stage(tabId: number, url: string, value: T): void {
    const pending = this.detections.get(tabId) ?? [];
    pending.push({ url, value });
    this.detections.set(tabId, pending);
  }

  commit(tabId: number, committedUrl: string): T[] {
    this.navigatingTabs.delete(tabId);
    const pending = this.detections.get(tabId) ?? [];
    this.detections.delete(tabId);
    const committedDocumentUrl = documentUrl(committedUrl);
    return pending
      .filter((candidate) => documentUrl(candidate.url) === committedDocumentUrl)
      .map((candidate) => candidate.value);
  }

  abort(tabId: number): void {
    this.navigatingTabs.delete(tabId);
    this.detections.delete(tabId);
  }
}

/** Advance a tab to a new top-level document generation. */
export function advanceNavigationGeneration(
  generations: Map<number, number>,
  tabId: number,
): number {
  const next = (generations.get(tabId) ?? 0) + 1;
  generations.set(tabId, next);
  return next;
}

/** Return the document generation currently accepting detections for a tab. */
export function currentNavigationGeneration(
  generations: ReadonlyMap<number, number>,
  tabId: number,
): number {
  return generations.get(tabId) ?? 0;
}

/** Reject asynchronous response-body results belonging to an older document. */
export function isCurrentNavigationGeneration(
  generations: ReadonlyMap<number, number>,
  tabId: number,
  generation: number,
): boolean {
  return currentNavigationGeneration(generations, tabId) === generation;
}

/**
 * Main-document headers can arrive before webNavigation.onCommitted. Once the
 * same URL commits, its bounded response-body result belongs to the new page;
 * subresource results keep the generation captured when their request started.
 */
export function responseBodyNavigationGeneration(
  capturedGeneration: number,
  currentGeneration: number,
  requestType: string,
  requestUrl: string,
  committedUrl?: string,
): number {
  if (requestType !== "main_frame" || !committedUrl) return capturedGeneration;
  return documentUrl(requestUrl) === documentUrl(committedUrl)
    ? currentGeneration
    : capturedGeneration;
}
