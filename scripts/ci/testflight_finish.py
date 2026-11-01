#!/usr/bin/env python3
"""Finish a TestFlight upload through the App Store Connect API.

After `xcodebuild -exportArchive` uploads a build, this script:

1. waits until App Store Connect has processed the build,
2. sets its English (U.S.) "What to Test" text from the version's CHANGELOG.md
   section, and
3. links the app's most recent approved encryption declaration, so the export
   compliance questions don't have to be answered again for every build.

The declaration is the one you created by answering the questions in App Store
Connect; this script never creates one or changes its answers, and never sets
the build's usesNonExemptEncryption flag. If no approved declaration exists, the
build is left for you to answer by hand.

Standard library only; the ES256 token is signed with the openssl CLI.

Usage:
  testflight_finish.py --bundle-id app.playbridge.phone --version 0.17.0 \
      --build 12.345 --platform IOS --changelog mobile/apple/CHANGELOG.md

Environment: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH (the .p8 file).
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.appstoreconnect.apple.com"
LOCALE = "en-US"
WHATS_NEW_LIMIT = 4000


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def der_to_raw(sig: bytes) -> bytes:
    """Convert a DER ECDSA signature (openssl output) to JWS r||s form."""
    if sig[0] != 0x30:
        raise ValueError("unexpected signature encoding")
    i = 2 if sig[1] < 0x80 else 2 + (sig[1] & 0x7F)
    parts = []
    for _ in range(2):
        if sig[i] != 0x02:
            raise ValueError("unexpected signature encoding")
        n = sig[i + 1]
        value = sig[i + 2 : i + 2 + n].lstrip(b"\0")
        parts.append(value.rjust(32, b"\0"))
        i += 2 + n
    return b"".join(parts)


def make_token(key_id: str, issuer: str, key_path: str) -> str:
    now = int(time.time())
    header = b64url(json.dumps({"alg": "ES256", "kid": key_id, "typ": "JWT"}).encode())
    claims = b64url(
        json.dumps(
            {"iss": issuer, "iat": now, "exp": now + 15 * 60, "aud": "appstoreconnect-v1"}
        ).encode()
    )
    signing_input = f"{header}.{claims}".encode()
    der = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", key_path],
        input=signing_input,
        capture_output=True,
        check=True,
    ).stdout
    return f"{header}.{claims}.{b64url(der_to_raw(der))}"


class Client:
    def __init__(self, key_id: str, issuer: str, key_path: str) -> None:
        self.key_id, self.issuer, self.key_path = key_id, issuer, key_path
        self.token, self.token_time = "", 0.0

    def request(self, method: str, path: str, params: dict | None = None, body: dict | None = None) -> dict:
        if time.time() - self.token_time > 10 * 60:
            self.token, self.token_time = make_token(self.key_id, self.issuer, self.key_path), time.time()
        url = API + path + ("?" + urllib.parse.urlencode(params) if params else "")
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", f"Bearer {self.token}")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        for attempt in range(4):
            try:
                with urllib.request.urlopen(req, timeout=60) as resp:
                    raw = resp.read()
                    return json.loads(raw) if raw else {}
            except urllib.error.HTTPError as err:
                detail = err.read().decode(errors="replace")[:2000]
                if err.code >= 500 or err.code == 429:
                    if attempt < 3:
                        time.sleep(10 * (attempt + 1))
                        continue
                raise RuntimeError(f"{method} {path} failed: HTTP {err.code}: {detail}") from None
            except (urllib.error.URLError, TimeoutError) as err:
                if attempt < 3:
                    time.sleep(10 * (attempt + 1))
                    continue
                raise RuntimeError(f"{method} {path} failed: {err}") from None
        raise AssertionError("unreachable")


def changelog_notes(path: str, version: str) -> str:
    """Return the version's CHANGELOG section as plain text for TestFlight."""
    try:
        with open(path, encoding="utf-8") as f:
            text = f.read()
    except FileNotFoundError:
        return ""
    match = re.search(
        rf"^## \[{re.escape(version)}\][^\n]*\n(.*?)(?=^## \[|\Z)", text, re.M | re.S
    )
    if not match:
        return ""
    lines = []
    for line in match.group(1).strip().splitlines():
        line = re.sub(r"^#{3,}\s*", "", line)  # "### Added" -> "Added"
        line = re.sub(r"^>\s?", "", line)
        line = re.sub(r"^- ", "• ", line)
        line = re.sub(r"\*\*(.+?)\*\*", r"\1", line)
        line = re.sub(r"`([^`]+)`", r"\1", line)
        line = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", line)
        lines.append(line.rstrip())
    notes = re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()
    if len(notes) > WHATS_NEW_LIMIT:
        cut = notes[: WHATS_NEW_LIMIT - 2].rsplit("\n", 1)[0]
        notes = cut + "\n…"
    return notes


def find_app(client: Client, bundle_id: str) -> str:
    apps = client.request("GET", "/v1/apps", {"filter[bundleId]": bundle_id, "limit": 1})["data"]
    if not apps:
        raise RuntimeError(f"No App Store Connect app has bundle id {bundle_id}")
    return apps[0]["id"]


