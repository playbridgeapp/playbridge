/**
 * Desktop page bridge MAIN-world content script for Chrome MV3.
 * Runs in top frames before page scripts run.
 */
import { FIXED_PAGE_CHANNEL_BOOT } from "./core/page-channel";
import { initDesktopPageBridge } from "./core/page-bridge";

if (window.top === window) {
  initDesktopPageBridge(FIXED_PAGE_CHANNEL_BOOT);
}
