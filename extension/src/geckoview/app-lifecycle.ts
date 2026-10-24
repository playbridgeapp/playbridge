import { PAGE_CHANNEL_PRELUDE, injectPageScriptWithChannel } from "./page-channel";

export function validAppReopenedMessage(value: unknown): boolean {
  return !!value && typeof value === "object" && !Array.isArray(value) &&
    Object.keys(value).length === 1 && (value as { type?: unknown }).type === "app_reopened";
}

export const APP_LIFECYCLE_PAGE_SCRIPT = `
(function() {
  ${PAGE_CHANNEL_PRELUDE}
  var EventCtor = Event;
  var dispatchWindow = window.dispatchEvent.bind(window);
  channelReceive(function(message) {
    if (message.type === 'app_reopened' && Object.keys(message).length === 1) {
      dispatchWindow(new EventCtor('PlayBridgeAppReopened'));
    }
  });
})();
`;

/** The native policy port is document-bound; only its top frame signals the page. */
export function installAppLifecycleBridge(): (message: unknown) => boolean {
  if (window.top !== window) return () => false;
  const page = injectPageScriptWithChannel(APP_LIFECYCLE_PAGE_SCRIPT);
  return (message) => {
    if (!validAppReopenedMessage(message)) return false;
    page?.post(message);
    return true;
  };
}