def wait_for_build(client: Client, app_id: str, version: str, build: str, platform: str, timeout: int) -> dict:
    params = {
        "filter[app]": app_id,
        "filter[version]": build,
        "filter[preReleaseVersion.version]": version,
        "filter[preReleaseVersion.platform]": platform,
        "include": "appEncryptionDeclaration",
        "limit": 1,
    }
    deadline = time.time() + timeout
    state = "not found"
    while time.time() < deadline:
        try:
            builds = client.request("GET", "/v1/builds", params)["data"]
        except RuntimeError as err:
            print(f"::warning::{err}; retrying")
            builds = []
        if builds:
            state = builds[0]["attributes"].get("processingState", "unknown")
            if state == "VALID":
                return builds[0]
            if state in ("FAILED", "INVALID"):
                raise RuntimeError(f"Build {version} ({build}) processing ended as {state}")
        print(f"Waiting for build {version} ({build}): {state}")
        time.sleep(30)
    raise RuntimeError(f"Build {version} ({build}) still {state} after {timeout // 60} min")


def set_whats_new(client: Client, build_id: str, notes: str) -> None:
    existing = client.request("GET", f"/v1/builds/{build_id}/betaBuildLocalizations", {"limit": 200})["data"]
    for loc in existing:
        if loc["attributes"].get("locale") == LOCALE:
            client.request(
                "PATCH",
                f"/v1/betaBuildLocalizations/{loc['id']}",
                body={"data": {"type": "betaBuildLocalizations", "id": loc["id"], "attributes": {"whatsNew": notes}}},
            )
            return
    client.request(
        "POST",
        "/v1/betaBuildLocalizations",
        body={
            "data": {
                "type": "betaBuildLocalizations",
                "attributes": {"locale": LOCALE, "whatsNew": notes},
                "relationships": {"build": {"data": {"type": "builds", "id": build_id}}},
            }
        },
    )


def link_encryption(client: Client, app_id: str, build: dict, platform: str) -> str:
    linked = (build.get("relationships", {}).get("appEncryptionDeclaration") or {}).get("data")
    if linked:
        return f"already linked to declaration {linked['id']}"
    if build["attributes"].get("usesNonExemptEncryption") is not None:
        return "export compliance is already answered for this build"

    declarations = client.request(
        "GET", f"/v1/apps/{app_id}/appEncryptionDeclarations", {"limit": 200}
    )["data"]
    approved = [
        d
        for d in declarations
        if d["attributes"].get("appEncryptionDeclarationState") == "APPROVED"
        and d["attributes"].get("platform") in (None, platform)
    ]
    if not approved:
        raise LookupError(
            "No approved encryption declaration for this app; answer the export compliance "
            "questions in App Store Connect once and later builds will reuse it"
        )
    approved.sort(key=lambda d: d["attributes"].get("uploadedDate") or "", reverse=True)
    decl = approved[0]
    client.request(
        "PATCH",
        f"/v1/builds/{build['id']}",
        body={
            "data": {
                "type": "builds",
                "id": build["id"],
                "relationships": {
                    "appEncryptionDeclaration": {"data": {"type": "appEncryptionDeclarations", "id": decl["id"]}}
                },
            }
        },
    )
    return f"linked declaration {decl['id']}"


def summary(line: str) -> None:
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    print(line)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--bundle-id", required=True)
    parser.add_argument("--version", required=True, help="MARKETING_VERSION, e.g. 0.17.0")
    parser.add_argument("--build", required=True, help="Uploaded CFBundleVersion, e.g. 12.345")
    parser.add_argument("--platform", required=True, choices=["IOS", "TV_OS"])
    parser.add_argument("--changelog", required=True)
    parser.add_argument("--timeout", type=int, default=45 * 60, help="Seconds to wait for processing")
    args = parser.parse_args()

    client = Client(os.environ["ASC_KEY_ID"], os.environ["ASC_ISSUER_ID"], os.environ["ASC_KEY_PATH"])
    app_id = find_app(client, args.bundle_id)
    build = wait_for_build(client, app_id, args.version, args.build, args.platform, args.timeout)
    failed = False

    notes = changelog_notes(args.changelog, args.version)
    if notes:
        try:
            set_whats_new(client, build["id"], notes)
            summary(f"📝 Set What to Test from {args.changelog} [{args.version}]")
        except RuntimeError as err:
            print(f"::error::Setting What to Test failed: {err}")
            failed = True
    else:
        print(f"::warning::No [{args.version}] section in {args.changelog}; What to Test left empty.")

    try:
        summary(f"🔐 Export compliance: {link_encryption(client, app_id, build, args.platform)}")
    except LookupError as err:
        print(f"::warning::{err}")
    except RuntimeError as err:
        print(f"::error::Linking the encryption declaration failed: {err}")
        failed = True

    return 1 if failed else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except RuntimeError as err:
        print(f"::error::{err}")
        sys.exit(1)
