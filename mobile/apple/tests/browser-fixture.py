#!/usr/bin/env python3
"""Loopback-only WebKit fixture; no third-party sites or credentials."""
import http.server
import json
import sys
import time
import io
import wave
from urllib.parse import urlsplit

requests = []
class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_POST(self): self.do_GET()
    def do_GET(self):
        path = urlsplit(self.path).path
        body = self.rfile.read(int(self.headers.get('Content-Length', 0))).decode() if self.command == 'POST' else ''
        if path == '/bridged-external-redirect':
            self.send_response(302)
            self.send_header('Location', 'http://localhost:' + str(self.server.server_port) + '/child')
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        if path in ('/redirect-ad', '/redirect-good', '/redirect-detection'):
            self.send_response(302)
            location = ('http://127.0.0.1:' + str(self.server.server_port) + '/detection?redirected=1') if path == '/redirect-detection' else ('/ad' if path == '/redirect-ad' else '/child')
            self.send_header('Location', location)
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        if path == '/requests':
            data = json.dumps(requests).encode(); content = 'application/json'
        else:
            requests.append({'path': path, 'method': self.command, 'body': body,
                             'cookie': self.headers.get('Cookie', ''), 'userAgent': self.headers.get('User-Agent', '')})
            if path == '/.well-known/playbridge-app.json' and self.headers.get('Host', '').startswith('127.0.0.1:') and any(mode in sys.argv for mode in ('--detection-policy', '--bridged-apps')):
                data = json.dumps({'protocol': 'playbridge-app-v1', 'name': 'Fixture', 'start_url': '/'}).encode()
            elif path == '/detection':
                data = ("<html><title>Detection</title><script>window.detectorAtStart=window.__playbridgeDetectionEnabled;addEventListener('message',e=>{window.frameDetectorState=e.data.detector;});</script><iframe src='http://localhost:" + str(self.server.server_port) + "/detection-frame'></iframe></html>").encode()
            elif path == '/detection-frame':
                data = b"<html><script>parent.postMessage({detector:window.__playbridgeDetectionEnabled},'*');addEventListener('message',()=>parent.postMessage({detector:window.__playbridgeDetectionEnabled},'*'));</script></html>"
            elif path == '/playback.wav':
                output = io.BytesIO()
                with wave.open(output, 'wb') as audio:
                    audio.setnchannels(1); audio.setsampwidth(2); audio.setframerate(8000)
                    audio.writeframes(bytes(8000 * 2 * 5))
                data = output.getvalue()
            elif path == '/playback':
                data = b'''<html><title>Playback</title><audio id="media" loop src="/playback.wav"></audio>
                <script>addEventListener('message', e => { if(e.data==='play')document.querySelector('audio').play(); });</script></html>'''
            elif path == '/playback-frame':
                data = ('<html><title>PlaybackFrame</title><iframe src="http://localhost:' + str(self.server.server_port) + '/playback"></iframe></html>').encode()
            elif path == '/image-actions':
                data = b'''<html><head><title>Image Actions</title><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:16px"><picture><source srcset="/protected.png?best=1 1x">
                <img src="/invalid-image" alt="Standalone fixture image" width="150" height="100"></picture>
                <p><a href="/child"><img src="/protected.png?linked=1" alt="Linked fixture image" width="150" height="100"></a></p>
                <p><a href="/child" style="position:relative;display:inline-block"><img src="/protected.png?covered=1" alt="Covered linked fixture image" width="150" height="100"><span style="position:absolute;left:0;top:0;right:0;bottom:0"></span></a></p>
                <p><span style="position:relative;display:inline-block"><img src="/protected.png?covered=2" alt="Covered standalone fixture image" width="150" height="100"><span style="position:absolute;left:0;top:0;right:0;bottom:0"></span></span></p></body></html>'''
            elif path == '/protected.png':
                if 'image_auth=ok' not in self.headers.get('Cookie', '') or self.headers.get('User-Agent') != 'PlayBridgeImageFixture/1.0':
                    self.send_response(403); self.end_headers(); return
                import struct, zlib
                def chunk(kind, payload):
                    return struct.pack('!I', len(payload)) + kind + payload + struct.pack('!I', zlib.crc32(kind + payload) & 0xffffffff)
                data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!IIBBBBB', 2, 2, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(b'\x00\xff\x00\x00\xff\x00\x00' * 2)) + chunk(b'IEND', b'')
                content = 'image/png'
            elif path == '/oversized-image':
                self.send_response(200); self.send_header('Content-Length', str(51*1024*1024)); self.end_headers(); return
            elif path == '/invalid-image':
                data = b'not an image'; content = 'image/png'
            elif path == '/identity':
                data = b'<html><head><title>Identity</title><meta name="viewport" content="width=device-width, initial-scale=1"></head><body>Identity</body></html>'
            elif path == '/' and '--bridged-apps' in sys.argv:
                # Model Movi's canvas + CSS fallback contract without a video element.
                data = b'''<html><head><title>Child</title><meta name="viewport" content="width=device-width,initial-scale=1">
                <style>body{padding:60px 30px}button,a{display:block;margin:12px;padding:12px}movi-player{display:block;height:100px}</style></head><body>
                <movi-player id="player"></movi-player>
                <button onclick="player.dispatchEvent(new Event('playing'))">Play inline canvas</button>
                <button onclick="player.classList.add('movi-pseudo-fullscreen')">Enter canvas fullscreen</button>
                <button onclick="player.dispatchEvent(new Event('pause'))">Pause canvas</button>
                <button onclick="player.classList.remove('movi-pseudo-fullscreen')">Exit canvas fullscreen</button>
                <button onclick="player.remove()">Close canvas player</button>
                <a href="/identity">Leave canvas page</a>
                <script>player.attachShadow({mode:'open'}).appendChild(document.createElement('canvas'));</script>
                </body></html>'''
            elif path == '/parent':
                data = b'''<html><head><title>Parent</title></head><body>
                <a id="link" href="/child" target="_blank">Open</a>
                <form id="form" action="/post" method="post" target="_blank"><input name="value" value="preserved"></form>
                <a id="download" href="/file">Download</a></body></html>'''
            elif path == '/network':
                data = ('''<html><title>Network</title><img src="/image.svg"><script>
                fetch('/fetch-test?token=secret-fixture').then(r=>r.text());
                var xhr=new XMLHttpRequest();xhr.open('POST','/xhr-test');xhr.send('test');
                var controller=new AbortController();fetch('/slow',{signal:controller.signal}).catch(()=>{});controller.abort();
                </script><iframe src="http://localhost:''' + str(self.server.server_port) + '/network-frame"></iframe></html>').encode()
            elif path == '/network-frame':
                data = b"<html><script>fetch('/frame-request?token=secret-fixture').then(r=>r.text())</script></html>"
            elif path == '/image.svg':
                data = b'<svg xmlns="http://www.w3.org/2000/svg" width="2" height="2"><rect width="2" height="2"/></svg>'
            elif path == '/touch-frame':
                data = ('<html><title>Touch</title><iframe src="http://localhost:' + str(self.server.server_port) + '/touch"></iframe></html>').encode()
            elif path in ('/under-script', '/under-copy', '/overlay', '/signin', '/under-allowed'):
                cross = 'http://localhost:' + str(self.server.server_port) + '/landing'
                if path == '/overlay':
                    control = '<a href="/child" target="_blank"><span style="position:fixed;inset:0;opacity:0.01">Overlay link</span></a>'
                elif path == '/under-allowed':
                    control = '<a href="/under-allowed" target="_blank" onclick="setTimeout(()=>location.href=\'' + cross + '\',100)">Allowed link</a>'
                else:
                    action = '' if path == '/signin' else ("window.open(location.href,'_blank');" if path == '/under-copy' else "window.open('/child','_blank');")
                    control = '<button onclick="' + action + "location.href='" + cross + "'" + '">Continue</button>'
                data = ('<html><title>Touch</title><meta name="viewport" content="width=device-width,initial-scale=1"><body>' + control + '</body></html>').encode()
            elif path == '/touch':
                data = b'''<html><head><title>Touch</title><meta name="viewport" content="width=device-width,initial-scale=1"></head><body>
                <a href="/child" target="_blank">Open link</a>
                <a href="http://localhost:PORT/child" target="_blank">Cross-site link</a>
                <form action="/post" method="post" target="_blank"><input name="value" value="preserved"><button>Submit form</button></form>
                <button onclick="window.open('/child','_blank')">Script popup</button>
                <button onclick="window.open('/child','_blank');window.open('/extra','_blank')">Burst popup</button>
                <button onclick="setTimeout(function(){window.open('/child','_blank')},1500)">Delayed popup</button>
                </body></html>'''
                data = data.replace(b'PORT', str(self.server.server_port).encode())
            elif path in ('/popup-frame-window', '/popup-frame-link'):
                action = "window.open('/frame-child','_blank');" if path.endswith('window') else "var a=document.createElement('a');a.href='/frame-child';a.target='_blank';document.body.appendChild(a);a.click();"
                data = ("<html><script>setTimeout(function(){" + action + "parent.postMessage({popupAuditDone:true,active:!!(navigator.userActivation&&navigator.userActivation.isActive)},'*');},100);</script></html>").encode()
            elif path in ('/file', '/slow', '/retry'):
                data = b'playbridge-download-fixture\n' * (100000 if path == '/slow' else 100)
            else:
                data = b'<html><title>Child</title><body>Ready</body></html>'
            content = 'application/octet-stream' if path in ('/file', '/slow', '/retry') else 'text/html'
        if path == '/image.svg': content = 'image/svg+xml'
        if path == '/playback.wav': content = 'audio/wav'
        self.send_response(200)
        self.send_header('Content-Type', content)
        self.send_header('Content-Length', str(len(data)))
        if path == '/image-actions': self.send_header('Set-Cookie', 'image_auth=ok; Path=/')
        if path == '/parent': self.send_header('Set-Cookie', 'fixture=present; Path=/')
        if content == 'application/octet-stream': self.send_header('Content-Disposition', 'attachment; filename="sample.bin"')
        self.end_headers()
        try:
            if path == '/retry' and sum(r['path'] == path for r in requests) == 1:
                self.wfile.write(data[:10]); self.close_connection = True; return
            for i in range(0, len(data), 4096):
                self.wfile.write(data[i:i+4096]); self.wfile.flush()
                if path == '/slow': time.sleep(.03)
        except (BrokenPipeError, ConnectionResetError): pass
# Only the new mode starts TLS. Its certificate is anchored by the isolated
# test delegate, never installed into the Simulator/system trust store.
if '--site-tools-ui' in sys.argv:
    import os, ssl, threading
    tls = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(os.environ['PB_BROWSER_TLS_CERT'], os.environ['PB_BROWSER_TLS_KEY'])
    tls.socket = context.wrap_socket(tls.socket, server_side=True)
    with open(sys.argv[1] + '-tls', 'w') as f:
        f.write('https://127.0.0.1:' + str(tls.server_port))
    threading.Thread(target=tls.serve_forever, daemon=True).start()
server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
with open(sys.argv[1], 'w') as f: f.write('http://127.0.0.1:' + str(server.server_port))
server.serve_forever()
