export const prerender = true;
export const trailingSlash = 'never';

// Layout data (`githubStars`) comes from `+layout.server.ts`, which runs only while
// prerendering. It is deliberately not fetched here: a universal load re-runs in the
// visitor's browser on hydration and would call the GitHub API from their IP.
