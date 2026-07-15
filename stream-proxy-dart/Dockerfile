# Stage 1: Build the Dart standalone AOT executable
FROM dart:stable AS build

WORKDIR /app

# Copy dependency configuration and fetch packages
COPY pubspec.yaml ./
RUN dart pub get

# Copy the rest of the source files
COPY . .

# Compile to a native self-contained binary
RUN mkdir -p build && dart compile exe bin/stream_proxy.dart -o build/pb-proxy

# Stage 2: Final minimal run container
# Pin Debian so the FFmpeg library ABI remains aligned with the FFI loader.
FROM debian:13-slim

WORKDIR /app

# Install only the FFmpeg runtime library used by the AVIO FFI fallback.
# libavformat61 pulls in libavutil and its other runtime dependencies.
RUN apt-get update && \
    apt-get install -y --no-install-recommends ca-certificates libavformat61 && \
    rm -rf /var/lib/apt/lists/*

# Copy the compiled native binary from Stage 1
COPY --from=build /app/build/pb-proxy /app/pb-proxy

# Expose default proxy port
EXPOSE 8888

# Run the proxy binding to all network interfaces
ENTRYPOINT ["/app/pb-proxy", "-p", "8888", "-a", "0.0.0.0"]
