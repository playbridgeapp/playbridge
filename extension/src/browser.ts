// Single cross-browser entry point for the WebExtension API.
//
// `webextension-polyfill` provides the promise-based `browser.*` namespace on
// Chrome (which natively only has callback-based `chrome.*`) and is a thin
// pass-through on Firefox. Importing from here everywhere lets the rest of the
// code use one promise-based API for both MV2 (Firefox) and MV3 (Chrome).
import browser from "webextension-polyfill";

export default browser;
