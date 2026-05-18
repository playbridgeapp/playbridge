import * as esbuild from "esbuild";
import * as fs from "fs";
import * as path from "path";

const watch = process.argv.includes("--watch");

const sharedOpts = {
  bundle: true,
  platform: "browser",
  target: "firefox102",
  sourcemap: watch ? "inline" : false,
  minify: !watch,
};

const entryPoints = [
  { in: "src/background.ts", out: "dist/background" },
  { in: "src/content.ts",    out: "dist/content" },
  { in: "src/ui/popup.ts",   out: "dist/ui/popup" },
];

function copyStatics() {
  const statics = [
    ["manifests/firefox.json",   "dist/manifest.json"],
    ["src/ui/popup.html",       "dist/ui/popup.html"],
    ["src/ui/popup.css",        "dist/ui/popup.css"],
    ["src/ui/outfit.css",       "dist/ui/outfit.css"],
    ["src/icon.png",            "dist/icon.png"],
  ];
  fs.mkdirSync("dist/ui/fonts", { recursive: true });
  for (const [src, dst] of statics) {
    if (fs.existsSync(src)) fs.copyFileSync(src, dst);
  }
  // Copy fonts directory
  const fontsDir = "src/ui/fonts";
  if (fs.existsSync(fontsDir)) {
    for (const f of fs.readdirSync(fontsDir)) {
      fs.copyFileSync(path.join(fontsDir, f), path.join("dist/ui/fonts", f));
    }
  }
}

if (watch) {
  const ctx = await esbuild.context({
    ...sharedOpts,
    entryPoints: entryPoints.map(e => e.in),
    outdir: "dist",
    plugins: [{
      name: "copy-statics",
      setup(build) {
        build.onEnd(() => copyStatics());
      },
    }],
  });
  await ctx.watch();
  console.log("Watching for changes...");
} else {
  for (const { in: src, out } of entryPoints) {
    await esbuild.build({ ...sharedOpts, entryPoints: [src], outfile: `${out}.js` });
  }
  copyStatics();
  console.log("Build complete → dist/");
}
