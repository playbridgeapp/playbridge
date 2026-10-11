/**
 * Origin normalisation and display formatting for website page API.
 */

export function normalizeOrigin(value: unknown): string | null {
  if (typeof value !== "string" || !value.trim()) return null;
  try {
    const url = new URL(value);
    if (url.protocol !== "http:" && url.protocol !== "https:") return null;
    if (url.username || url.password) return null;
    if (url.pathname !== "" && url.pathname !== "/") return null;
    if (url.search || url.hash) return null;
    const hostname = url.hostname.toLowerCase();
    if (!hostname) return null;
    const port = url.port;
    const isDefaultPort =
      (url.protocol === "http:" && (port === "" || port === "80")) ||
      (url.protocol === "https:" && (port === "" || port === "443"));
    return `${url.protocol}//${hostname}${isDefaultPort ? "" : `:${port}`}`;
  } catch {
    return null;
  }
}

export function displayOrigin(origin: string): string {
  try {
    const url = new URL(origin);
    return url.port ? `${url.hostname}:${url.port}` : url.hostname;
  } catch {
    return origin;
  }
}
