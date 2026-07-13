import { access, readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");

function fail(message) {
  throw new Error(`Store validation failed: ${message}`);
}

function assert(condition, message) {
  if (!condition) fail(message);
}

async function readJson(path) {
  return JSON.parse(await readFile(resolve(root, path), "utf8"));
}

async function assertFiles(base, files) {
  for (const file of files) {
    try {
      await access(resolve(root, base, file));
    } catch {
      fail(`${base}/${file} is missing`);
    }
  }
}

const firefoxSource = await readJson("manifests/firefox.json");
const chromeSource = await readJson("manifests/chrome.json");
const firefoxBuilt = await readJson("dist/firefox/manifest.json");
const chromeBuilt = await readJson("dist/chrome/manifest.json");
const packageJson = await readJson("package.json");

assert(firefoxSource.version === chromeSource.version, "manifest versions differ");
assert(
  firefoxBuilt.version === firefoxSource.version &&
    chromeBuilt.version === chromeSource.version,
  "built manifests do not match source versions",
);
assert(firefoxSource.manifest_version === 2, "Firefox must remain MV2");
assert(chromeSource.manifest_version === 3, "Chrome must remain MV3");

for (const [target, manifest] of [
  ["Firefox", firefoxSource],
  ["Chrome", chromeSource],
]) {
  assert(!manifest.permissions.includes("activeTab"), `${target} still requests activeTab`);
  assert(
    !manifest.permissions.includes("webRequestExtraHeaders"),
    `${target} declares webRequestExtraHeaders`,
  );
}

const gecko = firefoxSource.browser_specific_settings?.gecko;
assert(gecko?.id === "video-detector@playbridge", "Firefox ID changed");
assert(
  Number.parseInt(gecko?.strict_min_version, 10) >= 140,
  "Firefox minimum version must support built-in data consent",
);
assert(
  Number.parseInt(
    firefoxSource.browser_specific_settings?.gecko_android
      ?.strict_min_version,
    10,
  ) >= 142,
  "Firefox Android minimum version must support built-in data consent",
);
const firefoxData = gecko?.data_collection_permissions?.required ?? [];
assert(
  firefoxData.includes("browsingActivity") &&
    firefoxData.includes("websiteContent") &&
    !firefoxData.includes("none"),
  "Firefox data collection declarations are incomplete",
);

assert(
  packageJson.packageManager === "pnpm@9.15.2",
  "packageManager must pin pnpm 9.15.2",
);

const runtimeFiles = [
  "background.js",
  "content.js",
  "icon.png",
  "ui/fonts/outfit.css",
  "ui/popup.css",
  "ui/popup.html",
  "ui/popup.js",
];
await assertFiles("dist/firefox", runtimeFiles);
await assertFiles("dist/chrome", runtimeFiles);
await assertFiles("dist/chrome", [
  "ui/consent.css",
  "ui/consent.html",
  "ui/consent.js",
]);

for (const [target, htmlNames] of [
  ["firefox", ["popup.html"]],
  ["chrome", ["consent.html", "popup.html"]],
]) {
  for (const htmlName of htmlNames) {
    const html = await readFile(
      resolve(root, "dist", target, "ui", htmlName),
      "utf8",
    );
    assert(
      !/<script[^>]+src=["']https?:/i.test(html),
      `${target}/${htmlName} loads remote code`,
    );
  }
}

console.log(
  `Store validation passed for Firefox and Chrome ${firefoxSource.version}.`,
);
