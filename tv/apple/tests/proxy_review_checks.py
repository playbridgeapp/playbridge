"""Exercise the production Swift proxy over real loopback TCP sockets."""
import http.server
import socket
import subprocess
import sys
import threading
import time

seen = []

class Upstream(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        seen.append(dict(self.headers))
        body = b"ok"
        self.send_response(206)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Content-Range", "bytes 0-1/10")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass

server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
threading.Thread(target=server.serve_forever, daemon=True).start()
process = subprocess.Popen(
    [sys.argv[1], f"http://127.0.0.1:{server.server_port}/video.mp4"],
    stdout=subprocess.PIPE, text=True,
)
try:
    ready = process.stdout.readline().strip()
    assert ready.startswith("READY "), ready
    port = int(ready.split()[1])
    with socket.create_connection(("127.0.0.1", port), timeout=5) as client:
        client.sendall(b"GET /video.mp4 HTTP/1.1\r\nHost: localhost\r\n")
        time.sleep(0.1)
        assert not seen, "Proxy forwarded incomplete headers"
        client.sendall(b"Range: bytes=0-1\r\n\r\n")
        chunks = []
        while chunk := client.recv(4096):
            chunks.append(chunk)
        response = b"".join(chunks)
        assert response.startswith(b"HTTP/1.1 206"), response
        assert response.endswith(b"\r\n\r\nok"), response
        headers = {k.lower(): v for k, v in seen[0].items()}
        assert headers["range"] == "bytes=0-1", headers
        assert headers["x-test"] == "fixture", headers
    for request in [b"GET ? HTTP/1.1\r\n\r\n", b"POST / HTTP/1.1\r\n\r\n"]:
        with socket.create_connection(("127.0.0.1", port), timeout=5) as client:
            client.sendall(request)
            assert client.recv(4096) == b""
    assert len(seen) == 1, "Malformed requests reached upstream"
    print("PASS: async proxy startup, fragmented HTTP headers, Range forwarding and malformed requests")
finally:
    process.terminate()
    process.wait(timeout=5)
    server.shutdown()
