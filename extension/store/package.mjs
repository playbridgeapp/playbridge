import { createHash } from "node:crypto";
import { createReadStream, createWriteStream } from "node:fs";
import {
  cp,
  mkdir,
  readFile,
  readdir,
  rm,
  stat,
  writeFile,
} from "node:fs/promises";
import { basename, dirname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

import { ZipArchive } from "archiver";

const extensionRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = resolve(extensionRoot, "..");
const artifactsDir = resolve(extensionRoot, "artifacts");
const sourceStage = resolve(artifactsDir, "source");
const fixedDate = new Date("1980-01-01T00:00:00.000Z");

const manifest = JSON.parse(
  await readFile(resolve(extensionRoot, "manifests/firefox.json"), "utf8"),
);
const version = manifest.version;

async function filesBelow(root) {
  const result = [];
  async function visit(directory) {
    const entries = await readdir(directory, { withFileTypes: true });
    entries.sort((left, right) => left.name.localeCompare(right.name));
    for (const entry of entries) {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) await visit(path);
      else if (entry.isFile()) result.push(path);
    }
  }
  await visit(root);
  return result;
}

async function zipDirectory(source, destination) {
  await mkdir(dirname(destination), { recursive: true });
  const output = createWriteStream(destination);
  const archive = new ZipArchive({ zlib: { level: 9 } });
  const complete = new Promise((resolvePromise, reject) => {
    output.on("close", resolvePromise);
    output.on("error", reject);
    archive.on("error", reject);
    archive.on("warning", (error) => {
      if (error.code !== "ENOENT") reject(error);
    });
  });
  archive.pipe(output);
  for (const file of await filesBelow(source)) {
    archive.append(createReadStream(file), {
      name: relative(source, file).split(sep).join("/"),
      date: fixedDate,
      mode: 0o100644,
    });
  }
  await archive.finalize();
  await complete;
}

async function copySource(relativePath) {
  const source = resolve(extensionRoot, relativePath);
  const destination = resolve(sourceStage, relativePath);
  await mkdir(dirname(destination), { recursive: true });
  await cp(source, destination, { recursive: true });
}

async function checksum(path) {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return `${hash.digest("hex")}  ${basename(path)}`;
}

await rm(artifactsDir, { recursive: true, force: true });
await mkdir(sourceStage, { recursive: true });

for (const path of [
  ".gitignore",
  "AMO_SOURCE_BUILD.md",
  "CHANGELOG.md",
  "PRIVACY.md",
  "README.md",
  "STORE_LISTING_COPY.md",
  "STORE_PUBLISHING_READINESS.md",
  "build.mjs",
  "manifests",
  "package.json",
  "pnpm-lock.yaml",
  "src",
  "store",
  "test",
  "tsconfig.json",
]) {
  await copySource(path);
}
const repositoryLicense = resolve(repositoryRoot, "LICENSE");
const localLicense = resolve(extensionRoot, "LICENSE");
const license = await stat(repositoryLicense)
  .then(() => repositoryLicense)
  .catch(() => localLicense);
await cp(license, resolve(sourceStage, "LICENSE"));

const staleJavaScript = (await filesBelow(resolve(sourceStage, "src"))).filter(
  (file) => file.endsWith(".js"),
);
if (staleJavaScript.length > 0) {
  throw new Error(
    `Source archive contains stale JavaScript: ${staleJavaScript.join(", ")}`,
  );
}

const outputs = [
  {
    source: resolve(extensionRoot, "dist/firefox"),
    destination: resolve(
      artifactsDir,
      `playbridge-extension-firefox-${version}.zip`,
    ),
  },
  {
    source: resolve(extensionRoot, "dist/chrome"),
    destination: resolve(
      artifactsDir,
      `playbridge-extension-chrome-${version}.zip`,
    ),
  },
  {
    source: sourceStage,
    destination: resolve(
      artifactsDir,
      `playbridge-extension-source-${version}.zip`,
    ),
  },
];

for (const output of outputs) {
  const info = await stat(output.source).catch(() => null);
  if (!info?.isDirectory()) {
    throw new Error(`Missing build directory: ${output.source}`);
  }
  await zipDirectory(output.source, output.destination);
}

const sums = [];
for (const output of outputs) sums.push(await checksum(output.destination));
await writeFile(resolve(artifactsDir, "SHA256SUMS.txt"), `${sums.join("\n")}\n`);
await rm(sourceStage, { recursive: true, force: true });

console.log(`Store artifacts created in ${artifactsDir}:`);
for (const output of outputs) console.log(`- ${basename(output.destination)}`);
console.log("- SHA256SUMS.txt");
