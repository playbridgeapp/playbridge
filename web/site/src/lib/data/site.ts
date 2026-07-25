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
  icon: 'android' | 'firefox' | 'tv' | 'apple' | 'desktop' | 'terminal';
  name: string;
  desc: string;
};

export const SENDERS: Platform[] = [
  { icon: 'android', name: 'Android app', desc: 'Browse, search, and send anything to a player.' },
  { icon: 'firefox', name: 'Browser extension', desc: 'Detect & cast media from Firefox or Chrome tabs.' },
  {
    icon: 'desktop',
    name: 'Desktop app',
    desc: 'Cast local files and streams to TVs — also runs as a full receiver.'
  },
  {
    icon: 'terminal',
    name: 'CLI',
    desc: 'Command-line cast: discover devices, send files/URLs, or host a receiver.'
  }
];

export const PLAYERS: Platform[] = [
  { icon: 'tv', name: 'Android TV', desc: 'Plays anything. Optional GeckoView + uBlock Origin browser plugin.' },
  { icon: 'apple', name: 'Apple TV', desc: 'Native tvOS receiver with AVPlayer.' },
  {
    icon: 'desktop',
    name: 'Desktop app',
    desc: 'macOS, Windows, and Linux player — also sends to other receivers on your network.'
  },
  {
    icon: 'terminal',
    name: 'CLI',
    desc: 'Receive casts via mpv, or run a browser receiver from the terminal.'
  }
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
    desc: 'Put a receiver on the screen you watch on — Android TV, Apple TV, Desktop, or the CLI with mpv.',
    phase: 'Set up once'
  },
  {
    num: '02',
    title: 'Install a sender',
    desc: 'Phone app, browser extension, Desktop, or CLI — anything that can push media to a player on your network.',
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

export type InstallRole = 'sender' | 'player';

export type InstallTab = {
  id: string;
  label: string;
  /** Products that both send and receive list both roles and appear in each install group. */
  roles: InstallRole[];
  icon: 'android' | 'tv' | 'apple' | 'desktop' | 'firefox' | 'windows' | 'linux' | 'terminal';
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
    title: 'macOS Desktop',
    steps: [
      ['Download', 'Latest .zip from GitHub Releases (search marker 1a4b6c).'],
      ['Open', 'Extract and right-click → Open (unsigned build).'],
      ['Play or send', 'Receive casts from phone/extension, or cast local files and streams to a TV.']
    ],
    cmd: 'github.com/playbridgeapp/PlayBridge/releases?q=1a4b6c&expanded=true',
    downloadUrl: '/download/macos',
    meta: [
      ['sha256', '—'],
      ['size', '86.1 MB'],
      ['min', 'macOS 12'],
      ['roles', 'sender + player']
    ]
  },
  {
    id: 'windows',
    label: 'Windows',
    icon: 'windows',
    title: 'Windows Desktop',
    steps: [
      ['Download', 'Latest .zip from GitHub Releases (search marker 1a4b6c).'],
      ['Extract & run', 'Run playbridge_desktop.exe — no installer needed.'],
      ['Play or send', 'Receive casts from phone/extension, or cast local files and streams to a TV.']
    ],
    cmd: 'github.com/playbridgeapp/PlayBridge/releases?q=1a4b6c&expanded=true',
    downloadUrl: '/download/windows',
    meta: [
      ['sha256', '—'],
      ['size', '86.1 MB'],
      ['min', 'Windows 10'],
      ['roles', 'sender + player']
    ]
  },
  {
    id: 'linux',
    label: 'Linux',
    icon: 'linux',
    title: 'Linux Desktop',
    steps: [
      ['Download', 'Latest .tar.gz from GitHub Releases (search marker 1a4b6c).'],
      ['Extract & run', 'Run bundle/playbridge_desktop from the extracted folder (needs libmpv2).'],
      ['Play or send', 'Receive casts from phone/extension, or cast local files and streams to a TV.']
    ],
    cmd: 'github.com/playbridgeapp/PlayBridge/releases?q=1a4b6c&expanded=true',
    downloadUrl: '/download/linux',
    meta: [
      ['sha256', '—'],
      ['size', '86.1 MB'],
      ['min', 'libmpv2 required'],
      ['roles', 'sender + player']
    ]
  }
];

