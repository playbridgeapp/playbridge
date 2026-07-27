FROM rust:bookworm AS builder

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        pkg-config \
        libavformat-dev \
        libavutil-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /usr/src/app

COPY . .

RUN cargo build --release --bin stream-proxy-rust

FROM debian:bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        libavformat59 \
        libavutil57 \
        libavcodec59 \
        libswresample4 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /usr/src/app/target/release/stream-proxy-rust /usr/local/bin/stream-proxy-rust

ENV PORT=8888
ENV ADDRESS=0.0.0.0

EXPOSE 8888

ENTRYPOINT ["/usr/local/bin/stream-proxy-rust"]
