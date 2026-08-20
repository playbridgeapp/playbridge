export const SITE = {
  name: 'PlayBridge',
  tagline: 'Your phone. Your TV. One bridge.',
  /** Document title for the homepage (H1 stays `tagline`). */
  title: 'PlayBridge app — Cast your phone to your TV',
  description:
    'Open-source PlayBridge app: browse on your phone, watch on Android TV, Apple TV, Desktop, or a DLNA TV. Local network, no account.',
  url: 'https://playbridge.app',
  ogImage: '/favicon.svg',
  twitter: '@playbridge',
  email: 'playbridgeapp@gmail.com',
  github: 'https://github.com/playbridgeapp/PlayBridge',
  githubOrg: 'https://github.com/playbridgeapp',
  discord: 'https://discord.gg/4U6WPSdSa9',
  /** Suite is multi-product; do not invent a single semver for the marketing site. */
  versionLabel: 'Alpha'
};

export const CLI_INSTALL_COMMANDS = {
  unix: 'curl -fsSL https://playbridge.app/install.sh | sh',
  windows: 'irm https://playbridge.app/install.ps1 | iex'
} as const;

/** GitHub release body markers used in ?q= search links (see docs/release.md). */
export const RELEASE_MARKERS = {
  desktop: '1a4b6c',
  phone: '5c9b2f',
  tvPlayer: '8d2a1c',
  tvBrowser: '3e7f9a',
  extension: '9f2d8e',
  cli: '7b2c9a'
} as const;

export function releaseSearchUrl(marker: string): string {
  return `github.com/playbridgeapp/PlayBridge/releases?q=${marker}&expanded=true`;
}

export type PlatformIcon =
  | 'android'
  | 'firefox'
  | 'tv'
  | 'apple'
  | 'desktop'
  | 'terminal';

export type Platform = {
  icon: PlatformIcon;
  name: string;
  desc: string;
  href: string;
};

export const SENDERS: Platform[] = [
  {
    icon: 'android',
    name: 'Android app',
    desc: 'Browse, search, and send media to a receiver on your network.',
    href: '/senders#android'
  },
  {
    icon: 'firefox',
    name: 'Browser extension',
    desc: 'Detect and cast media from Firefox or Chrome / Edge / Brave tabs.',
    href: '/senders#extension'
  },
  {
    icon: 'desktop',
    name: 'Desktop app',
    desc: 'Cast local files and streams to TVs — same app also receives.',
    href: '/senders#desktop'
  },
  {
    icon: 'terminal',
    name: 'CLI',
    desc: 'Discover devices and send files or URLs from the terminal.',
    href: '/senders#cli'
  }
];

export const RECEIVERS: Platform[] = [
  {
    icon: 'tv',
    name: 'Android TV',
    desc: 'Primary living-room receiver. Optional GeckoView + uBlock Origin plugin.',
    href: '/receivers#androidtv'
  },
  {
    icon: 'apple',
    name: 'Apple TV',
    desc: 'Native tvOS receiver with AVPlayer (build from source).',
    href: '/receivers#appletv'
  },
  {
    icon: 'desktop',
    name: 'Desktop app',
    desc: 'macOS, Windows, and Linux receiver — same app also sends.',
    href: '/receivers#desktop'
  },
  {
    icon: 'terminal',
    name: 'CLI',
    desc: 'Receive casts via mpv, or host a browser receiver from the terminal.',
    href: '/receivers#cli'
  },
  {
    icon: 'tv',
    name: 'DLNA / UPnP TV',
    desc: 'Nothing to install. The phone discovers the renderer on your network.',
    href: '/receivers#dlna'
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
    title: 'Install a receiver',
    desc: 'Put PlayBridge on the screen you watch on — Android TV, Apple TV, Desktop, or the CLI with mpv.',
    phase: 'Set up once'
  },
  {
    num: '02',
    title: 'Install a sender',
    desc: 'Phone app, browser extension, Desktop, or CLI — anything that can push media to a receiver.',
    phase: 'Set up once'
  },
  {
    num: '03',
    title: 'Connect on the same Wi-Fi',
    desc: 'They discover each other automatically. Confirm a 6-digit code on first connect; paired devices stay trusted.',
    phase: 'Set up once'
  },
  {
    num: '04',
    title: 'Browse on one device, watch on another',
    desc: 'Pick a video on your sender and cast — it plays on the receiver instantly.',
    phase: 'Every time'
  }
];

