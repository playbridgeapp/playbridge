# PlayBridge Stream Proxy — Deployment Guidelines

This guide outlines security, networking, and performance configuration items for deploying the PlayBridge Stream Proxy (`pb-proxy`) in various environments (such as a VPS, home server, or cloud container).

---

## 🔒 1. Security & Access Control

### Enforce Token Authentication
Never expose the proxy to a local network or the public internet without an API password.
- In `docker-compose.yml`, configure the `PASSWORD` environment variable:
  ```yaml
  environment:
    - PASSWORD=your_secure_random_token_here
  ```
- **Fallback**: If the `PASSWORD` environment variable (or `--password` command line argument) is left empty or omitted, the proxy **automatically generates a secure random 128-bit base64url token on startup** and prints it to stdout. The server will *never* run as an open unauthenticated proxy.
- All client requests must then authenticate by sending `?token=YOUR_TOKEN` as a query parameter or `Authorization: Bearer YOUR_TOKEN` in the HTTP headers.

### Restrict Network Bindings (Firewalls)
If the proxy is intended only for your home devices or private VPN (e.g. Tailscale/WireGuard):
- Use `ufw` or cloud security group firewalls to block port `8888` from public inbound traffic.
- Only allow access from your specific subnet range (e.g., `192.168.1.0/24`).

### CORS Management
The proxy returns `Access-Control-Allow-Origin: *` to permit playback on web receivers (like Chrome cast receivers) or in-browser players. Ensuring the **API token is active** prevents unauthorized web clients from abusing this open CORS policy.

---

## 🌐 2. TLS Termination & Reverse Proxy

Modern browsers and cast protocols (like Google Cast / Chromecast) enforce strict mixed-content rules and will refuse to play streams over plaintext `http://` if the controlling app is running on `https://`.

Always place the proxy behind a reverse proxy (such as **Caddy**, **Nginx**, or **Traefik**) to handle SSL/TLS termination automatically.

### Example: Caddyfile
Caddy automatically provisions Let's Encrypt certificates:
```text
pb-proxy.yourdomain.com {
    reverse_proxy localhost:8888
}
```

### Example: Nginx configuration
```nginx
server {
    listen 443 ssl;
    server_name pb-proxy.yourdomain.com;

    ssl_certificate /etc/letsencrypt/live/pb-proxy.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/pb-proxy.yourdomain.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8888;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Disable buffering to allow instant streaming chunks
        proxy_buffering off;
    }
}
```

---

## ⚡ 3. Caching & Bandwidth Optimization

Streaming video consumes substantial network bandwidth. Deploying a caching proxy layer or CDN (like Cloudflare) in front of the proxy is highly recommended.

### HLS Segment Caching (`.ts`, `.m4s`, `.mp4` chunks)
- Cache segments at the edge/reverse proxy layer.
- Because segments are immutable media fragments, caching them saves up to 90% of your proxy server's outbound bandwidth.

### Disable Playlist Caching (`.m3u8`)
- Ensure playlists are **never cached** (or cached for a maximum of 1 second). Caching manifests blocks players from receiving real-time HLS segment updates, causing streams to freeze or loop.

---

## ⚙️ 4. System Tuning & Limits

### Increase File Descriptor Limits (uLimit)
HLS video playback requests dozens of files in parallel, creating many concurrent connections. Under heavy loads, the default system open-files limit (often `1024`) can be exceeded, causing connection drops.

- **On the host machine**, increase the limits in `/etc/security/limits.conf`:
  ```text
  * soft nofile 65535
  * hard nofile 65535
  ```
- **In Docker Compose**, specify limits directly in the service definition:
  ```yaml
  services:
    pb-proxy:
      ...
      ulimits:
        nofile:
          soft: 65535
          hard: 65535
  ```

### Health Monitoring
The proxy exposes public unauthenticated endpoints `/health` and `/ping` for load balancers, reverse proxies, and healthcheck utilities.
- Configure a standard `healthcheck` in `docker-compose.yml` to automatically verify container state:
  ```yaml
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8888/health"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 10s
  ```

---

## 🔒 5. Upstream SSL/TLS Security

### Bad/Expired Certificate Bypass
To prevent playback failure on self-hosted or private network streams (which often use expired or self-signed certificates), the proxy's HTTP client is configured to automatically ignore invalid TLS certificates:
- The proxy executes `badCertificateCallback = (cert, host, port) => true` on all upstream requests.
- This ensures maximum connection reliability without forcing users to configure custom trust stores.

---

## 📦 6. FFmpeg Dependency Validation (AVIO Fallback)

The proxy features an FFmpeg AVIO FFI fallback to bypass advanced TLS fingerprint blocks (e.g. from CDNs like `strmd.st`).

- **Docker Deployments**: The provided `Dockerfile` automatically installs `ffmpeg` and `libavformat-dev`. The fallback is active out-of-the-box.
- **Bare-metal Deployments**: If running the binary natively on Linux, macOS, or Windows, verify that FFmpeg libraries are installed on the system path. Check the application logs at startup to confirm there are no FFI initialization errors.
