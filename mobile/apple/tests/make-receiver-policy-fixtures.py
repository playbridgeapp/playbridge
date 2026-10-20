#!/usr/bin/env python3
"""Project the current declarative Kotlin/Dart/Rust builders into scratch JSON.

No receiver, compiler package install, or network is required. Apple TV's Swift
builder is executed directly by the check runner. Fail if a builder stops using
the declaration shape this projection understands.
"""
import json
import re
import sys
from pathlib import Path

repo, output = map(Path, sys.argv[1:])


def strings(source):
    return re.findall(r'''["']([^"']+)["']''', source)


android = (repo / "tv/android/player/app/src/main/java/com/playbridge/player/server/TvCapabilities.kt").read_text()
constants = {name: strings(value) for name, value in re.findall(r"private val (\w+) = listOf\(([^)]*)\)", android)}
player = {field: constants[name] for field, name in {
    "transport": "PLAYER_TRANSPORT", "keys": "PLAYER_KEYS", "settings": "PLAYER_SETTINGS",
    "imageTransforms": "PLAYER_IMAGE_TRANSFORMS", "tracks": "PLAYER_TRACKS", "queue": "PLAYER_QUEUE", "reporting": "PLAYER_REPORTING",
}.items()}
browsers = {}
for engine in ["webview", "gecko"]:
    match = re.search(r'put\("' + engine + r'", buildBrowserEngineDescriptor\(pointer = BROWSER_POINTER_BASE(.*?)\)\)', android)
    assert match, engine
    browsers[engine] = {"pointer": constants["BROWSER_POINTER_BASE"] + strings(match[1]), **{
        field: constants[name] for field, name in {"transport": "BROWSER_TRANSPORT", "keys": "BROWSER_KEYS",
        "text": "BROWSER_TEXT", "browser": "BROWSER_OPERATIONS", "reporting": "BROWSER_REPORTING"}.items()}}
android_descriptor = {"version": 1, "surfaces": {"player": {"engines": {"exo": player, "mpv": player}}, "browser": {"engines": browsers}},
    "volume": {"scope": "system", "operations": ["relative_step", "mute_toggle"]},
    "host": {"osFamily": "android", "deviceClass": "tv"}, "companionHints": ["android_tv_remote_v2"]}
assert 'listOf("relative_step", "mute_toggle")' in android
(output / "android.json").write_text(json.dumps(android_descriptor))

desktop = (repo / "desktop/lib/receiver_capabilities.dart").read_text()
desktop = re.sub(r"//[^\n]*", "", desktop)
body = desktop.split("'internal_mpv': {", 1)[1].split("},", 1)[0]
desktop_player = {field: strings(values) for field, values in re.findall(r"'(\w+)':\s*\[([^]]*)\]", body)}
assert {"transport", "imageTransforms", "tracks", "queue", "reporting"} <= desktop_player.keys() <= {
    "transport", "imageTransforms", "tracks", "queue", "reporting", "settings"}
# The volume block is built per OS; project macOS with native volume, the shipped default.
volume = desktop.split("'volume': {", 1)[1]
operations = volume.split("'operations':", 1)[1].split("],\n", 2)
volume_ops = [op for op in re.findall(r"'(\w+)'", operations[0] + operations[1]) if op != "macos"]
assert "relative_step" in volume_ops and "'scope': 'system'" in volume and "'fallbackScope': 'app'" in volume
(output / "desktop.json").write_text(json.dumps({"version": 1, "surfaces": {"player": {"engines": {"internal_mpv": desktop_player}}},
    "volume": {"scope": "system", "operations": volume_ops, "step": 0.0625, "fallbackScope": "app"},
    "host": {"osFamily": "macos", "deviceClass": "desktop"}}))

cli = (repo / "cli/src/receive.rs").read_text().split("fn receiver_capabilities()", 1)[1]
cli = cli.split("from_json(json!({", 1)[1].split("}))", 1)[0]
cli_descriptor = json.loads("{" + cli.replace("std::env::consts::OS", '"linux"') + "}")
(output / "cli.json").write_text(json.dumps(cli_descriptor))