export type FeatureItem = {
  tag: string;
  title: string;
  desc: string;
  visual: 'allow' | 'remote' | 'queue' | 'engine' | 'library' | 'browser';
};

export const FEATURES: FeatureItem[] = [
  {
    tag: 'PAIRING',
    title: 'A 6-digit code. Then it stays trusted.',
    desc: 'First connect shows a short code on the receiver. Confirm it on the sender for an encrypted local link. Paired devices reconnect on their own.',
    visual: 'allow'
  },
  {
    tag: 'CONTROL',
    title: 'Your phone is the remote.',
    desc: 'Touchpad, D-pad, keyboard, transport, volume, and track selection — from the couch, while the video stays on the big screen.',
    visual: 'remote'
  },
  {
    tag: 'BROWSER',
    title: 'Browse on the phone. Cast what it finds.',
    desc: 'The Android sender includes a GeckoView browser with uBlock Origin. It detects direct files, HLS, DASH, and page players — tap cast.',
    visual: 'browser'
  },
  {
    tag: 'LIBRARY',
    title: 'Your library. Your files.',
    desc: 'A library on the phone with a watchlist and collections. Send a title to the receiver, or cast a video stored on the phone.',
    visual: 'library'
  },
  {
    tag: 'PLAYBACK',
    title: 'Queue, auto-advance, resume.',
    desc: 'Start an episode; the rest of the season can follow. Watch progress is remembered, and resume seeks the receiver to where you left off.',
    visual: 'queue'
  },
  {
    tag: 'SCREENS',
    title: 'PlayBridge receivers — or the TV you already have.',
    desc: 'Android TV, Fire TV, Apple TV, and Desktop play with ExoPlayer, MPV, VLC, or AVPlayer depending on the device. DLNA / UPnP TVs need nothing installed.',
    visual: 'engine'
  }
];

export type FeatureBand = {
  primary: FeatureItem;
  secondary: FeatureItem;
};

/** Homepage bands — same facts as FEATURES, grouped so the page is not six identical rows. */
export const FEATURE_BANDS: FeatureBand[] = [
  { primary: FEATURES[2], secondary: FEATURES[3] },
  { primary: FEATURES[0], secondary: FEATURES[1] },
  { primary: FEATURES[4], secondary: FEATURES[5] }
];

export type InstallRole = 'sender' | 'receiver';
export type CliInstallPlatform = keyof typeof CLI_INSTALL_COMMANDS;

export type InstallIcon =
  | 'android'
  | 'tv'
  | 'apple'
  | 'desktop'
  | 'firefox'
  | 'windows'
  | 'linux'
  | 'terminal';

export type InstallDetail = {
  title: string;
  steps: Array<[string, string]>;
  cmd: string;
  downloadUrl?: string;
  playStoreUrl?: string;
  /** Honest metadata only (role, platform, marker, status). No fake sizes/hashes. */
  meta: Array<[string, string]>;
  notice?: { badge: string; text: string };
  /** Use DESKTOP_PLATFORMS for OS-specific download steps. */
  desktop?: boolean;
  /** OS-specific one-liners shown in a copyable block (CLI). */
  installCommands?: Record<CliInstallPlatform, string>;
  /** Optional plugin callout (Android TV GeckoView). */
  plugin?: {
    title: string;
    body: string;
    downloadUrl?: string;
    cmd?: string;
  };
};

export type ExtensionBrowser = {
  id: 'chrome' | 'firefox';
  label: string;
  icon: 'desktop' | 'firefox';
  title: string;
  steps: Array<[string, string]>;
  cmd: string;
  downloadUrl: string;
  meta: Array<[string, string]>;
};

export type InstallProduct = {
  id: string;
  label: string;
  icon: InstallIcon;
  /** Role-specific panels. Dual-role products define both with different copy. */
  sender?: InstallDetail;
  receiver?: InstallDetail;
  hidden?: boolean;
};

export type DesktopPlatform = {
  id: 'macos' | 'windows' | 'linux';
  label: string;
  icon: 'apple' | 'windows' | 'linux';
  title: string;
  senderSteps: Array<[string, string]>;
  receiverSteps: Array<[string, string]>;
  cmd: string;
  downloadUrl?: string;
  meta: Array<[string, string]>;
};

const desktopMeta = (min: string): Array<[string, string]> => [
  ['roles', 'sender + receiver'],
  ['marker', RELEASE_MARKERS.desktop],
  ['min', min]
];

