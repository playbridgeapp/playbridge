import browser from "../browser";
import { displayOrigin, normalizeOrigin, originFromUrl } from "../core/page-origin";
import {
  protocolChips,
  defaultProtocol,
  filterByProtocol,
  visibleDevices,
  hiddenOfflineCount,
  isActiveDevice,
  formatDeviceProtocol,
  DEVICE_PICKER_PROTOCOL_STORAGE_KEY,
} from "../core/device-protocol";

const urlParams = new URLSearchParams(window.location.search);
const rawOrigin = urlParams.get("origin") || "";
const mode = urlParams.get("mode") || "consent";
const origin = originFromUrl(rawOrigin) || normalizeOrigin(rawOrigin) || rawOrigin;

const consentView = document.getElementById("consent-view");
const pickerView = document.getElementById("picker-view");

const consentTitle = document.getElementById("consent-prompt-title");
const consentAllowBtn = document.getElementById("consent-allow-btn");
const consentDenyBtn = document.getElementById("consent-deny-btn");

const pickerOriginLabel = document.getElementById("picker-origin-label");
const devicesList = document.getElementById("devices-list");
const pickerDoneBtn = document.getElementById("picker-done-btn");

let bridgeDevices: any[] = [];
let bridgeSelection: any = null;
let currentStatus: any = null;
let rememberedProtocol: string | null = null;
let selectedPickerProtocol: string | null = null;
let showOfflinePicker = false;

// Initialize view
if (mode === "picker") {
  showPickerView();
} else {
  showConsentView();
}

function showConsentView(): void {
  consentView?.classList.remove("hidden");
  pickerView?.classList.add("hidden");

  if (consentTitle) {
    consentTitle.textContent = `Allow ${displayOrigin(origin)} to use your PlayBridge devices?`;
  }
}

function showPickerView(): void {
  consentView?.classList.add("hidden");
  pickerView?.classList.remove("hidden");

  if (pickerOriginLabel) {
    pickerOriginLabel.textContent = `For ${displayOrigin(origin)}`;
  }

  loadDevices();
}

consentAllowBtn?.addEventListener("click", () => {
  browser.runtime
    .sendMessage({
      action: "pageAccessConsentResponse",
      origin,
      allowed: true,
    })
    .then(() => {
      if (urlParams.get("then") === "picker") {
        showPickerView();
      } else {
        window.close();
      }
    })
    .catch(() => {
      window.close();
    });
});

consentDenyBtn?.addEventListener("click", () => {
  browser.runtime
    .sendMessage({
      action: "pageAccessConsentResponse",
      origin,
      allowed: false,
    })
    .finally(() => {
      window.close();
    });
});

pickerDoneBtn?.addEventListener("click", () => {
  window.close();
});

function loadDevices(): void {
  browser.storage.local
    .get(DEVICE_PICKER_PROTOCOL_STORAGE_KEY)
    .then((result) => {
      if (typeof result[DEVICE_PICKER_PROTOCOL_STORAGE_KEY] === "string") {
        rememberedProtocol = result[DEVICE_PICKER_PROTOCOL_STORAGE_KEY] as string;
      }
    })
    .catch(() => {})
    .finally(() => {
      browser.runtime
        .sendMessage({ action: "wsGetStatus" })
        .then((response: any) => {
          if (response) {
            currentStatus = response;
            bridgeDevices = response.devices || [];
            bridgeSelection = response.selection || null;
            renderDevices();
          }
        })
        .catch(() => {});
    });
}

