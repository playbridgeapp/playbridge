import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.jsonc" },
      // Small limits so the tests can reach them.
      miniflare: { bindings: { MAX_MEMBERS: "3", ROOMS_PER_IP_PER_DAY: "2" } },
    }),
  ],
});
