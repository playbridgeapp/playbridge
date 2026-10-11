import { spawn } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const executable = resolve(
  root,
  "node_modules/.bin",
  process.platform === "win32" ? "web-ext.cmd" : "web-ext",
);

// The add-on is desktop-only (no gecko_android key), so the linter's check of
// the desktop minimum version against Firefox for Android does not apply.
const IGNORED_WARNINGS = new Set(["KEY_FIREFOX_ANDROID_UNSUPPORTED_BY_MIN_VERSION"]);

const { exitCode, stdout } = await new Promise((resolvePromise, reject) => {
  const child = spawn(
    executable,
    ["lint", "--source-dir", "dist/firefox", "--output", "json"],
    {
      cwd: root,
      env: { ...process.env, NO_UPDATE_NOTIFIER: "1" },
      stdio: ["ignore", "pipe", "inherit"],
    },
  );
  let output = "";
  child.stdout.on("data", (chunk) => (output += chunk));
  child.on("error", reject);
  child.on("exit", (code) => resolvePromise({ exitCode: code ?? 1, stdout: output }));
});

let report;
try {
  report = JSON.parse(stdout);
} catch {
  console.error(stdout);
  console.error("web-ext lint did not produce a JSON report");
  process.exit(exitCode || 1);
}

const problems = [
  ...report.errors.map((item) => ({ ...item, level: "error" })),
  ...report.warnings
    .filter((item) => !IGNORED_WARNINGS.has(item.code))
    .map((item) => ({ ...item, level: "warning" })),
];
const ignored = report.warnings.length - problems.filter((p) => p.level === "warning").length;

for (const problem of problems) {
  console.error(
    `${problem.level.toUpperCase()} ${problem.code}: ${problem.message}` +
      (problem.file ? ` (${problem.file}${problem.line ? `:${problem.line}` : ""})` : ""),
  );
}
console.log(
  `web-ext lint: ${report.errors.length} errors, ${report.warnings.length - ignored} warnings` +
    (ignored ? ` (${ignored} ignored: desktop-only)` : "") +
    `, ${report.notices.length} notices`,
);
if (problems.length > 0 || exitCode !== 0) process.exitCode = 1;
