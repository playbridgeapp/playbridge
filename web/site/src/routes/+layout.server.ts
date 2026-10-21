import { env } from '$env/dynamic/private';
import { fetchGithubStars } from '$lib/github';
import type { LayoutServerLoad } from './$types';

// One request per build, shared by every prerendered page.
let starsOnce: Promise<number | null> | undefined;

export const load: LayoutServerLoad = async () => {
  starsOnce ??= fetchGithubStars({ token: env.GITHUB_TOKEN });
  return { githubStars: await starsOnce };
};
