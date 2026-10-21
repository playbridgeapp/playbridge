import { env } from '$env/dynamic/private';
import { fetchGithubStars } from '$lib/github';
import type { LayoutServerLoad } from './$types';

// One request per build, shared by every prerendered page.
let starsOnce: Promise<number | null> | undefined;

export const load: LayoutServerLoad = async () => {
  starsOnce ??= fetchGithubStars({ token: env.GITHUB_TOKEN }).then((stars) => {
    // Shows up in the Pages build log so a missing or unreadable token is easy to spot.
    const auth = env.GITHUB_TOKEN ? 'token present' : 'no GITHUB_TOKEN at build time';
    console.log(`[github] stars: ${stars ?? 'unavailable'} (${auth})`);
    return stars;
  });
  return { githubStars: await starsOnce };
};
