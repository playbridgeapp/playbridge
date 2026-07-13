import browser from "../browser";
import type { DataConsentStatus } from "../data-consent";

const allowButton = document.getElementById("allow-btn") as HTMLButtonElement;
const declineButton = document.getElementById("decline-btn") as HTMLButtonElement;
const disableButton = document.getElementById("disable-btn") as HTMLButtonElement;
const closeButton = document.getElementById("close-btn") as HTMLButtonElement;
const offActions = document.getElementById("consent-off-actions") as HTMLElement;
const onActions = document.getElementById("consent-on-actions") as HTMLElement;
const statusElement = document.getElementById("consent-status") as HTMLElement;

function render(status: DataConsentStatus): void {
  if (!status.required) {
    offActions.classList.add("hidden");
    onActions.classList.remove("hidden");
    disableButton.classList.add("hidden");
    statusElement.textContent =
      "Firefox manages this consent in its installation permissions.";
    return;
  }
  offActions.classList.toggle("hidden", status.granted);
  onActions.classList.toggle("hidden", !status.granted);
  disableButton.classList.remove("hidden");
}

async function refresh(): Promise<void> {
  const status = (await browser.runtime.sendMessage({
    action: "getDataConsent",
  })) as DataConsentStatus;
  render(status);
}

async function setConsent(granted: boolean): Promise<void> {
  allowButton.disabled = true;
  declineButton.disabled = true;
  disableButton.disabled = true;
  statusElement.textContent = granted
    ? "Saving your choice…"
    : "Disabling detection and clearing detected media…";
  try {
    const response = (await browser.runtime.sendMessage({
      action: "setDataConsent",
      granted,
    })) as { success?: boolean; status?: DataConsentStatus };
    if (!response?.success || !response.status) {
      throw new Error("Consent update failed");
    }
    render(response.status);
    statusElement.textContent = granted
      ? "Media detection is enabled. Reload an already-playing page if its stream was requested before you allowed access."
      : "Media detection is off and saved detections were cleared.";
  } catch {
    statusElement.textContent = "Could not save your choice. Please try again.";
  } finally {
    allowButton.disabled = false;
    declineButton.disabled = false;
    disableButton.disabled = false;
  }
}

allowButton.addEventListener("click", () => void setConsent(true));
declineButton.addEventListener("click", () => void setConsent(false));
disableButton.addEventListener("click", () => void setConsent(false));
closeButton.addEventListener("click", () => {
  void browser.tabs
    .getCurrent()
    .then((tab) =>
      tab?.id != null ? browser.tabs.remove(tab.id) : window.close(),
    )
    .catch(() => window.close());
});

void refresh().catch(() => {
  statusElement.textContent = "Could not load the current setting.";
});
