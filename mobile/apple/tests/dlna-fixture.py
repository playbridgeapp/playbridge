from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json, sys
from urllib.parse import urlsplit, parse_qs
state = {'state': 'STOPPED', 'actions': []}
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def reply(self, body, mime='text/xml'):
        body = body.encode()
        self.send_response(200); self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_GET(self):
        if self.path == '/query/device-info': return self.reply('<device-info><friendly-device-name>Roku Fixture</friendly-device-name></device-info>')
        if self.path == '/query/apps': return self.reply('<apps><app id="15985">Play on Roku</app></apps>')
        if self.path == '/query/media-player': return self.reply('<player state="play"><position>12000</position><duration>60000</duration></player>')
        if self.path == '/actions': return self.reply(json.dumps(state['actions']), 'application/json')
        self.reply('''<root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><device>
<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>DLNA Fixture</friendlyName><manufacturer>PlayBridge</manufacturer><modelName>Fixture</modelName><UDN>uuid:fixture</UDN><serviceList><service>
<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><SCPDURL>/service.xml</SCPDURL><controlURL>/control</controlURL><eventSubURL>/events</eventSubURL>
</service></serviceList></device></root>''')
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', '0'))).decode()
        if self.path.startswith('/launch/') or self.path.startswith('/keypress/'):
            state['actions'].append({'action': urlsplit(self.path).path, 'query': parse_qs(urlsplit(self.path).query)})
            return self.reply('')
        action = self.headers['SOAPAction'].strip('"').split('#')[-1]
        state['actions'].append({'action': action, 'body': body})
        if action in ['Play', 'Pause', 'Stop']: state['state'] = {'Play':'PLAYING','Pause':'PAUSED_PLAYBACK','Stop':'STOPPED'}[action]
        values = ''
        if action == 'GetTransportInfo': values = '<CurrentTransportState>'+state['state']+'</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>'
        if action == 'GetPositionInfo': values = '<Track>1</Track><TrackDuration>00:01:00</TrackDuration><RelTime>00:00:12</RelTime>'
        self.reply('<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:'+action+'Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">'+values+'</u:'+action+'Response></s:Body></s:Envelope>')
server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
with open(sys.argv[1], 'w') as f: f.write(str(server.server_port))
server.serve_forever()
