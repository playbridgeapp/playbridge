#!/usr/bin/env python3
"""Apply deterministic secret-safe diagnostics to generated webhook models.

Wire/Dart default message diagnostics include every field. Keep serialization
unchanged, but don't expose session credentials when a parent message is logged.
"""
from pathlib import Path
import sys
root = Path(sys.argv[1])
p = root / 'kotlin/playbridge/ProgressWebhook.kt'
s = p.read_text()
s = s.replace('result += """url=${sanitize(url)}"""', 'result += "url=[redacted]"')
s = s.replace('result += """bearer_token=${sanitize(bearer_token)}"""', 'result += "bearer_token=[redacted]"')
p.write_text(s)
p = root / 'dart/lib/messages.pb.dart'
s = p.read_text().replace('class ProgressWebhook extends $pb.GeneratedMessage {', '''class ProgressWebhook extends $pb.GeneratedMessage {
  @$core.override
  $core.String toString() => 'ProgressWebhook([redacted])';''')
p.write_text(s)
