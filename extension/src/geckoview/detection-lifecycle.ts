export interface DetectorPageVersion {
  detectorEpoch: number;
  navigationGeneration: number;
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
  return requestUrl.split("#")[0] === committedUrl.split("#")[0]
    ? currentGeneration
    : capturedGeneration;
}
