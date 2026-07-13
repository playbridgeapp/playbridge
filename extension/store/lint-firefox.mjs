import { spawn } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const executable = resolve(
  root,
  "node_modules/.bin",
  process.platform === "win32" ? "web-ext.cmd" : "web-ext",
);

const exitCode = await new Promise((resolvePromise, reject) => {
  const child = spawn(
    executable,
    ["lint", "--source-dir", "dist/firefox", "--warnings-as-errors"],
    {
      cwd: root,
      env: { ...process.env, NO_UPDATE_NOTIFIER: "1" },
      stdio: "inherit",
    },
  );
  child.on("error", reject);
  child.on("exit", (code) => resolvePromise(code ?? 1));
});

if (exitCode !== 0) process.exitCode = exitCode;
