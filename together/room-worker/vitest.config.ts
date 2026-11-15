import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.jsonc" },
      // Small limits so the tests can reach them.
      miniflare: {
        bindings: {
          MAX_MEMBERS: "3",
          OPEN_ROOMS_PER_IP: "2",
          ROOMS_PER_IP_PER_HOUR: "4",
          DAILY_BUDGET: "1000000",
          ANDROID_APP_PACKAGE: "com.playbridge.sender",
          ANDROID_CERT_SHA256: Array.from({ length: 32 }, () => "ab").join(":"),
        },
      },
    }),
  ],
});