export const DESKTOP_PLATFORMS: DesktopPlatform[] = [
  {
    id: 'macos',
    label: 'macOS',
    icon: 'apple',
    title: 'macOS Desktop',
    senderSteps: [
      ['Download', `Latest .zip from GitHub Releases (marker ${RELEASE_MARKERS.desktop}).`],
      ['Open', 'Extract and right-click → Open (unsigned build).'],
      ['Cast out', 'Send local files or streams to a TV / other receiver. Pair the browser extension here if you cast from Chrome or Firefox.']
    ],
    receiverSteps: [
      ['Download', `Latest .zip from GitHub Releases (marker ${RELEASE_MARKERS.desktop}).`],
      ['Open', 'Extract and right-click → Open (unsigned build).'],
      ['Approve senders', 'Allow phones and extensions that connect — this machine becomes the screen you watch on.']
    ],
    cmd: releaseSearchUrl(RELEASE_MARKERS.desktop),
    downloadUrl: '/download/macos',
    meta: desktopMeta('macOS 12')
  },
  {
    id: 'windows',
    label: 'Windows',
    icon: 'windows',
    title: 'Windows Desktop',
    senderSteps: [
      ['Download', `Latest .zip from GitHub Releases (marker ${RELEASE_MARKERS.desktop}).`],
      ['Extract & run', 'Run playbridge_desktop.exe — no installer needed.'],
      ['Cast out', 'Send local files or streams to a TV / other receiver. Pair the browser extension here if you cast from Chrome or Firefox.']
    ],
    receiverSteps: [
      ['Download', `Latest .zip from GitHub Releases (marker ${RELEASE_MARKERS.desktop}).`],
      ['Extract & run', 'Run playbridge_desktop.exe — no installer needed.'],
      ['Approve senders', 'Allow phones and extensions that connect — this machine becomes the screen you watch on.']
    ],
    cmd: releaseSearchUrl(RELEASE_MARKERS.desktop),
    downloadUrl: '/download/windows',
    meta: desktopMeta('Windows 10')
  },
  {
    id: 'linux',
    label: 'Linux',
    icon: 'linux',
    title: 'Linux Desktop',
    senderSteps: [
      ['Download', `Latest .tar.gz from GitHub Releases (marker ${RELEASE_MARKERS.desktop}).`],
      ['Extract & run', 'Run bundle/playbridge_desktop (needs libmpv2).'],
      ['Cast out', 'Send local files or streams to a TV / other receiver. Pair the browser extension here if you cast from Chrome or Firefox.']
    ],
    receiverSteps: [
      ['Download', `Latest .tar.gz from GitHub Releases (marker ${RELEASE_MARKERS.desktop}).`],
      ['Extract & run', 'Run bundle/playbridge_desktop (needs libmpv2).'],
      ['Approve senders', 'Allow phones and extensions that connect — this machine becomes the screen you watch on.']
    ],
    cmd: releaseSearchUrl(RELEASE_MARKERS.desktop),
    downloadUrl: '/download/linux',
    meta: desktopMeta('libmpv2 required')
  }
];

