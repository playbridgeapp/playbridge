/**
 * Shared media-detection core used by:
 * - Desktop store extension (Chrome + Firefox)
 * - Phone GeckoView built-in video detector
 *
 * Host adapters (native messaging, popup, Desktop bridge) stay outside this
 * package. Only pure URL/playlist classification, ranking, and synthetic HLS
 * construction live here.
 */

export * from "./media-candidate";
export * from "./synthetic-hls";
export * from "./hls-parser";
