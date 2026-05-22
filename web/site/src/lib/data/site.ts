export const SITE = {
  name: 'PlayBridge',
  tagline: 'Your phone. Your TV. One bridge.',
  description:
    'PlayBridge is an open-source casting suite in active development. Browse on your phone, watch on your TV — no accounts, no telemetry, local network only.',
  url: 'https://playbridge.app',
  ogImage: '/og-image.png',
  twitter: '@playbridge',
  email: 'playbridgeapp@gmail.com',
  github: 'https://github.com/playbridgeapp/PlayBridge',
  githubOrg: 'https://github.com/playbridgeapp',
  version: 'v2.4.1'
};

export type Platform = {
  icon: 'android' | 'firefox' | 'tv' | 'apple' | 'desktop';
  name: string;
  desc: string;
};

export const SENDERS: Platform[] = [
  { icon: 'android', name: 'Android app', desc: 'Browse, search, and send anything to a player.' },
  { icon: 'firefox', name: 'Firefox extension', desc: 'Send links from any tab — right-click → cast.' }
];

export const PLAYERS: Platform[] = [
  { icon: 'tv', name: 'Android TV', desc: 'Plays anything. Ad-blocked browser built in.' },
  { icon: 'apple', name: 'Apple TV', desc: 'Native tvOS receiver with AVPlayer.' },
  { icon: 'desktop', name: 'Desktop', desc: 'macOS, Windows, and Linux receiver.' }
];

export const STEPS: Array<[string, string]> = [
  ['01', 'Browse on your phone'],
  ['02', 'Tap cast'],
  ['03', 'Lean back']
];

export type FeatureItem = {
  tag: string;
  title: string;
  desc: string;
  visual:
    | 'allow'
    | 'remote'
    | 'queue'
    | 'engine'
    | 'debrid'
    | 'browser';
};

export const FEATURES: FeatureItem[] = [
  {
    tag: 'PAIRING',
    title: 'Allow with one tap.',
    desc: 'When a phone connects, the player shows an Allow / Reject prompt. Approve once and the device is trusted from then on.',
    visual: 'allow'
  },
  {
    tag: 'CONTROL',
    title: 'Your phone is the remote.',
    desc: 'Full touchpad, D-pad, volume, and scrub. Switch episodes from the couch.',
    visual: 'remote'
  },
  {
    tag: 'PLAYBACK',
    title: 'Season binge mode.',
    desc: 'Start episode one instantly. The rest of the season pre-queues quietly in the background.',
    visual: 'queue'
  },
  {
    tag: 'ENGINE',
    title: 'Multi-engine playback.',
    desc: 'ExoPlayer, MPV, VLC, AVPlayer — PlayBridge picks the right one for the content.',
    visual: 'engine'
  },
  {
    tag: 'SOURCES',
    title: 'Debrid & Stremio support.',
    desc: 'Real-Debrid, AllDebrid, and Stremio addons — resolved locally, no proxy in between.',
    visual: 'debrid'
  },
  {
    tag: 'BROWSER',
    title: 'Ad-free TV browser.',
    desc: 'EasyList + cosmetic filtering, on by default. The TV browser is clean from the first tap.',
    visual: 'browser'
  }
];

export type InstallTab = {
  id: string;
  label: string;
  role: 'sender' | 'player';
  icon: 'android' | 'tv' | 'apple' | 'desktop' | 'firefox' | 'windows' | 'linux';
  title: string;
  steps: Array<[string, string]>;
  cmd: string;
  meta: Array<[string, string]>;
};

export type DesktopPlatform = {
  id: 'macos' | 'windows' | 'linux';
  label: string;
  icon: 'apple' | 'windows' | 'linux';
  title: string;
  steps: Array<[string, string]>;
  cmd: string;
  meta: Array<[string, string]>;
};

export const DESKTOP_PLATFORMS: DesktopPlatform[] = [
  {
    id: 'macos',
    label: 'macOS',
    icon: 'apple',
    title: 'macOS Receiver',
    steps: [
      ['Download', 'Latest .zip from GitHub Releases.'],
      ['Open', 'Extract and right-click → Open (unsigned build).'],
      ['Approve devices', 'Allow the first sender that connects.']
    ],
    cmd: 'github.com/playbridgeapp/PlayBridge/releases?q=pb-desktop-receiver&expanded=true',
    meta: [
      ['sha256', '—'],
      ['size', '86.1 MB'],
      ['min', 'macOS 12']
    ]
  },
  {
    id: 'windows',
    label: 'Windows',
    icon: 'windows',
    title: 'Windows Receiver',
    steps: [
      ['Download', 'Latest .zip from GitHub Releases.'],
      ['Extract & run', 'Run playbridge_desktop.exe — no installer needed.'],
      ['Approve devices', 'Allow the first sender that connects.']
    ],
    cmd: 'github.com/playbridgeapp/PlayBridge/releases?q=pb-desktop-receiver&expanded=true',
    meta: [
      ['sha256', '—'],
      ['size', '86.1 MB'],
      ['min', 'Windows 10']
    ]
  },
  {
    id: 'linux',
    label: 'Linux',
    icon: 'linux',
    title: 'Linux Receiver',
    steps: [
      ['Download', 'Latest .tar.gz from GitHub Releases.'],
      ['Extract & run', 'Run bundle/playbridge_desktop from the extracted folder.'],
      ['Approve devices', 'Allow the first sender that connects.']
    ],
    cmd: 'github.com/playbridgeapp/PlayBridge/releases?q=pb-desktop-receiver&expanded=true',
    meta: [
      ['sha256', '—'],
      ['size', '86.1 MB'],
      ['min', 'libmpv2 required']
    ]
  }
];

