import assert from "node:assert/strict";
import test from "node:test";
import { installAppLifecycleBridge, validAppReopenedMessage } from "../src/geckoview/app-lifecycle";
import { delivered, pageWorld } from "./geckoview-page-channel-fixture";

test("app reopen messages accept only a payload-free native notification", () => {
  assert.equal(validAppReopenedMessage({ type: "app_reopened" }), true);
  for (const value of [null, [], "app_reopened", {}, { type: "other" },
    { type: "app_reopened", data: {} }, { type: "app_reopened", url: "https://app.example" }]) {
    assert.equal(validAppReopenedMessage(value), false);
  }
});

test("the top-frame native relay dispatches a plain page event without changing playbridge", async (t) => {
  const world = pageWorld();
  t.after(world.close);
  world.window.top = world.window;
  const bridge = world.window.playbridge;
  const events: Event[] = [];
  world.window.addEventListener("PlayBridgeAppReopened", (event: Event) => events.push(event));
  const host = globalThis as any;
  const previous = { window: host.window, document: host.document };
  host.window = world.window;
  host.document = {
    createElement: () => ({ textContent: "", remove() {} }),
    documentElement: { appendChild: (script: { textContent: string }) => world.run(script.textContent) },
  };
  let deliver: ReturnType<typeof installAppLifecycleBridge>;
  try { deliver = installAppLifecycleBridge(); }
  finally { Object.assign(host, previous); }
  assert.equal(deliver({ type: "app_reopened", data: {} }), false);
  assert.equal(deliver({ type: "detection_policy" }), false);
  assert.equal(deliver({ type: "app_reopened" }), true);
  await delivered(() => events.length === 1);
  assert.equal(events[0].constructor, Event);
  assert.equal("detail" in events[0], false);
  assert.equal(world.window.playbridge, bridge);

  host.window = { top: world.window };
  try { assert.equal(installAppLifecycleBridge()({ type: "app_reopened" }), false); }
  finally { Object.assign(host, previous); }
  await delivered();
  assert.equal(events.length, 1);
});
