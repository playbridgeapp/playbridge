export type Theme = 'light' | 'dark';
export type ThemePref = 'auto' | Theme;

const KEY = 'pb-theme';

export function readPref(): ThemePref {
  if (typeof localStorage === 'undefined') return 'auto';
  try {
    const stored = localStorage.getItem(KEY);
    if (stored === 'light' || stored === 'dark' || stored === 'auto') return stored;
  } catch {
    /* private mode */
  }
  return 'auto';
}

export function systemTheme(): Theme {
  if (typeof window === 'undefined') return 'dark';
  return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
}

export function effectiveTheme(pref: ThemePref = readPref()): Theme {
  return pref === 'auto' ? systemTheme() : pref;
}

export function applyPref(pref: ThemePref) {
  if (typeof document === 'undefined') return;
  const theme = effectiveTheme(pref);
  document.documentElement.dataset.theme = theme;
  document.documentElement.dataset.themePref = pref;
  document.documentElement.style.colorScheme = theme;
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute('content', theme === 'light' ? '#f6f3ec' : '#06091e');
  try {
    localStorage.setItem(KEY, pref);
  } catch {
    /* private mode */
  }
}

export function cyclePref(): ThemePref {
  const order: ThemePref[] = ['auto', 'dark', 'light'];
  const next = order[(order.indexOf(readPref()) + 1) % order.length];
  applyPref(next);
  return next;
}

export function currentTheme(): Theme {
  if (typeof document === 'undefined') return 'dark';
  return document.documentElement.dataset.theme === 'light' ? 'light' : 'dark';
}