export const INSTALL_TABS: InstallTab[] = [
  {
    id: 'android',
    label: 'Android',
    role: 'sender',
    icon: 'android',
    title: 'Android phones',
    steps: [
      ['Download', 'Latest APK from GitHub Releases.'],
      ['Allow install', 'Permit installs from unknown sources.'],
      ['Open & connect', 'Send to a player on your network.']
    ],
    cmd: 'github.com/playbridgeapp/PlayBridge/releases?q=pb-phone-app&expanded=true',
    meta: [
      ['sha256', 'a4f1…c2b9'],
      ['size', '28.4 MB'],
      ['min', 'Android 8']
    ]
  },
  {
    id: 'tvplayer',
    label: 'TV Player',
    role: 'player',
    icon: 'tv',
    title: 'Android TV Player',
    steps: [
      ['Download', 'TV Player APK from GitHub Releases.'],
      ['Sideload', 'adb install or Downloader app.'],
      ['Approve devices', 'Allow the first phone that connects.']
    ],
    cmd: 'github.com/playbridgeapp/PlayBridge/releases?q=pb-tv-player&expanded=true',
    meta: [
      ['sha256', '—'],
      ['size', '—'],
      ['min', 'Android TV 8']
    ]
  },
  {
    id: 'tvbrowser',
    label: 'TV Browser',
    role: 'player',
    icon: 'tv',
    title: 'Android TV Browser',
    steps: [
      ['Download', 'TV Browser APK from GitHub Releases.'],
      ['Sideload', 'adb install or Downloader app.'],
      ['Approve devices', 'Allow the first phone that connects.']
    ],
    cmd: 'github.com/playbridgeapp/PlayBridge/releases?q=pb-tv-browser&expanded=true',
    meta: [
      ['sha256', '—'],
      ['size', '—'],
      ['min', 'Android TV 8']
    ]
  },
  {
    id: 'appletv',
    label: 'Apple TV',
    role: 'player',
    icon: 'apple',
    title: 'Apple TV',
    steps: [
      ['Clone Repository', 'Clone the repository using the git command.'],
      ['Open Xcode', 'Open tv/apple target folder in Xcode.'],
      ['Run on Apple TV', 'Select your Apple TV as Xcode run destination.']
    ],
    cmd: 'git clone https://github.com/playbridgeapp/PlayBridge.git',
    meta: [
      ['status', 'Active Development'],
      ['platform', 'tvOS 16.0+'],
      ['environment', 'Xcode 15+']
    ]
  },
  {
    id: 'desktop',
    label: 'Desktop',
    role: 'player',
    icon: 'desktop',
    title: 'Desktop Receiver',
    steps: [], // Loaded from DESKTOP_PLATFORMS dynamically
    cmd: '',
    meta: []
  },
  {
    id: 'firefox',
    label: 'Firefox',
    role: 'sender',
    icon: 'firefox',
    title: 'Firefox extension',
    steps: [
      ['Download', 'Latest .xpi from GitHub Releases.'],
      ['Install', 'Drag the .xpi onto Firefox.'],
      ['Right-click links', 'Send any link to a player.']
    ],
    cmd: 'github.com/playbridgeapp/PlayBridge/releases?q=pb-firefox-extension&expanded=true',
    meta: [
      ['sha256', '77fa…d3e2'],
      ['size', '3.2 MB'],
      ['min', 'Firefox 109']
    ]
  }
];

export const FAQ: Array<[string, string]> = [
  ['Do I need an account?', 'Never. PlayBridge has no concept of users — there is nothing to sign up for.'],
  ['Does it work over the internet?', 'No. PlayBridge is local-network only by design. Your traffic never leaves your home.'],
  ['Is it really free?', 'Yes, fully open source under GPLv3. No paywalls, no tiers, no telemetry.'],
  ['What video formats are supported?', 'Anything ExoPlayer, MPV, VLC, or AVPlayer can play — essentially everything.'],
  ['Can I use it without Stremio or Debrid?', 'Yes. Paste any URL, drop in any file, point it at any media server.'],
  ['How is this different from AirPlay or Chromecast?', 'Not tied to one manufacturer. Same protocol across Android, Apple TV, Linux, Firefox.']
];

export const STATS: Array<[string, string]> = [
  ['4.2k', 'GitHub stars'],
  ['GPL-3.0', 'License'],
  ['v2.4.1', 'Version'],
  ['87', 'Contributors']
];
