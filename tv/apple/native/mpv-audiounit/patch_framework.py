#!/usr/bin/env python3
"""Rebuild ONLY the pinned MPVKit AudioUnit object; preserve every other member.

Invoked by CocoaPods post_install. No runtime interposition or private Apple API.
Source and input binaries are checksum-pinned; unknown binaries fail closed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
SOURCE_URL = "https://codeload.github.com/mpv-player/mpv/tar.gz/refs/tags/v0.41.0"
SOURCE_SHA = "ee21092a5ee427353392360929dc64645c54479aefdb5babc5cfbb5fad626209"
MEMBER = "audio_out_ao_audiounit.m.o"
SLICES = {
    "tvos-arm64_arm64e": (
        "6a280cae36b7eb25acca71981c42a2815cb5bd60923002c7e8b7897c64db67ed",
        "appletvos", {"arm64": "arm64-apple-tvos14.0", "arm64e": "arm64e-apple-tvos14.0"}),
    "tvos-arm64_x86_64-simulator": (
        "afd762c519710ba77d934eea3529a9ca674d3fba71f82f42cf9215357848da9a",
        "appletvsimulator", {"arm64": "arm64-apple-tvos14.0-simulator", "x86_64": "x86_64-apple-tvos14.0-simulator"}),
}


def sha(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def run(*args, cwd=None):
    return subprocess.check_output([str(x) for x in args], cwd=cwd, text=True)


def member_hashes(path):
    """Hash ordered BSD archive member payloads, ignoring timestamps/symbol index.

    Preserve duplicate names: a dictionary would silently hide changed objects.
    """
    members = []
    with open(path, "rb") as f:
        if f.read(8) != b"!<arch>\n":
            raise RuntimeError("Expected a thin BSD archive")
        while header := f.read(60):
            if len(header) != 60 or header[58:] != b"`\n":
                raise RuntimeError("Invalid archive header")
            size = int(header[48:58])
            name = header[:16].decode().strip()
            data = f.read(size)
            if len(data) != size:
                raise RuntimeError("Truncated archive")
            if name.startswith("#1/"):
                name_size = int(name[3:])
                name, data = data[:name_size].rstrip(b"\0").decode(), data[name_size:]
            else:
                name = name.rstrip("/")
            if name != MEMBER and not name.startswith("__.SYMDEF"):
                members.append((name, hashlib.sha256(data).hexdigest()))
            if size % 2:
                f.read(1)
    return members


def prepare_source(cache):
    """Download immutable upstream source; preserve license alongside patched build."""
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / "mpv-v0.41.0.tar.gz"
    if not archive.exists():
        partial = cache / "mpv-download.tmp"
        run("curl", "--fail", "--location", "--retry", "2", "--max-time", "120", SOURCE_URL, "-o", partial)
        if sha(partial) != SOURCE_SHA:
            raise RuntimeError("mpv source checksum mismatch")
        partial.replace(archive)
    if sha(archive) != SOURCE_SHA:
        raise RuntimeError("cached mpv source checksum mismatch")
    return archive


def extract_source(archive, work):
    run("tar", "-xzf", archive, "-C", work)
    source = work / "mpv-0.41.0"
    # Apply with no fuzz: a mismatched patch must never silently change another site.
    run("patch", "--batch", "--fuzz=0", "-p1", "-i", HERE / "channel-layout-fallback.patch", cwd=source)
    shutil.copyfile(HERE / "config.h", source / "config.h")
    return source


def compile_driver(source, output, sdk, target):
    sdk_path = run("xcrun", "--sdk", sdk, "--show-sdk-path").strip()
    run("xcrun", "--sdk", sdk, "clang", "-target", target, "-isysroot", sdk_path,
        "-std=c11", "-O2", "-fobjc-arc", "-I", source,
        "-include", HERE / "abi-check.h", "-c", source / "audio/out/ao_audiounit.m", "-o", output)


def patch_framework(framework, cache):
    # Include toolchain identity so an Xcode change rebuilds instead of trusting old output.
    fingerprint = hashlib.sha256()
    for name in ("patch_framework.py", "channel-layout-fallback.patch", "config.h", "abi-check.h"):
        fingerprint.update((HERE / name).read_bytes())
    fingerprint.update(run("xcrun", "clang", "--version").encode())
    fingerprint.update(run("xcodebuild", "-version").encode())
    build_id = fingerprint.hexdigest()
    cache.mkdir(parents=True, exist_ok=True)
    receipt_path = cache / "receipt.json"
    receipt = json.loads(receipt_path.read_text()) if receipt_path.exists() else {}
    inputs = {}
    up_to_date = True
    # Verify ALL slices before changing any. Backups are never inferred from unknown input.
    for name, (expected, _, _) in SLICES.items():
        binary = framework / name / "MPVKit.framework/MPVKit"
        current = sha(binary)
        original = cache / (name + ".original.a")
        recorded = receipt.get(name, {})
        if current == expected:
            shutil.copyfile(binary, original)
        elif current != recorded.get("sha256"):
            raise RuntimeError(f"Unknown MPVKit binary: {name}; reinstall the pinned pod. Refusing to patch.")
        if not original.exists() or sha(original) != expected:
            raise RuntimeError(f"Missing or altered original backup: {name}; reinstall the pinned pod.")
        inputs[name] = original
        up_to_date &= current == recorded.get("sha256") and recorded.get("build") == build_id
    if up_to_date:
        print("MPVKit AudioUnit channel-layout fallback is up to date")
        return
    archive = prepare_source(cache)
    with tempfile.TemporaryDirectory(prefix="mpv-audio-", dir=cache) as temp:
        work = Path(temp)
        source = extract_source(archive, work)
        outputs = {}
        for name, (_, sdk, targets) in SLICES.items():
            thin_outputs = []
            for arch, target in targets.items():
                arch_dir = work / name / arch
                arch_dir.mkdir(parents=True)
                thin = arch_dir / "MPVKit.a"
                run("xcrun", "lipo", inputs[name], "-thin", arch, "-output", thin)
                before = run("ar", "-t", thin).splitlines()
                if before.count(MEMBER) != 1:
                    raise RuntimeError("Expected exactly one pinned AudioUnit object")
                # Compare all other payloads, including duplicate object names.
                preserved = member_hashes(thin)
                obj = arch_dir / MEMBER
                compile_driver(source, obj, sdk, target)
                run("ar", "-r", thin, obj)
                run("xcrun", "ranlib", thin)
                if run("ar", "-t", thin).splitlines() != before:
                    raise RuntimeError("Archive member list changed unexpectedly")
                if preserved != member_hashes(thin):
                    raise RuntimeError("Non-AudioUnit archive members changed unexpectedly")
                thin_outputs.append(thin)
                print(f"Rebuilt MPVKit AudioUnit: {name}/{arch}", flush=True)
            output = work / (name + ".a")
            run("xcrun", "lipo", "-create", *thin_outputs, "-output", output)
            outputs[name] = output
        # Commit only after every architecture compiled and every preservation check passed.
        for name, output in outputs.items():
            binary = framework / name / "MPVKit.framework/MPVKit"
            staged = binary.with_suffix(".patched")
            shutil.copyfile(output, staged)
            os.replace(staged, binary)
            receipt[name] = {"sha256": sha(binary), "build": build_id}
        staged_receipt = receipt_path.with_suffix(".tmp")
        staged_receipt.write_text(json.dumps(receipt, indent=2) + "\n")
        staged_receipt.replace(receipt_path)
    print("Installed MPVKit AudioUnit layout fallback; video/codec objects unchanged")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("framework", type=Path, help="Installed MPVKit.xcframework")
    parser.add_argument("--cache", type=Path, required=True, help="Private build cache outside xcframework")
    args = parser.parse_args()
    patch_framework(args.framework.resolve(), args.cache.resolve())