export const INSTALL_TABS: InstallTab[] = [
  {
    id: 'android',
    label: 'Android',
    roles: ['sender'],
    icon: 'android',
    title: 'Android phones',
    steps: [
      ['Download', 'Latest APK from GitHub Releases (search marker 5c9b2f).'],
      ['Allow install', 'Permit installs from unknown sources.'],
      ['Open & connect', 'Send to a player on your network.']
    ],
    cmd: 'github.com/playbridgeapp/PlayBridge/releases?q=5c9b2f&expanded=true',
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
    roles: ['player'],
    icon: 'tv',
    title: 'Android TV',
    steps: [
      ['Download', 'Use Downloader app on TV with code 9557748, or GitHub Releases (8d2a1c).'],
      ['Install', 'Sideload via adb install or follow Downloader prompts.'],
      ['Approve devices', 'Allow the first phone that connects.']
    ],
    cmd: 'github.com/playbridgeapp/PlayBridge/releases?q=8d2a1c&expanded=true',
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
    roles: ['player'],
    icon: 'tv',
    title: 'GeckoView Browser Plugin',
    hidden: true,
    steps: [
      ['Download', 'GeckoView browser plugin APK from GitHub Releases (search marker 3e7f9a).'],
      ['Sideload', 'adb install or Downloader app — installs alongside the player.'],
      ['Browse ad-free', 'EasyList + cosmetic filtering on by default.']
    ],
    cmd: 'github.com/playbridgeapp/PlayBridge/releases?q=3e7f9a&expanded=true',
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
    roles: ['player'],
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
    roles: ['sender', 'player'],
    icon: 'desktop',
    title: 'Desktop app',
    steps: [], // Loaded from DESKTOP_PLATFORMS dynamically
    cmd: '',
    meta: []
  },
  {
    id: 'cli',
    label: 'CLI',
    roles: ['sender', 'player'],
    icon: 'terminal',
    title: 'PlayBridge CLI',
    steps: [
      [
        'Install',
        'macOS/Linux: curl -fsSL https://raw.githubusercontent.com/playbridgeapp/playbridge/main/cli/install.sh | sh'
      ],
      [
        'Send',
        'playbridge discover — then playbridge send video.mp4 (or a stream URL) to a TV, Desktop, or other receiver.'
      ],
      [
        'Receive',
        'playbridge receiver — plays incoming casts with mpv. Requires mpv on PATH.'
      ],
      [
        'Archives',
        'Windows and multi-arch packages: GitHub Releases filtered by marker 7b2c9a.'
      ]
    ],
    cmd: 'github.com/playbridgeapp/PlayBridge/releases?q=7b2c9a&expanded=true',
    meta: [
      ['binary', 'playbridge'],
      ['platforms', 'macOS, Linux, Windows'],
      ['roles', 'sender + player'],
      ['marker', '7b2c9a']
    ]
  },
  {
    id: 'chrome',
    label: 'Chrome',
    roles: ['sender'],
    icon: 'desktop',
    title: 'Chrome Extension',
    steps: [
      ['Open Store', 'Install PlayBridge Video Detector from Chrome Web Store.'],
      ['Pair Desktop', 'Run PlayBridge Desktop as a player to handle casting from the browser.'],
      ['Cast Videos', 'Detect and cast streams from web pages with one click.']
    ],
    cmd: 'chromewebstore.google.com/detail/playbridge-video-detector/gofdcnocpnieoonficfnfccolcocoaim',
    downloadUrl: 'https://chromewebstore.google.com/detail/playbridge-video-detector/gofdcnocpnieoonficfnfccolcocoaim?hl=en',
    meta: [
      ['store', 'Chrome Web Store'],
      ['platform', 'Chrome, Brave, Edge'],
      ['status', 'Published']
    ]
  },
  {
    id: 'firefox',
    label: 'Firefox',
    roles: ['sender'],
    icon: 'firefox',
    title: 'Firefox Extension',
    steps: [
      ['Open Store', 'Install PlayBridge Video Detector from Firefox Add-ons.'],
      ['Pair Desktop', 'Run PlayBridge Desktop as a player to handle casting from the browser.'],
      ['Cast Videos', 'Detect and cast streams from web pages with one click.']
    ],
    cmd: 'addons.mozilla.org/en-US/firefox/addon/playbridge-video-detector',
    downloadUrl: 'https://addons.mozilla.org/en-US/firefox/addon/playbridge-video-detector/',
    meta: [
      ['store', 'Firefox Add-ons'],
      ['platform', 'Firefox Desktop'],
      ['status', 'Published']
    ]
  }
];