export const INSTALL_PRODUCTS: InstallProduct[] = [
  {
    id: 'android',
    label: 'Android',
    icon: 'android',
    sender: {
      title: 'Android phone (sender)',
      steps: [
        ['Google Play', 'Install PlayBridge Sender from the Google Play Store.'],
        ['Or GitHub Releases', `Download APK from GitHub Releases (marker ${RELEASE_MARKERS.phone}) for direct sideloading.`],
        ['Open & cast', 'Discover a PlayBridge receiver or a DLNA / UPnP TV on your Wi‑Fi and send media to it.']
      ],
      notice: {
        badge: 'DLNA / UPnP',
        text: 'The Android sender can also find TVs that already speak DLNA / UPnP. Nothing to install on those sets. Details are on Receivers → DLNA / UPnP.'
      },
      cmd: releaseSearchUrl(RELEASE_MARKERS.phone),
      playStoreUrl: 'https://play.google.com/store/apps/details?id=com.playbridge.sender',
      downloadUrl: '/download/android',
      meta: [
        ['role', 'sender'],
        ['store', 'Google Play Store'],
        ['marker', RELEASE_MARKERS.phone],
        ['min', 'Android 8']
      ]
    }
  },
  {
    id: 'extension',
    label: 'Extension',
    icon: 'firefox',
    sender: {
      title: 'Browser extension (sender)',
      steps: [],
      cmd: releaseSearchUrl(RELEASE_MARKERS.extension),
      meta: [
        ['role', 'sender'],
        ['browsers', 'Chrome, Edge, Brave, Firefox'],
        ['marker', RELEASE_MARKERS.extension]
      ],
      notice: {
        badge: 'Needs a receiver',
        text: 'Pair with PlayBridge Desktop (or another receiver) so casted tabs have somewhere to play.'
      }
    }
  },
  {
    id: 'desktop',
    label: 'Desktop',
    icon: 'desktop',
    sender: {
      title: 'Desktop app (sender)',
      steps: [],
      cmd: releaseSearchUrl(RELEASE_MARKERS.desktop),
      meta: desktopMeta('macOS 12 · Windows 10 · Linux'),
      desktop: true,
      notice: {
        badge: 'Also a receiver',
        text: 'Same install works as a full receiver. See the Receivers page for watch-on-this-machine setup.'
      }
    },
    receiver: {
      title: 'Desktop app (receiver)',
      steps: [],
      cmd: releaseSearchUrl(RELEASE_MARKERS.desktop),
      meta: desktopMeta('macOS 12 · Windows 10 · Linux'),
      desktop: true,
      notice: {
        badge: 'Also a sender',
        text: 'Same install can cast local files and host the browser extension. See the Senders page for cast-out setup.'
      }
    }
  },
  {
    id: 'cli',
    label: 'CLI',
    icon: 'terminal',
    sender: {
      title: 'PlayBridge CLI (sender)',
      steps: [
        ['Install', 'Choose your operating system above and run the verified installer command.'],
        [
          'Discover',
          'playbridge discover — list TVs, Desktop, and other receivers on your network.'
        ],
        [
          'Send',
          'playbridge send video.mp4 (or a stream URL) to the device you pick.'
        ],
        [
          'Manual archives',
          `Verified multi-arch packages remain available on GitHub Releases (marker ${RELEASE_MARKERS.cli}).`
        ]
      ],
      cmd: releaseSearchUrl(RELEASE_MARKERS.cli),
      installCommands: CLI_INSTALL_COMMANDS,
      meta: [
        ['role', 'sender'],
        ['binary', 'playbridge'],
        ['marker', RELEASE_MARKERS.cli]
      ],
      notice: {
        badge: 'Also a receiver',
        text: 'The same binary can receive casts with mpv. Open the Receivers page for receive mode.'
      }
    },
    receiver: {
      title: 'PlayBridge CLI (receiver)',
      steps: [
        ['Install', 'Choose your operating system above and run the verified installer command.'],
        [
          'Install mpv',
          'Receiver mode needs mpv on PATH (brew install mpv or your distro package).'
        ],
        [
          'Receive',
          'playbridge receiver — approve senders and play incoming casts via mpv.'
        ],
        [
          'Manual archives',
          `Verified multi-arch packages remain available on GitHub Releases (marker ${RELEASE_MARKERS.cli}).`
        ]
      ],
      cmd: releaseSearchUrl(RELEASE_MARKERS.cli),
      installCommands: CLI_INSTALL_COMMANDS,
      meta: [
        ['role', 'receiver'],
        ['binary', 'playbridge'],
        ['marker', RELEASE_MARKERS.cli]
      ],
      notice: {
        badge: 'Also a sender',
        text: 'The same binary can discover devices and send media. Open the Senders page for cast-out setup.'
      }
    }
  },
  {
    id: 'androidtv',
    label: 'Android TV',
    icon: 'tv',
    receiver: {
      title: 'Android TV (receiver)',
      steps: [
        ['Google Play', 'Install PlayBridge for Android TV from Google Play (Open Testing).'],
        [
          'Or Downloader / Releases',
          `Sideload with Downloader code 9557748, or GitHub Releases (marker ${RELEASE_MARKERS.tvPlayer}).`
        ],
        ['Approve senders', 'Allow the first phone or CLI that connects.']
      ],
      cmd: releaseSearchUrl(RELEASE_MARKERS.tvPlayer),
      playStoreUrl: 'https://play.google.com/store/apps/details?id=com.playbridge.player',
      downloadUrl: '/download/tv-player',
      meta: [
        ['role', 'receiver'],
        ['store', 'Google Play (Open Testing)'],
        ['marker', RELEASE_MARKERS.tvPlayer],
        ['min', 'Android TV 8']
      ],
      plugin: {
        title: 'GeckoView + uBlock Origin',
        body: 'Optional plugin: Mozilla GeckoView with uBlock Origin for ad-free browsing on the TV. The TV receiver already includes System WebView.',
        downloadUrl: '/download/tv-browser',
        cmd: releaseSearchUrl(RELEASE_MARKERS.tvBrowser)
      }
    }
  },
  {
    id: 'appletv',
    label: 'Apple TV',
    icon: 'apple',
    receiver: {
      title: 'Apple TV (receiver)',
      steps: [
        ['Clone repository', 'git clone the PlayBridge monorepo.'],
        ['Open Xcode', 'Open the tv/apple target folder in Xcode.'],
        ['Run on Apple TV', 'Select your Apple TV as the run destination.']
      ],
      cmd: 'git clone https://github.com/playbridgeapp/PlayBridge.git',
      meta: [
        ['role', 'receiver'],
        ['status', 'Active development'],
        ['platform', 'tvOS 16.0+']
      ],
      notice: {
        badge: 'Pre-release',
        text: 'Not yet on TestFlight. Developers and testers can build from source with Xcode.'
      }
    }
  },
  {
    id: 'dlna',
    label: 'DLNA / UPnP',
    icon: 'tv',
    receiver: {
      title: 'DLNA / UPnP TV',
      steps: [
        ['Same Wi-Fi', 'Phone and TV on the same local network.'],
        [
          'Android sender',
          'This path uses the PlayBridge Android phone app. Other senders do not discover DLNA renderers.'
        ],
        [
          'Pick the TV',
          'Open the device list on the phone. Compatible renderers appear next to PlayBridge receivers. No 6-digit code.'
        ],
        [
          'Cast',
          'Play, pause, stop, and seek from the phone. Volume and audio/subtitle tracks are not exposed. Keep the phone awake if you use a queue.'
        ]
      ],
      cmd: '',
      meta: [
        ['role', 'receiver'],
        ['install', 'None on the TV'],
        ['sender', 'Android phone'],
        ['pairing', 'Not required']
      ],
      notice: {
        badge: 'Nothing to install',
        text: 'The TV already has a renderer. PlayBridge does not run on that set.'
      }
    }
  }
];

