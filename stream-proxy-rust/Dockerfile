FROM rust:bookworm AS builder

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        pkg-config \
        libavformat-dev \
        libavutil-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /usr/src/app

COPY stream-proxy-rust ./stream-proxy-rust
COPY cast/net-policy ./cast/net-policy
# Seed standalone resolution with workspace versions. Cargo prunes unrelated entries.
COPY Cargo.lock ./stream-proxy-rust/Cargo.lock

RUN cargo build --release --manifest-path stream-proxy-rust/Cargo.toml --bin stream-proxy-rust

FROM debian:bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        libavformat59 \
        libavutil57 \
        libavcodec59 \
        libswresample4 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 playbridge \
    && useradd --system --uid 10001 --gid playbridge --no-create-home playbridge

COPY --from=builder /usr/src/app/stream-proxy-rust/target/release/stream-proxy-rust /usr/local/bin/stream-proxy-rust

ENV PORT=8888
ENV ADDRESS=0.0.0.0

USER playbridge

EXPOSE 8888

ENTRYPOINT ["/usr/local/bin/stream-proxy-rust"]
