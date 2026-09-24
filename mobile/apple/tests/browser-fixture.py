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
        if path in ('/redirect-ad', '/redirect-good'):
            self.send_response(302)
            self.send_header('Location', '/ad' if path == '/redirect-ad' else '/child')
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        if path == '/requests':
            data = json.dumps(requests).encode(); content = 'application/json'
        else:
            requests.append({'path': path, 'method': self.command, 'body': body,
                             'cookie': self.headers.get('Cookie', ''), 'userAgent': self.headers.get('User-Agent', '')})
            if path == '/playback.wav':
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
            elif path == '/identity':
                data = b'<html><head><title>Identity</title><meta name="viewport" content="width=device-width, initial-scale=1"></head><body>Identity</body></html>'
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
            elif path == '/touch':
                data = b'''<html><head><title>Touch</title><meta name="viewport" content="width=device-width,initial-scale=1"></head><body>
                <a href="/child" target="_blank">Open link</a>
                <form action="/post" method="post" target="_blank"><input name="value" value="preserved"><button>Submit form</button></form>
                <button onclick="window.open('/child','_blank')">Script popup</button>
                <button onclick="window.open('/child','_blank');window.open('/extra','_blank')">Burst popup</button>
                <button onclick="setTimeout(function(){window.open('/child','_blank')},1500)">Delayed popup</button>
                </body></html>'''
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
server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
with open(sys.argv[1], 'w') as f: f.write('http://127.0.0.1:' + str(server.server_port))
server.serve_forever()