export const EXTENSION_BROWSERS: ExtensionBrowser[] = [
  {
    id: 'chrome',
    label: 'Chrome',
    icon: 'desktop',
    title: 'Chrome / Edge / Brave',
    steps: [
      ['Open Store', 'Install PlayBridge Video Detector from the Chrome Web Store.'],
      [
        'Pair a receiver',
        'Run PlayBridge Desktop (or another receiver) so the extension has a cast target.'
      ],
      ['Cast videos', 'Detect streams on web pages and cast with one click.']
    ],
    cmd: 'chromewebstore.google.com/detail/playbridge-video-detector/gofdcnocpnieoonficfnfccolcocoaim',
    downloadUrl:
      'https://chromewebstore.google.com/detail/playbridge-video-detector/gofdcnocpnieoonficfnfccolcocoaim?hl=en',
    meta: [
      ['role', 'sender'],
      ['store', 'Chrome Web Store'],
      ['platform', 'Chrome, Brave, Edge']
    ]
  },
  {
    id: 'firefox',
    label: 'Firefox',
    icon: 'firefox',
    title: 'Firefox',
    steps: [
      ['Open Store', 'Install PlayBridge Video Detector from Firefox Add-ons.'],
      [
        'Pair a receiver',
        'Run PlayBridge Desktop (or another receiver) so the extension has a cast target.'
      ],
      ['Cast videos', 'Detect streams on web pages and cast with one click.']
    ],
    cmd: 'addons.mozilla.org/en-US/firefox/addon/playbridge-video-detector',
    downloadUrl: 'https://addons.mozilla.org/en-US/firefox/addon/playbridge-video-detector/',
    meta: [
      ['role', 'sender'],
      ['store', 'Firefox Add-ons'],
      ['platform', 'Firefox Desktop']
    ]
  }
];

export function productsForRole(role: InstallRole): InstallProduct[] {
  return INSTALL_PRODUCTS.filter((p) => !p.hidden && (role === 'sender' ? p.sender : p.receiver));
}

export function detailFor(product: InstallProduct, role: InstallRole): InstallDetail | undefined {
  return role === 'sender' ? product.sender : product.receiver;
}

/** Resolve product id from URL hash (supports legacy #chrome / #firefox → extension). */
export function productIdFromHash(hash: string, role: InstallRole): string | null {
  const raw = hash.replace(/^#/, '');
  if (!raw) return null;
  if (raw === 'chrome' || raw === 'firefox') {
    return role === 'sender' ? 'extension' : null;
  }
  const list = productsForRole(role);
  return list.some((p) => p.id === raw) ? raw : null;
}
