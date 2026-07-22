FROM rust:alpine AS builder

RUN apk add --no-cache musl-dev ffmpeg-dev pkgconfig

WORKDIR /usr/src/app

COPY . .

RUN cargo build --release --bin stream-proxy-rust

FROM alpine:latest

RUN apk add --no-cache ca-certificates ffmpeg-libs

COPY --from=builder /usr/src/app/target/release/stream-proxy-rust /usr/local/bin/stream-proxy-rust

ENV PORT=8888
ENV ADDRESS=0.0.0.0

EXPOSE 8888

ENTRYPOINT ["/usr/local/bin/stream-proxy-rust"]
