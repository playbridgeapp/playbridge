#!/usr/bin/env zsh
# Regenerate all language bindings from proto/messages.proto.
# Run this whenever the proto changes, then commit the generated/ output.
#
# Modes:
#   ./generate.sh           Regenerate in place (default; commits expected after).
#   ./generate.sh --check   Regenerate into a temp dir and diff against the committed
#                           generated/ files. Exits 0 if identical, non-zero on drift.
#                           Use this in CI to catch missed regenerations.
#
# Prerequisites:
#   brew install protobuf swift-protobuf
#   go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
#   npm install -g ts-proto
#   dart pub global activate protoc_plugin
#   # Wire (Kotlin): generate.sh auto-fetches wire-compiler-5.1.0.jar to ~/.cache
#   # Requires a JDK on PATH (`java`)
source ~/.zshrc 2>/dev/null

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROTO_DIR="$SCRIPT_DIR/proto"

CHECK_MODE=0
if [[ "$1" == "--check" ]]; then
  CHECK_MODE=1
  OUT="$(mktemp -d -t playbridge-protocol-check)"
  echo "==> --check mode: regenerating into $OUT"
else
  OUT="$SCRIPT_DIR/generated"
fi

# Ensure protoc-gen-go and protoc-gen-dart are on PATH
export PATH="$(go env GOPATH)/bin:$HOME/.pub-cache/bin:$PATH"

echo "==> Generating Go"
mkdir -p "$OUT/go"
protoc \
  --proto_path="$PROTO_DIR" \
  --go_out="$OUT/go" \
  --go_opt=paths=source_relative \
  messages.proto

echo "==> Generating TypeScript"
mkdir -p "$OUT/typescript"
protoc \
  --proto_path="$PROTO_DIR" \
  --plugin="$( npm root -g )/ts-proto/protoc-gen-ts_proto" \
  --ts_proto_out="$OUT/typescript" \
  --ts_proto_opt=outputJsonMethods=true \
  --ts_proto_opt=useOptionals=messages \
  --ts_proto_opt=snakeToCamel=true \
  messages.proto

echo "==> Generating Swift"
mkdir -p "$OUT/swift"
protoc \
  --proto_path="$PROTO_DIR" \
  --swift_out="$OUT/swift" \
  messages.proto

echo "==> Generating Dart"
mkdir -p "$OUT/dart/lib"
protoc \
  --proto_path="$PROTO_DIR" \
  --dart_out="$OUT/dart/lib" \
  messages.proto

echo "==> Generating Kotlin (Wire)"
WIRE_VERSION="5.1.0"
WIRE_JAR="${WIRE_JAR:-$HOME/.cache/wire-compiler-${WIRE_VERSION}.jar}"
mkdir -p "$(dirname "$WIRE_JAR")"
if [[ ! -f "$WIRE_JAR" ]]; then
  echo "    fetching wire-compiler ${WIRE_VERSION}..."
  curl -fsSL -o "$WIRE_JAR" \
    "https://repo1.maven.org/maven2/com/squareup/wire/wire-compiler/${WIRE_VERSION}/wire-compiler-${WIRE_VERSION}-jar-with-dependencies.jar"
fi
mkdir -p "$OUT/kotlin"
# Clean previous generation so removed messages don't linger
find "$OUT/kotlin" -name '*.kt' -delete 2>/dev/null || true
java -jar "$WIRE_JAR" \
  --proto_path="$PROTO_DIR" \
  --kotlin_out="$OUT/kotlin" \
  messages.proto

if [[ $CHECK_MODE -eq 1 ]]; then
  echo "==> Comparing $OUT against $SCRIPT_DIR/generated"
  # Compare generated source only. Package manifests, lockfiles, placeholder files,
  # and local tool caches are maintained separately and are not emitted by protoc.
  if diff -ruN \
      -x '.dart_tool' \
      -x '.gitkeep' \
      -x 'go.mod' \
      -x 'pubspec.yaml' \
      -x 'pubspec.lock' \
      "$SCRIPT_DIR/generated" "$OUT" > /tmp/playbridge-protocol-diff 2>&1; then
    echo "OK: generated/ is up to date with messages.proto"
    rm -rf "$OUT"
    exit 0
  else
    echo "DRIFT DETECTED — committed generated/ does not match output of generate.sh."
    echo "Run ./generate.sh and commit the result. Diff:"
    cat /tmp/playbridge-protocol-diff
    rm -rf "$OUT"
    exit 1
  fi
fi

echo "Done. Commit the generated/ directory."
