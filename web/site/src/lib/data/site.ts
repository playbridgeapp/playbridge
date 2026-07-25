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
    desc: 'Browse, search, and send anything to a receiver on your network.',
    href: '/senders#android'
  },
  {
    icon: 'firefox',
    name: 'Browser extension',
    desc: 'Detect and cast media from Firefox or Chrome tabs.',
    href: '/senders#chrome'
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
    desc: 'They discover each other automatically. Approve the sender on the receiver once and it stays trusted.',
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
  visual: 'allow' | 'remote' | 'queue' | 'engine' | 'debrid' | 'browser';
};

export const FEATURES: FeatureItem[] = [
  {
    tag: 'PAIRING',
    title: 'Allow with one tap.',
    desc: 'When a sender connects, the receiver shows an Allow / Reject prompt. Approve once and the device is trusted from then on.',
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
    desc: "The TV receiver's built-in System WebView can't block ads. An optional GeckoView plugin adds Mozilla's engine so uBlock Origin runs natively — clean from the first tap.",
    visual: 'browser'
  }
];

export type InstallRole = 'sender' | 'receiver';

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
  meta: Array<[string, string]>;
  notice?: { badge: string; text: string };
  /** Use DESKTOP_PLATFORMS for OS-specific download steps. */
  desktop?: boolean;
  /** Optional plugin callout (Android TV GeckoView). */
  plugin?: {
    title: string;
    body: string;
    downloadUrl?: string;
    cmd?: string;
  };
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
        ['Download', `Latest APK from GitHub Releases (marker ${RELEASE_MARKERS.phone}).`],
        ['Allow install', 'Permit installs from unknown sources.'],
        ['Open & cast', 'Discover a receiver on your Wi‑Fi and send media to it.']
      ],
      cmd: releaseSearchUrl(RELEASE_MARKERS.phone),
      downloadUrl: '/download/android',
      meta: [
        ['role', 'sender'],
        ['marker', RELEASE_MARKERS.phone],
        ['min', 'Android 8']
      ]
    }
  },
  {
    id: 'chrome',
    label: 'Chrome',
    icon: 'desktop',
    sender: {
      title: 'Chrome extension (sender)',
      steps: [
        ['Open Store', 'Install PlayBridge Video Detector from the Chrome Web Store.'],
        ['Pair Desktop', 'Run PlayBridge Desktop as a receiver (or another player) to handle casting.'],
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
    }
  },
  {
    id: 'firefox',
    label: 'Firefox',
    icon: 'firefox',
    sender: {
      title: 'Firefox extension (sender)',
      steps: [
        ['Open Store', 'Install PlayBridge Video Detector from Firefox Add-ons.'],
        ['Pair Desktop', 'Run PlayBridge Desktop as a receiver (or another player) to handle casting.'],
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
        [
          'Install',
          'macOS/Linux: curl -fsSL https://raw.githubusercontent.com/playbridgeapp/playbridge/main/cli/install.sh | sh'
        ],
        [
          'Discover',
          'playbridge discover — list TVs, Desktop, and other receivers on your network.'
        ],
        [
          'Send',
          'playbridge send video.mp4 (or a stream URL) to the device you pick.'
        ],
        [
          'Archives',
          `Windows and multi-arch packages: GitHub Releases (marker ${RELEASE_MARKERS.cli}).`
        ]
      ],
      cmd: releaseSearchUrl(RELEASE_MARKERS.cli),
      meta: [
        ['role', 'sender'],
        ['binary', 'playbridge'],
        ['marker', RELEASE_MARKERS.cli]
      ],
      notice: {
        badge: 'Also a receiver',
        text: 'The same binary can receive casts with mpv. See the Receivers page for receive mode.'
      }
    },
    receiver: {
      title: 'PlayBridge CLI (receiver)',
      steps: [
        [
          'Install',
          'macOS/Linux: curl -fsSL https://raw.githubusercontent.com/playbridgeapp/playbridge/main/cli/install.sh | sh'
        ],
        [
          'Install mpv',
          'Receiver mode needs mpv on PATH (brew install mpv or your distro package).'
        ],
        [
          'Receive',
          'playbridge receiver — approve senders and play incoming casts via mpv.'
        ],
        [
          'Archives',
          `Windows and multi-arch packages: GitHub Releases (marker ${RELEASE_MARKERS.cli}).`
        ]
      ],
      cmd: releaseSearchUrl(RELEASE_MARKERS.cli),
      meta: [
        ['role', 'receiver'],
        ['binary', 'playbridge'],
        ['marker', RELEASE_MARKERS.cli]
      ],
      notice: {
        badge: 'Also a sender',
        text: 'The same binary can discover devices and send media. See the Senders page for cast-out setup.'
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
        [
          'Download',
          `Sideload with Downloader code 9557748, or GitHub Releases (marker ${RELEASE_MARKERS.tvPlayer}).`
        ],
        ['Install', 'adb install or follow Downloader prompts.'],
        ['Approve senders', 'Allow the first phone or CLI that connects.']
      ],
      cmd: releaseSearchUrl(RELEASE_MARKERS.tvPlayer),
      downloadUrl: '/download/tv-player',
      meta: [
        ['role', 'receiver'],
        ['marker', RELEASE_MARKERS.tvPlayer],
        ['min', 'Android TV 8']
      ],
      plugin: {
        title: 'GeckoView + uBlock Origin',
        body: 'Optional plugin: Mozilla GeckoView with uBlock Origin for ad-free browsing on the TV. The player already includes System WebView.',
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
  }
];

export function productsForRole(role: InstallRole): InstallProduct[] {
  return INSTALL_PRODUCTS.filter((p) => !p.hidden && (role === 'sender' ? p.sender : p.receiver));
}

export function detailFor(product: InstallProduct, role: InstallRole): InstallDetail | undefined {
  return role === 'sender' ? product.sender : product.receiver;
}
