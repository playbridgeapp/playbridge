import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { build } from "esbuild";

const directory = await mkdtemp(join(tmpdir(), "playbridge-extension-tests-"));
const outfiles = [
  join(directory, "data-consent.test.mjs"),
  join(directory, "media-candidate.test.mjs"),
  join(directory, "settings.test.mjs"),
];

try {
  await build({
    entryPoints: [
      "test/data-consent.test.ts",
      "test/media-candidate.test.ts",
      "test/settings.test.ts",
    ],
    outdir: directory,
    entryNames: "[name]",
    outExtension: { ".js": ".mjs" },
    bundle: true,
    platform: "node",
    format: "esm",
    target: "node20",
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
