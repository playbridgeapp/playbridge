# Protocol Module

The `protocol` module (`com.playbridge.protocol`) is a shared Kotlin JVM library consumed by both apps via `implementation(project(":protocol"))`.

## Package Structure
```
com.playbridge.protocol/
├── Message.kt           (All shared protocol classes, sealed Command class, parseCommand, and helpers)
└── NsdConstants.kt      (NSD service type and key constants)
```

## Key Components

| File | Contents |
|------|----------|
| NsdConstants.kt | NSD service type and key constants |
| Message.kt | All shared protocol classes, sealed `Command` class, `parseCommand()`, and 14 helper functions |

**Dependencies:** Kotlin JVM, `kotlinx-serialization-json:1.7.3`

---

## Communication Protocol

Commands flow bidirectionally between Phone ↔ TV via WebSocket JSON messages:

### Connection & Authentication Flow

```mermaid
sequenceDiagram
    participant Phone
    participant TV
    
    Phone->>TV: WebSocket connect to ws://ip:8765
    Phone->>TV: {"type": "auth", "pin": "1234"} or {"type": "auth", "token": "..."}
    TV->>Phone: {"type": "auth_response", "success": true, "token": "..."}
    Note over Phone,TV: Authenticated session established
    Phone->>TV: Commands...
    TV->>Phone: Status updates...
```

### Phone → TV Commands

```json
// Play video (with optional headers, content type, and external subtitles)
{"type": "command", "action": "play", "payload": {"url": "...", "title": "...", "headers": {...}, "contentType": "...", "subtitles": ["url1.srt", "url2.vtt"]}}

// Open browser on TV
{"type": "command", "action": "browser", "payload": {"url": "..."}}

// Player control
{"type": "command", "action": "control", "payload": {"command": "pause"}}

// Remote control (D-pad navigation)
{"type": "command", "action": "remote", "payload": {"key": "dpad_up"}}

// Mouse/touchpad control
{"type": "command", "action": "mouse", "payload": {"event": "move", "dx": 10.5, "dy": -3.2}}

// Browser control (refresh, switch engine, maximize/restore video)
{"type": "command", "action": "browser_control", "payload": {"action": "refresh"}}
{"type": "command", "action": "browser_control", "payload": {"action": "maximize_video"}}
{"type": "command", "action": "browser_control", "payload": {"action": "restore_video"}}
{"type": "command", "action": "browser_control", "payload": {"action": "switch_engine"}}

// Context query (ask TV what screen it's on)
{"type": "command", "action": "context_query"}

// Heartbeat
{"type": "ping"}
```

### TV → Phone Responses

```json
// Authentication response
{"type": "auth_response", "success": true, "token": "generated-token"}

// Playback status
{"type": "status", "state": "playing", "position": 12345, "duration": 60000, "title": "..."}

// Context response
{"type": "context", "active": "player"}  // "player", "browser", or "idle"

// Heartbeat
{"type": "pong"}
```
