import http.server, sys, time, json, urllib.parse
class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_POST(self):
        parsed = urllib.parse.urlsplit(self.path)
        token = urllib.parse.parse_qs(parsed.query).get('token', [''])[0]
        if parsed.path != '/prefix/register' or token != 'a&b+? secret':
            self.send_response(403); self.end_headers(); return
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))))
        assert body['headers']['Authorization'] == 'fixture-secret'
        result = json.dumps({'proxy_url': 'https://remote-fixture.test/s/session/master.m3u8'}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(result))); self.end_headers()
        self.wfile.write(result)
    def do_GET(self):
        path = urllib.parse.urlsplit(self.path).path
        if path == '/download.mp4/':
            assert self.headers.get('Referer') == 'https://example.test/player'
            assert self.headers.get('Authorization') == 'Bearer fixture-secret'
            destination = sys.argv[3] if len(sys.argv) > 3 else 'http://' + self.headers['Host']
            self.send_response(302)
            self.send_header('Location', destination + '/range.mp4')
            self.send_header('Content-Length', '0'); self.end_headers(); return
        if path == '/range.mp4':
            assert self.headers.get('Referer') == 'https://example.test/'
            assert self.headers.get('User-Agent') == 'AppleFixture'
            assert not self.headers.get('Authorization') and not self.headers.get('Cookie')
            ranges = {'bytes=0-1': (b'01', 'bytes 0-1/10'), 'bytes=4-7': (b'4567', 'bytes 4-7/10')}
            body, content_range = ranges[self.headers['Range']]
            self.send_response(206)
            self.send_header('Content-Type', 'video/mp4')
            self.send_header('Content-Range', content_range)
            self.send_header('Accept-Ranges', 'bytes')
            self.send_header('Content-Length', str(len(body))); self.end_headers()
            self.wfile.write(body); return
        if self.path.startswith('/hls/'):
            expected_referer = 'https://example.test/' if self.path.endswith('.jpg') else 'https://example.test/player'
            if self.headers.get('Referer') != expected_referer:
                self.send_response(403); self.end_headers(); return
            if self.path.endswith('.jpg') and (self.headers.get('User-Agent') != 'AppleFixture' or self.headers.get('Authorization') or self.headers.get('Cookie')):
                self.send_response(403); self.end_headers(); return
            if self.path == '/hls/master.m3u8':
                body = b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000\nmedia.m3u8\n'
            elif self.path == '/hls/media.m3u8':
                segment = (sys.argv[3] + '/hls/segment.jpg') if len(sys.argv) > 3 else 'segment.jpg'
                body = ('#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\n' + segment + '\n#EXT-X-ENDLIST\n').encode()
            else:
                body = bytes([0x47, 0x40, 0x00, 0x10]) + bytes(184)
            self.send_response(200)
            self.send_header('Content-Type', 'image/jpeg' if self.path.endswith('.jpg') else 'application/vnd.apple.mpegurl')
            self.send_header('Content-Length', str(len(body))); self.end_headers()
            self.wfile.write(body); return
        if self.path == '/redirect':
            self.send_response(302); self.send_header('Location', '/body'); self.end_headers(); return
        if self.path == '/body':
            assert self.headers['Referer'] == 'https://example.test/player'
            assert self.headers['User-Agent'] == 'AppleFixture'
            body = b'A' * (2 * 1024 * 1024)
            self.send_response(200)
        elif self.path == '/range':
            assert self.headers['Range'] == 'bytes=4-7'
            body = b'4567'
            self.send_response(206); self.send_header('Content-Range', 'bytes 4-7/10')
        else:
            body = b'S' * (2 * 1024 * 1024)
            self.send_response(200)
        self.send_header('Content-Length', str(len(body))); self.end_headers()
        if self.path == '/slow':
            self.wfile.write(body[:65536]); self.wfile.flush(); time.sleep(4); body = body[65536:]
        try: self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError): pass
server = http.server.ThreadingHTTPServer((sys.argv[2] if len(sys.argv) > 2 else '127.0.0.1', 0), Handler)
with open(sys.argv[1], 'w') as port: port.write(str(server.server_port))
server.serve_forever()
