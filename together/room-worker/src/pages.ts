import type { RoomLimits } from "./config";

const STYLE = `
:root{color-scheme:light dark;--bg:#f6f7f9;--fg:#15171a;--muted:#5b616b;--card:#fff;--line:#d9dde3;--accent:#3b5bdb}
@media (prefers-color-scheme:dark){:root{--bg:#111316;--fg:#eceef1;--muted:#a1a8b3;--card:#1a1d21;--line:#2c3138;--accent:#8ea2ff}}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,sans-serif}
main{max-width:34rem;margin:0 auto;padding:3rem 1rem}
h1{font-size:1.5rem;margin:0 0 .5rem}p{color:var(--muted)}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:1rem;margin:1.5rem 0}
code{display:block;word-break:break-all;font-size:1rem;color:var(--fg)}
button{margin-top:.75rem;font:inherit;padding:.5rem 1rem;border-radius:8px;border:0;background:var(--accent);color:#fff;cursor:pointer}
`;

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}

function page(title: string, body: string): string {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${title}</title><style>${STYLE}</style></head><body><main>${body}</main></body></html>`;
}

export function setupPage(origin: string, limits: RoomLimits): string {
  const address = escapeHtml(origin);
  const hours = Math.round(limits.lifetimeMs / 3_600_000);
  const intro = limits.shared
    ? `This is PlayBridge's shared room relay. Parties here are limited to ${limits.maxMembers} devices and ${hours} hours. Deploy your own relay for free to remove these limits.`
    : `Your room relay is running. Parties here allow up to ${limits.maxMembers} devices for up to ${hours} hours.`;
  return page(
    "PlayBridge rooms",
    `<h1>PlayBridge Watch Together</h1><p>${intro}</p>
<div class="card"><p>In PlayBridge, open Settings → Watch Together → Room relay, choose Custom, and paste this address:</p>
<code id="address">${address}</code><button id="copy" type="button">Copy address</button></div>
<script>document.getElementById("copy").onclick=()=>navigator.clipboard.writeText(document.getElementById("address").textContent).then(()=>{document.getElementById("copy").textContent="Copied"});</script>`,
  );
}

export function invitePage(): string {
  return page(
    "Watch party invite",
    `<h1>You're invited to a watch party</h1><p>Open this link on a phone with PlayBridge installed to join. If it opened in a browser, copy the full link and paste it into PlayBridge → Watch Together → Join.</p>`,
  );
}
