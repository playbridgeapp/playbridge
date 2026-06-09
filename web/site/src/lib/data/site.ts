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
  { icon: 'android', name: 'Android app', desc: 'Browse, search, and send anything to a player.' }
  // Hidden for now — the Firefox extension sender is not being promoted.
  // { icon: 'firefox', name: 'Firefox extension', desc: 'Send links from any tab — right-click → cast.' }
];

export const PLAYERS: Platform[] = [
  { icon: 'tv', name: 'Android TV', desc: 'Plays anything. Optional GeckoView + uBlock Origin browser plugin.' },
  { icon: 'apple', name: 'Apple TV', desc: 'Native tvOS receiver with AVPlayer.' },
  { icon: 'desktop', name: 'Desktop', desc: 'macOS, Windows, and Linux receiver.' }
];

export type Step = {
  num: string;
  title: string;
  desc: string;
  phase: 'Set up once' | 'Every time';
};

export const STEPS: Step[] = [
  {
    num: '01',
    title: 'Install a player on your TV',
    desc: 'Put the receiver on the screen you watch on — Android TV, Apple TV, or a Mac / Windows / Linux desktop.',
    phase: 'Set up once'
  },
  {
    num: '02',
    title: 'Install the sender on your phone',
    desc: 'The Android app is your remote, search bar, and browser, all in one.',
    phase: 'Set up once'
  },
  {
    num: '03',
    title: 'Connect on the same Wi-Fi',
    desc: 'They discover each other automatically. Approve your phone on the TV once and it stays trusted.',
    phase: 'Set up once'
  },
  {
    num: '04',
    title: 'Browse on phone, watch on TV',
    desc: 'Pick a video on your phone and tap cast — it plays on the big screen instantly.',
    phase: 'Every time'
  }
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
    title: 'Ad-free GeckoView browser.',
    desc: "The TV player's built-in System WebView can't block ads. An optional GeckoView plugin adds Mozilla's engine so uBlock Origin runs natively — clean from the first tap.",
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
  downloadUrl?: string;
  meta: Array<[string, string]>;
  // hidden tabs are not rendered as tabs, but their data may still be referenced
  // (e.g. the TV browser is surfaced as a GeckoView plugin inside the Android TV tab).
  hidden?: boolean;
};

export type DesktopPlatform = {
  id: 'macos' | 'windows' | 'linux';
  label: string;
  icon: 'apple' | 'windows' | 'linux';
  title: string;
  steps: Array<[string, string]>;
  cmd: string;
  downloadUrl?: string;
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
    downloadUrl: '/download/macos',
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
    downloadUrl: '/download/windows',
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
    downloadUrl: '/download/linux',
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
    downloadUrl: '/download/android',
    meta: [
      ['sha256', 'a4f1…c2b9'],
      ['size', '28.4 MB'],
      ['min', 'Android 8']
    ]
  },
  {
    id: 'androidtv',
    label: 'Android TV',
    role: 'player',
    icon: 'tv',
    title: 'Android TV',
    steps: [
      ['Download', 'Use Downloader app on TV with code 9557748.'],
      ['Install', 'Sideload via adb install or follow Downloader prompts.'],
      ['Approve devices', 'Allow the first phone that connects.']
    ],
    cmd: 'github.com/playbridgeapp/PlayBridge/releases?q=pb-tv-player&expanded=true',
    downloadUrl: '/download/tv-player',
    meta: [
      ['sha256', '—'],
      ['size', '—'],
      ['min', 'Android TV 8']
    ]
  },
  {
    // Not a standalone tab — surfaced as a GeckoView plugin inside the Android TV tab.
    id: 'tvbrowser',
    label: 'GeckoView Browser',
    role: 'player',
    icon: 'tv',
    title: 'GeckoView Browser Plugin',
    hidden: true,
    steps: [
      ['Download', 'GeckoView browser plugin APK from GitHub Releases.'],
      ['Sideload', 'adb install or Downloader app — installs alongside the player.'],
      ['Browse ad-free', 'EasyList + cosmetic filtering on by default.']
    ],
    cmd: 'github.com/playbridgeapp/PlayBridge/releases?q=pb-tv-browser&expanded=true',
    downloadUrl: '/download/tv-browser',
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
  // Hidden for now — the Firefox extension sender is not being promoted.
  // {
  //   id: 'firefox',
  //   label: 'Firefox',
  //   role: 'sender',
  //   icon: 'firefox',
  //   title: 'Firefox extension',
  //   steps: [
  //     ['Download', 'Latest .xpi from GitHub Releases.'],
  //     ['Install', 'Drag the .xpi onto Firefox.'],
  //     ['Right-click links', 'Send any link to a player.']
  //   ],
  //   cmd: 'github.com/playbridgeapp/PlayBridge/releases?q=pb-firefox-extension&expanded=true',
  //   downloadUrl: '/download/firefox',
  //   meta: [
  //     ['sha256', '77fa…d3e2'],
  //     ['size', '3.2 MB'],
  //     ['min', 'Firefox 109']
  //   ]
  // }
];
