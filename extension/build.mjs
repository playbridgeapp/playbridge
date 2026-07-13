import * as esbuild from "esbuild";
import * as fs from "fs";
import * as path from "path";

const watch = process.argv.includes("--watch");

// Two browser targets, each with its own manifest and output dir.
const TARGETS = [
  {
    name: "firefox",
    manifest: "manifests/firefox.json",
    outDir: "dist/firefox",
    includeConsentPage: false,
  },
  {
    name: "chrome",
    manifest: "manifests/chrome.json",
    outDir: "dist/chrome",
    includeConsentPage: true,
  },
];

const commonEntryPoints = [
  "src/background.ts",
  "src/content.ts",
  "src/ui/popup.ts",
];

const sharedOpts = {
  bundle: true,
  platform: "browser",
  target: ["firefox102", "chrome110"],
  sourcemap: watch ? "inline" : false,
  minify: !watch,
  // Preserve the src/ layout so src/ui/popup.ts → <outDir>/ui/popup.js.
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

// Never let removed entry points or static files leak into a store package.
for (const target of TARGETS) {
  fs.rmSync(target.outDir, { recursive: true, force: true });
}

if (watch) {
  for (const t of TARGETS) {
    const entryPoints = t.includeConsentPage
      ? [...commonEntryPoints, "src/ui/consent.ts"]
      : commonEntryPoints;
    const ctx = await esbuild.context({
      ...sharedOpts,
      entryPoints,
      outdir: t.outDir,
      plugins: [
        {
          name: "copy-statics",
          setup(build) {
            build.onEnd(() =>
              copyStatics(t.outDir, t.manifest, t.includeConsentPage),
            );
          },
        },
      ],
    });
    await ctx.watch();
  }
  console.log("Watching for changes → dist/firefox + dist/chrome ...");
} else {
  for (const t of TARGETS) {
    const entryPoints = t.includeConsentPage
      ? [...commonEntryPoints, "src/ui/consent.ts"]
      : commonEntryPoints;
    await esbuild.build({ ...sharedOpts, entryPoints, outdir: t.outDir });
    copyStatics(t.outDir, t.manifest, t.includeConsentPage);
    console.log(`Build complete → ${t.outDir}/`);
  }
}
