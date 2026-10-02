import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { build } from "esbuild";

const directory = await mkdtemp(join(tmpdir(), "playbridge-extension-tests-"));
const outfiles = [
  join(directory, "data-consent.test.mjs"),
  join(directory, "geckoview-header-enrichment.test.mjs"),
  join(directory, "geckoview-detection-lifecycle.test.mjs"),
  join(directory, "geckoview-detection-policy.test.mjs"),
  join(directory, "geckoview-detected-media-kind.test.mjs"),
  join(directory, "geckoview-page-cast.test.mjs"),
  join(directory, "geckoview-playback-bridge.test.mjs"),
  join(directory, "geckoview-plugin-bridge.test.mjs"),
  join(directory, "hls-parser.test.mjs"),
  join(directory, "media-candidate.test.mjs"),
  join(directory, "response-body-media.test.mjs"),
  join(directory, "settings.test.mjs"),
  join(directory, "synthetic-hls.test.mjs"),
];

try {
  await build({
    entryPoints: [
      "test/data-consent.test.ts",
      "test/geckoview-header-enrichment.test.ts",
      "test/geckoview-detection-lifecycle.test.ts",
      "test/geckoview-detection-policy.test.ts",
      "test/geckoview-detected-media-kind.test.ts",
      "test/geckoview-page-cast.test.ts",
      "test/geckoview-playback-bridge.test.ts",
      "test/geckoview-plugin-bridge.test.ts",
      "test/hls-parser.test.ts",
      "test/media-candidate.test.ts",
      "test/response-body-media.test.ts",
      "test/settings.test.ts",
      "test/synthetic-hls.test.ts",
    ],
    outdir: directory,
    entryNames: "[name]",
    outExtension: { ".js": ".mjs" },
    bundle: true,
    platform: "node",
    format: "esm",
    target: "node20",
  });

  // Exercise the actual phone scripts with browser/DOM fakes, including injection.
  await build({
    entryPoints: ["src/geckoview/content.ts", "src/geckoview/background.ts"],
    outdir: join(directory, "geckoview-runtime"),
    entryNames: "[name]",
    bundle: true,
    platform: "browser",
    format: "iife",
    target: "firefox102",
  });
  await build({
    stdin: {
      contents: 'import { installPluginBridge } from "./src/geckoview/plugin-bridge"; installPluginBridge();',
      resolveDir: process.cwd(),
    },
    outfile: join(directory, "geckoview-runtime", "plugin-bridge.js"),
    bundle: true, platform: "browser", format: "iife", target: "firefox102",
  });

  const exitCode = await new Promise((resolve, reject) => {
    const child = spawn(process.execPath, ["--test", ...outfiles], {
      stdio: "inherit",
    });
    child.on("error", reject);
    child.on("exit", (code) => resolve(code ?? 1));
  });
  if (exitCode !== 0) process.exitCode = exitCode;
} finally {
  await rm(directory, { recursive: true, force: true });
}
