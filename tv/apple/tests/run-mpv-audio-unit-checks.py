#!/usr/bin/env python3
"""Host regression tests for the production-patched AudioUnit source (macOS/Xcode)."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
root = Path(__file__).resolve().parents[1]
fix = root / "native/mpv-audiounit"
spec = importlib.util.spec_from_file_location("patch_framework", fix / "patch_framework.py")
patch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(patch)
cache = root / "PlayBridge TV/Pods/MPVKitAudioPatch"
archive = patch.prepare_source(cache)
with tempfile.TemporaryDirectory(prefix="mpv-audio-tests-") as temp:
    work = Path(temp)
    source = patch.extract_source(archive, work)
    binary = work / "audio-tests"
    compile_command = [
        "xcrun", "--sdk", "macosx", "clang", "-std=c11", "-O1", "-g", "-fobjc-arc",
        "-fsanitize=address,undefined", "-I", str(source), "-include", str(fix / "abi-check.h"),
        str(root / "tests/MPVAudioUnitFallbackTests.m"), "-framework", "Foundation",
        "-framework", "AVFoundation", "-framework", "AudioToolbox", "-o", str(binary),
    ]
    subprocess.run(compile_command, check=True)
    subprocess.run([str(binary)], check=True)
    # Recompile the pristine driver against identical doubles to reproduce the
    # reported failure, rather than only testing a hypothetical policy helper.
    subprocess.run(["tar", "-xzf", str(archive), "-C", str(work),
                    "mpv-0.41.0/audio/out/ao_audiounit.m"], check=True)
    subprocess.run(compile_command, check=True)
    subprocess.run([str(binary)], check=True, env={**os.environ, "PB_MPV_EXPECT_ORIGINAL": "1"})
    # Fail-closed protections must trigger BEFORE compilation or binary mutation.
    bad_cache = work / "bad-cache"
    bad_cache.mkdir()
    (bad_cache / "mpv-v0.41.0.tar.gz").write_bytes(b"not the pinned source")
    try:
        patch.prepare_source(bad_cache)
        raise AssertionError("accepted an untrusted source archive")
    except RuntimeError as error:
        assert "checksum mismatch" in str(error)
    unknown_framework = work / "unknown.xcframework"
    unknown = unknown_framework / next(iter(patch.SLICES)) / "MPVKit.framework/MPVKit"
    unknown.parent.mkdir(parents=True)
    unknown.write_bytes(b"not the pinned binary")
    try:
        patch.patch_framework(unknown_framework, work / "unknown-cache")
        raise AssertionError("accepted an unknown binary")
    except RuntimeError as error:
        assert "Unknown MPVKit binary" in str(error)
    assert unknown.read_bytes() == b"not the pinned binary"
    print("PASS: source/binary mismatch fails closed without modifying unknown input")
