#!/usr/bin/env python3
"""Read-only header/export check for all three supported Apple Cast Core slices."""
import os
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import sys

ATV = ["abi_version", "start", "submit_json", "next_json", "cancel", "free"]
REQUIRED = ["pb_atv_" + name for name in ATV] + [
    "pb_cast_core_abi_version", "pb_session_start", "pb_session_submit_json",
    "pb_session_next_json", "pb_session_cancel", "pb_session_free", "pb_string_free",
]


def main():
    root = Path(sys.argv[1])
    info = plistlib.loads((root / "Info.plist").read_bytes())
    nm = os.environ.get("PB_APPLE_NM") or shutil.which("llvm-nm")
    if not nm:
        homebrew = Path("/opt/homebrew/opt/llvm/bin/llvm-nm")
        nm = str(homebrew) if homebrew.exists() else subprocess.check_output(["xcrun", "--find", "nm"], text=True).strip()
    checked = set()
    for item in info["AvailableLibraries"]:
        if item["SupportedPlatform"] != "ios":
            continue
        variant = item.get("SupportedPlatformVariant", "device")
        directory = root / item["LibraryIdentifier"]
        header = (directory / item["HeadersPath"] / "playbridge_cast_core.h").read_text()
        assert re.search(r"#define\s+PB_ATV_REMOTE_ABI_VERSION\s+1\b", header), "Missing ATV ABI 1 header marker"
        for symbol in REQUIRED:
            assert re.search(r"\b" + symbol + r"\s*\(", header), "Missing declaration: " + symbol
        for arch in item["SupportedArchitectures"]:
            # Universal archives must be inspected per architecture, not just the host slice.
            result = subprocess.run([nm, "-g", "-U", "--arch=" + arch, str(directory / item["LibraryPath"])], capture_output=True, text=True)
            if result.returncode:
                raise RuntimeError("nm failed; set PB_APPLE_NM to a Rust-LLVM-compatible llvm-nm: " + result.stderr[:240])
            names = set(re.findall(r"\b_?(pb_\w+)\s*$", result.stdout, re.MULTILINE))
            missing = set(REQUIRED) - names
            assert not missing, f"Missing {variant}/{arch} exports: {sorted(missing)}"
            checked.add((variant, arch))
            print(f"PASS: Apple {variant}/{arch}: ATV ABI 1 and Cast ABI 2 declarations/exports")
    assert checked == {("device", "arm64"), ("simulator", "arm64"), ("simulator", "x86_64")}, checked


if __name__ == "__main__":
    main()
