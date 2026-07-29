import * as esbuild from "esbuild";
import * as fs from "fs";
import * as path from "path";

const watch = process.argv.includes("--watch");

// Store extension targets + phone GeckoView built-in detector.
// Shared pure logic lives in src/core/ and is bundled into each host entry.
const TARGETS = [
  {
    name: "firefox",
    manifest: "manifests/firefox.json",
    outDir: "dist/firefox",
    includeConsentPage: false,
    entryPoints: [
      "src/background.ts",
      "src/content.ts",
      "src/ui/popup.ts",
    ],
    copyAndroid: false,
  },
  {
    name: "chrome",
    manifest: "manifests/chrome.json",
    outDir: "dist/chrome",
    includeConsentPage: true,
    entryPoints: [
      "src/background.ts",
      "src/content.ts",
      "src/ui/popup.ts",
      "src/ui/consent.ts",
    ],
    copyAndroid: false,
  },
  {
    name: "geckoview",
    manifest: "manifests/geckoview.json",
    outDir: "dist/geckoview",
    includeConsentPage: false,
    entryPoints: [
      "src/geckoview/background.ts",
      "src/geckoview/content.ts",
    ],
    // Phone ships the detector as a bundled WebExtension asset.
    copyAndroid: true,
    androidOut:
      "../mobile/android/app/src/main/assets/extensions/video_detector",
  },
];

const sharedOpts = {
  bundle: true,
  platform: "browser",
  target: ["firefox102", "chrome110"],
  sourcemap: watch ? "inline" : false,
  minify: !watch,
  define: {
    __PB_DEBUG__: watch ? "true" : "false",
  },
  // Preserve the src/ layout so src/ui/popup.ts → <outDir>/ui/popup.js
  // and src/geckoview/background.ts → <outDir>/geckoview/background.js.
  // GeckoView manifest expects background.js at package root — flattened below.
  outbase: "src",
};

function copyStatics(outDir, manifest, includeConsentPage) {
  fs.mkdirSync(`${outDir}/ui/fonts`, { recursive: true });
  const statics = [
    [manifest, `${outDir}/manifest.json`],
    ["src/ui/popup.html", `${outDir}/ui/popup.html`],
    ["src/ui/popup.css", `${outDir}/ui/popup.css`],
    ["src/ui/outfit.css", `${outDir}/ui/outfit.css`],
    ["src/icon.png", `${outDir}/icon.png`],
  ];
  if (includeConsentPage) {
    statics.push(
      ["src/ui/consent.html", `${outDir}/ui/consent.html`],
      ["src/ui/consent.css", `${outDir}/ui/consent.css`],
    );
  }
  for (const [src, dst] of statics) {
    if (fs.existsSync(src)) fs.copyFileSync(src, dst);
  }
  const fontsDir = "src/ui/fonts";
  if (fs.existsSync(fontsDir)) {
    for (const f of fs.readdirSync(fontsDir)) {
      fs.copyFileSync(path.join(fontsDir, f), path.join(outDir, "ui/fonts", f));
    }
  }
}

/** Flatten geckoview/* entries to package root for the phone asset layout. */
function flattenGeckoview(outDir) {
  const gvDir = path.join(outDir, "geckoview");
  if (!fs.existsSync(gvDir)) return;
  for (const f of fs.readdirSync(gvDir)) {
    fs.copyFileSync(path.join(gvDir, f), path.join(outDir, f));
  }
  fs.rmSync(gvDir, { recursive: true, force: true });
}

function copyToAndroid(outDir, androidOut) {
  if (!androidOut) return;
  const abs = path.resolve(androidOut);
  fs.mkdirSync(abs, { recursive: true });
  // Only ship the WebExtension package files the phone needs.
  const files = ["manifest.json", "background.js", "content.js"];
  for (const f of files) {
    const src = path.join(outDir, f);
    if (fs.existsSync(src)) {
      fs.copyFileSync(src, path.join(abs, f));
    }
  }
  console.log(`Copied GeckoView detector → ${androidOut}/`);
}

function finalizeTarget(t) {
  copyStatics(t.outDir, t.manifest, t.includeConsentPage);
  if (t.name === "geckoview") {
    flattenGeckoview(t.outDir);
    // GeckoView package has no popup/consent chrome.
    fs.rmSync(path.join(t.outDir, "ui"), { recursive: true, force: true });
    try {
      fs.unlinkSync(path.join(t.outDir, "icon.png"));
    } catch {
      /* optional */
    }
  }
  if (t.copyAndroid) {
    copyToAndroid(t.outDir, t.androidOut);
  }
  console.log(`Build complete → ${t.outDir}/`);
}

// Never let removed entry points or static files leak into a store package.
for (const target of TARGETS) {
  fs.rmSync(target.outDir, { recursive: true, force: true });
}

if (watch) {
  for (const t of TARGETS) {
    const ctx = await esbuild.context({
      ...sharedOpts,
      entryPoints: t.entryPoints,
      outdir: t.outDir,
      plugins: [
        {
          name: "copy-statics",
          setup(build) {
            build.onEnd(() => finalizeTarget(t));
          },
        },
      ],
    });
    await ctx.watch();
  }
  console.log(
    "Watching for changes → dist/firefox + dist/chrome + dist/geckoview ...",
  );
} else {
  for (const t of TARGETS) {
    await esbuild.build({
      ...sharedOpts,
      entryPoints: t.entryPoints,
      outdir: t.outDir,
    });
    finalizeTarget(t);
  }
}
