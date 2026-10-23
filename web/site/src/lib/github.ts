/**
 * Build-time GitHub proof: the real stargazer count for the PlayBridge repo.
 *
 * Fetched once while prerendering (see `src/routes/+layout.server.ts`) and baked into
 * the static pages. Any failure — offline build, rate limit, bad token, slow API —
 * resolves to `null`, and the UI then renders nothing. Never hardcode a fallback number.
 */

export const GITHUB_REPO_API = 'https://api.github.com/repos/playbridgeapp/PlayBridge';

const TIMEOUT_MS = 5000;

type FetchLike = typeof fetch;

async function requestStars(
  fetchFn: FetchLike,
  url: string,
  token: string | undefined
): Promise<number | null> {
  const headers: Record<string, string> = {
    accept: 'application/vnd.github+json',
    'user-agent': 'playbridge-site-build',
    'x-github-api-version': '2022-11-28'
  };
  if (token) headers.authorization = `Bearer ${token}`;

  const res = await fetchFn(url, { headers, signal: AbortSignal.timeout(TIMEOUT_MS) });
  if (!res.ok) {
    // A stale or wrong CI token should not cost us the unauthenticated quota.
    if (token && res.status === 401) return requestStars(fetchFn, url, undefined);
    return null;
  }
  const json = (await res.json()) as { stargazers_count?: unknown };
  const n = json.stargazers_count;
  return typeof n === 'number' && Number.isFinite(n) && n >= 0 ? n : null;
}

/** Stargazer count, or `null` when GitHub cannot be reached in time. Never throws. */
export async function fetchGithubStars(
  options: { token?: string; url?: string; fetch?: FetchLike } = {}
): Promise<number | null> {
  const { token, url = GITHUB_REPO_API, fetch: fetchFn = fetch } = options;
  try {
    return await requestStars(fetchFn, url, token || undefined);
  } catch {
    return null;
  }
}

/** `369` → "369", `1234` → "1.2k", `12000` → "12k". */
export function formatStars(n: number): string {
  if (n < 1000) return String(n);
  const k = n / 1000;
  const rounded = k >= 100 ? Math.round(k) : Math.round(k * 10) / 10;
  return `${String(rounded).replace(/\.0$/, '')}k`;
}
