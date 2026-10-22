<script lang="ts">
  import Seo from '$lib/seo/Seo.svelte';
  import { SITE } from '$lib/data/site';
</script>

<Seo
  title="Privacy"
  description="What each PlayBridge app stores on your devices, and when browsing, casting, updates, and optional services contact other devices or websites."
  path="/privacy"
/>

<section class="section wrap">
  <div class="section-head">
    <span class="eyebrow">Legal</span>
    <h2>Privacy</h2>
  </div>
  <div class="prose">
    <p class="updated mono">Last updated: 22 October 2026</p>
    <p><strong>Short version.</strong> PlayBridge does not require an account or include an in-app analytics or advertising SDK. Casting to a receiver on your local network can stay local. Browsing the web, searching, updating filter lists, and using a remote receiver or proxy can send data outside your local network.</p>
    <p>A few other things also leave your network: update checks to playbridge.app, and lookups for subtitles and skip-intro times. Each app's section below lists them.</p>
    <p>This page covers the Android phone app, the Android TV / Fire TV receiver and its GeckoView browser plugin, PlayBridge Desktop, the browser extension, the CLI, casting to DLNA / UPnP TVs, and the iPhone, iPad, and Apple TV apps (TestFlight beta).</p>

    <h3 id="everywhere">In every app</h3>
    <ul>
      <li>There is no PlayBridge account and no PlayBridge server that stores your browsing or casting history. What the apps save stays on your devices.</li>
      <li>None of the apps include an analytics, advertising, or crash-reporting SDK. The Android apps write crash and diagnostic logs to the device only. A log leaves the device only if you choose to share it.</li>
      <li>Senders and receivers talk over an encrypted local connection (WSS). Pairing stores a token and the receiver's certificate fingerprint on the sender. Receivers store the name of each paired sender. See <a href="/security">Security</a> for how pairing works.</li>
      <li>A cast sends the receiver the media address, title and artwork, subtitles, and any request headers (such as Referer or Cookie) the media server needs. The receiver then fetches the media from that server, which sees the receiver's IP address. Casting a file from your phone or computer sends that file to the receiver over your network.</li>
    </ul>

    <h3 id="android">Android phone (sender)</h3>
    <p><strong>On the phone.</strong> Browsing history, bookmarks, open tabs, downloads, cast history, watch progress and resume positions, settings, saved receivers, and any API keys or account details you enter for optional features or backups. Receiver tokens are encrypted with a key held in the Android Keystore. Clear history from the History screen and remove receivers from the Connection screen. Android's own backup can include this app data, depending on your device settings.</p>
    <p><strong>What leaves the phone.</strong></p>
    <ul>
      <li>Websites you open in the built-in GeckoView browser. Searches typed in the address bar go to Google Search.</li>
      <li>Tab icons come from Google's favicon service, which receives the domain of the site in each tab.</li>
      <li>If you install uBlock Origin during setup or from Extensions, it downloads from addons.mozilla.org and the app checks for updates once a day. uBlock Origin downloads its own filter lists.</li>
      <li>On launch, the app asks playbridge.app whether a newer version exists (see <a href="#updates">Update checks</a>).</li>
      <li>The older built-in library, which is deprecated and will be removed, looks up title details from public metadata services while you browse it. Those services receive the titles and IDs you look up.</li>
      <li>Optional services you set up yourself, with your own API key or account, receive the requests you make through them. Nothing is sent to them until you set them up.</li>
      <li><strong>Bridged apps</strong> are websites that open in the in-app browser and can cast through PlayBridge. The app adds one, streams.playbridge.app, on first launch, and you can remove it. Each bridged app handles data under its own policy.</li>
      <li>If you turn on automatic backup, the app uploads its export file to the S3-compatible storage you configure.</li>
      <li>Subtitle language detection uses Google ML Kit's on-device language ID. The text is not uploaded. Google says ML Kit may send performance and usage metrics to Google.</li>
      <li>When you cast to a Google Cast device, that device loads PlayBridge's receiver page from cast.playbridge.app along with Google's Cast framework.</li>
    </ul>
    <p>Screen mirroring, the Android TV remote, and casting to DLNA TVs stay on your local network.</p>

    <h3 id="androidtv">Android TV and Fire TV (receiver)</h3>
    <p><strong>On the TV.</strong> Playback history and favorites, including each item's media address, title, request headers, playback position, and track choices. Turn this off with <strong>Save playback history</strong> in Settings, and clear it from the library. The receiver also stores paired senders and their tokens, plus its own TLS identity (protected by an Android Keystore key and excluded from backups). A diagnostic log is off by default. When on, it removes URLs and credentials from each line before writing it, and a paired phone can read it for troubleshooting. Android's backup can include history and pairing data, depending on your device settings.</p>
    <p><strong>What leaves the TV.</strong></p>
    <ul>
      <li>Media, subtitles, and artwork from the addresses a sender supplies, with the request headers it supplies.</li>
      <li>When a cast includes an IMDb or TMDB ID, the receiver looks up subtitles from a public OpenSubtitles index (opensubtitles-v3.strem.io) and skip-intro times from IntroDB and TheIntroDB. These services receive the ID and, for episodes, the season and episode numbers. You can choose which skip-intro provider to use, or set your own address for each.</li>
      <li>The built-in browser blocks ads with EasyList filter lists, downloaded at most once a day.</li>
      <li>On launch, the receiver asks playbridge.app whether a newer version exists.</li>
      <li>A sender can ask the receiver to report playback progress to an address it supplies for that cast (see Apple TV below).</li>
    </ul>
    <p><strong>GeckoView plugin.</strong> The optional browser plugin downloads uBlock Origin from addons.mozilla.org the first time it starts, then checks for updates once a day. Websites you open in it receive ordinary browser requests.</p>

    <h3 id="desktop">Desktop (macOS, Windows, Linux)</h3>
    <p><strong>On your computer.</strong> Cast history and favorites (media address, title, and the request headers needed to replay), settings, paired senders, the TVs this computer has paired with (token and certificate fingerprint), and the app's TLS identity. Senders that pair with Desktop are stored as one-way verifiers, not the original token. Turn history off with <strong>Save Cast History</strong>. <strong>Prevent receiver cast history</strong> asks Android TV and Desktop receivers not to save what you send.</p>
    <p><strong>What leaves your computer.</strong> An update check to playbridge.app a few seconds after launch, with the download coming from GitHub Releases if you accept. Skip-intro lookups to IntroDB and TheIntroDB when a cast includes an IMDb or TMDB ID. Media requests to the servers a cast points at. Desktop also registers its browser-extension helper and a “Play on TV” file menu entry on your computer; neither one contacts the internet.</p>

    <h3 id="extension">Browser extension</h3>
    <p>PlayBridge Video Detector reads, on the pages you visit: media request URLs and headers (including <code>Cookie</code> or <code>Authorization</code> when present, so the player can fetch the same stream), the tab's address, and basic details of video elements for the on-video cast button. It does not read form inputs, keystrokes, or your browsing history.</p>
    <p>It hands detected media only to PlayBridge Desktop on your computer, through the browser's native-messaging channel. It may request a detected playlist, or the first bytes of a media file, from the server it came from to read qualities and subtitles. It has no backend and sends nothing to us. Detections are held in session storage and cleared when you navigate, clear them, or close the browser. Your cast-button preferences stay in local extension storage.</p>
    <p>Permissions: access to all sites and <code>webRequest</code> (to see media requests anywhere), <code>tabs</code> and <code>webNavigation</code> (to match detections to tabs), <code>nativeMessaging</code> (to reach Desktop), <code>storage</code>, <code>notifications</code>, and context menus. In Chrome you can revoke media access from the popup's Settings tab.</p>

    <h3 id="cli">CLI</h3>
    <p>The CLI keeps its files in <code>~/.config/playbridge/</code>: one credentials file per paired receiver (token and certificate fingerprint, readable only by your user), receiver-mode pairing data, settings, and an update-check cache. The interactive dashboard asks playbridge.app for the latest version, sending your OS and CPU type. Results are cached for 24 hours. Set <code>PLAYBRIDGE_NO_UPDATE_CHECK=1</code> to turn this off. Casting to a Google Cast device uses Google's Default Media Receiver unless you choose another one.</p>

    <h3 id="dlna">DLNA / UPnP TVs</h3>
    <p>The Android phone finds DLNA renderers on your local network. No pairing is involved. Files on the phone, and streams that need extra request headers, are served to the TV through a small proxy running on the phone. Other streams are fetched by the TV directly from their server. What the TV's own software does with what it plays is up to its manufacturer.</p>

    <h3 id="updates">Update checks</h3>
    <p>The Android phone and TV apps (including Google Play builds) and Desktop ask <code>playbridge.app/download/…</code> for the latest release. The CLI asks <code>playbridge.app/api/v1/updates/cli</code>. These requests carry your IP address and an app-specific user agent. The CLI also sends its OS and CPU type. playbridge.app is hosted on Cloudflare Pages and gets release details from GitHub. Our update endpoints do not log who asked. Updates download from GitHub Releases. Google Play builds point you to the Play Store, which installs the update. The extension updates through your browser's add-on store.</p>

    <h3 id="iphone">iPhone and iPad (sender, TestFlight beta)</h3>
    <p>The iPhone sender stores browser history, bookmarks, downloads, cast history, settings, and saved receiver details on your device. Receiver pairing credentials are stored in the iOS Keychain. You can clear browser history and website data in Browser settings and forget saved receivers in the Devices screen. Its content blocker downloads filter lists from their publishers (EasyList, uBlock Origin's lists on GitHub, and a short PlayBridge list on playbridge.app). Tab icons come from Google's favicon service.</p>

    <h3 id="appletv">Apple TV (receiver, TestFlight beta)</h3>
    <p>The Apple TV receiver stores cast history and favorites on the Apple TV, including media addresses, titles, request headers sent with a cast, and playback position for resuming. History is encrypted with a key kept in the Apple TV Keychain. You can turn it off with <strong>Save Cast History</strong> in Settings and erase it with <strong>Clear All</strong> in the library. The receiver also stores the names of paired phones and a one-way verifier of each phone's pairing secret, never the secret itself; you can forget phones in the pairing screen. Its TLS identity is kept in the Keychain.</p>
    <p>When your phone casts, the Apple TV fetches the media, subtitles, and artwork from the addresses your phone sends, together with any request headers it supplies. Those servers receive the Apple TV's IP address and those requests.</p>
    <p>A sender can ask the receiver to report playback progress (title identifiers, position, duration, and play state) to an HTTPS address it supplies for that cast, for example a media server you use. These reports go only to that address, stop when the cast ends, and are not stored on the Apple TV.</p>

    <h3 id="choices">Connections you choose</h3>
    <p>Websites you visit and the search engine you use receive ordinary browser requests and may receive your IP address, browser information, cookies, and search terms. Ad-blocking list publishers receive a request when a list downloads. Those sites and publishers handle data under their own policies.</p>
    <p>When you cast, the selected receiver receives the media address and information needed to play it, such as request headers. If you configure a remote proxy, a bridged app, or another third-party service, requests and media information also pass through that service. Review its privacy policy before using it.</p>

    <h3 id="retention">Retention and contact</h3>
    <p>Information kept by PlayBridge apps stays on your devices until you clear it or remove the app, subject to your device's storage and backup settings. External websites, search providers, bridged apps, receivers, and services control their own retention. Operating-system and app-store diagnostics are controlled by your device settings.</p>
    <p>If this page changes, the date at the top changes too.</p>
    <p>For privacy questions, contact <a href="mailto:{SITE.email}">{SITE.email}</a>.</p>
  </div>
</section>

<style>
  .prose { max-width: 640px; }
  .prose p { margin-top: 18px; font-size: 15px; line-height: 1.7; }
  .prose h3 { margin-top: 36px; font-size: 19px; scroll-margin-top: 96px; }
  .prose strong { color: var(--text); }
  .prose a { color: var(--accent); }
  .prose ul { margin: 12px 0 0; padding-left: 20px; }
  .prose li { margin-top: 8px; font-size: 15px; line-height: 1.65; color: var(--text-dim); }
  .prose code { font-family: var(--font-mono-ui); font-size: 0.88em; overflow-wrap: anywhere; }
  .prose .updated { margin-top: 0; font-size: 11px; letter-spacing: 0.14em; text-transform: uppercase; color: var(--text-faint); }
</style>
