/**
 * GeckoView / Firefox WebExtension API access.
 * Phone detector runs inside GeckoView; `browser` is injected by the runtime.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const b = (globalThis as any).browser ?? (globalThis as any).chrome;
export default b as typeof browser;