function renderDevices(): void {
  if (!devicesList) return;
  devicesList.replaceChildren();

  const eligibleDevices = visibleDevices(
    bridgeDevices,
    bridgeSelection,
    currentStatus?.activeTv,
    showOfflinePicker,
  );
  const chips = protocolChips(eligibleDevices);
  const showChips = chips.length >= 2;
  const activeProtocol = showChips
    ? defaultProtocol(
        eligibleDevices,
        bridgeSelection,
        selectedPickerProtocol || rememberedProtocol,
        currentStatus?.activeTv,
      )
    : null;
  const visibleDevicesList =
    showChips && activeProtocol
      ? filterByProtocol(eligibleDevices, activeProtocol)
      : eligibleDevices;
  const offlineCount = hiddenOfflineCount(
    bridgeDevices,
    bridgeSelection,
    currentStatus?.activeTv,
    showChips && activeProtocol ? activeProtocol : null,
  );

  // 1. Protocol filter chips row at the top
  if (showChips) {
    const chipsRow = document.createElement("div");
    chipsRow.className = "protocol-chips-row";
    chips.forEach((proto) => {
      const chip = document.createElement("button");
      chip.type = "button";
      chip.className = `protocol-chip${proto.toLowerCase() === (activeProtocol || "").toLowerCase() ? " active" : ""}`;
      chip.textContent = formatDeviceProtocol(proto);
      chip.addEventListener("click", (e) => {
        if (!e.isTrusted) return;
        selectedPickerProtocol = proto;
        rememberedProtocol = proto;
        browser.storage.local
          .set({ [DEVICE_PICKER_PROTOCOL_STORAGE_KEY]: proto })
          .catch(() => {});
        renderDevices();
      });
      chipsRow.appendChild(chip);
    });
    devicesList.appendChild(chipsRow);
  }

  // 2. This computer row
  const isThisComputerActive =
    bridgeSelection?.kind === "local" || (!bridgeSelection && !currentStatus?.activeTv);
  const localItem = document.createElement("div");
  localItem.className = `saved-connection-item${isThisComputerActive ? " active" : ""}`;
  localItem.addEventListener("click", (e) => {
    if (!e.isTrusted) return;
    browser.runtime.sendMessage({ action: "wsSelectLocal" }).then(() => {
      loadDevices();
    });
  });

  const localInfo = document.createElement("div");
  localInfo.className = "saved-connection-info";
  const localName = document.createElement("span");
  localName.className = "saved-connection-ip";
  localName.textContent = "This computer";
  localInfo.appendChild(localName);
  localItem.appendChild(localInfo);

  if (isThisComputerActive) {
    const actions = document.createElement("div");
    actions.className = "saved-connection-actions";
    const badge = document.createElement("span");
    badge.className = "badge-active";
    badge.textContent = "Active";
    actions.appendChild(badge);
    localItem.appendChild(actions);
  }
  devicesList.appendChild(localItem);

  // 3. Devices
  visibleDevicesList.forEach((d) => {
    const isRowActive = isActiveDevice(d, bridgeSelection, currentStatus?.activeTv);
    const item = document.createElement("div");
    item.className = `saved-connection-item${isRowActive ? " active" : ""}`;

    const info = document.createElement("div");
    info.className = "saved-connection-info";

    const name = document.createElement("span");
    name.className = "saved-connection-ip";
    name.textContent = d.name || "Unnamed Device";
    info.appendChild(name);
    item.appendChild(info);

    const actions = document.createElement("div");
    actions.className = "saved-connection-actions";

    if (isRowActive) {
      const activeBadge = document.createElement("span");
      if (d.online === false) {
        activeBadge.className = "badge-offline";
        activeBadge.textContent = "Offline";
      } else {
        activeBadge.className = "badge-active";
        activeBadge.textContent = "Active";
      }
      actions.appendChild(activeBadge);
    } else if (d.online === false) {
      const offlineBadge = document.createElement("span");
      offlineBadge.className = "badge-offline";
      offlineBadge.textContent = "Offline";
      actions.appendChild(offlineBadge);
    } else if (d.online) {
      const onlineBadge = document.createElement("span");
      onlineBadge.className = "badge-online";
      onlineBadge.textContent = "Online";
      actions.appendChild(onlineBadge);
    }

    if (!d.paired) {
      const pairBtn = document.createElement("button");
      pairBtn.type = "button";
      pairBtn.className = "pair-app-btn";
      pairBtn.textContent = "Pair";
      pairBtn.addEventListener("click", (e) => {
        if (!e.isTrusted) return;
        e.stopPropagation();
        browser.runtime.sendMessage({ action: "wsShowApp", pairId: d.id || d.uuid });
      });
      actions.appendChild(pairBtn);
    }

    item.appendChild(actions);

    item.addEventListener("click", (e) => {
      if (!e.isTrusted) return;
      if (d.protocol) {
        selectedPickerProtocol = d.protocol;
        rememberedProtocol = d.protocol;
        browser.storage.local
          .set({ [DEVICE_PICKER_PROTOCOL_STORAGE_KEY]: d.protocol })
          .catch(() => {});
      }
      browser.runtime
        .sendMessage({ action: "wsSelectDevice", id: d.id || d.uuid })
        .then(() => {
          loadDevices();
        });
    });

    devicesList.appendChild(item);
  });

  // 4. Toggle offline button
  if (offlineCount > 0) {
    const toggleBtn = document.createElement("button");
    toggleBtn.type = "button";
    toggleBtn.className = "toggle-offline-btn";
    toggleBtn.textContent = showOfflinePicker ? "Hide offline" : `Show ${offlineCount} offline`;
    toggleBtn.addEventListener("click", (e) => {
      if (!e.isTrusted) return;
      showOfflinePicker = !showOfflinePicker;
      renderDevices();
    });
    devicesList.appendChild(toggleBtn);
  }
}
