# Receiver-Owned Control v1 Plan

## Status

Implemented on the current feature branch across the additive contract, generated
models, reusable Rust receiver/runtime, CLI/MCP, Android TV receiver, Android
phone sender, Desktop receiver, Apple TV receiver, and Apple phone sender. The
implementations advertise and negotiate the v1 feature flags while retaining the
compatible legacy command surface for older senders and receivers.

## Goal

Make a PlayBridge receiver easy to control from Android, iOS, approved websites,
agents, and scripts without exposing transport-process lifecycle as product state.

The receiver owns playback and its active queue. Any locally approved controller
with a valid receiver credential can inspect and mutate that state. Closing a
controller or its WebSocket connection must not stop playback; only an explicit
stop command does.

The public model is:

```text
Receiver             shared playback and queue state
Credential           permission to control one receiver
Connection           temporary authenticated transport
Controller           phone, website gateway, agent, CLI, or script
```

CLI/MCP session IDs remain an internal or exceptional mechanism for sender-owned
resources such as a local-file proxy, pairing in progress, or non-PlayBridge
transports. They are not the identity of receiver playback.

## Current implementation

The existing protocol and Android implementation already provide much of the
required foundation:

- Android TV accepts multiple authenticated WSS clients.
- Receiver state changes are broadcast to all authenticated clients.
- The TV's `PlaybackCoordinator` owns the live queue and current index.
- `playlist` replaces the queue and starts playback.
- `queue_add` appends without replacing playback.
- `playlist_jump` selects a zero-based queue index.
- `context_query` replays receiver state after reconnect.
- Android library casting lazily resolves and appends episodes.
- Android website casting uses the approved phone as a security gateway and
  lazily supplies page-owned items.
- `playlist_status` echoes episode metadata used by queue reattachment and watch
  progress tracking.
- A normal WSS disconnect does not stop receiver playback.

The sender-to-receiver WSS protocol itself does not currently use a general cast
session ID. The session-ID friction observed in CLI/MCP is introduced by the CLI's
managed child-process lifecycle rather than by the receiver protocol.

## Current gaps

### Queue identity

Queue selection is index-based. Concurrent append, remove, or move operations can
change an index before another controller acts on it. Queue items need stable IDs.

There is also no identity for the current replacement playlist. Long-running
Android library, linked-page, agent, or script automation cannot distinguish its
playback from a newer playlist installed by another controller.

### Command confirmation

Commands have no request ID and receivers do not send a targeted result. A sender
can tell that it wrote a frame, but not that the receiver applied it. Invalid
indices, stale automation, queue limits, and deduplication are therefore ambiguous.

### Queue operations

The protocol supports replace, single-item append, and index jump. It lacks remove,
move, explicit queue query, batch append, and receiver-confirmed clear behavior.

### Query routing

Android TV currently loses the originating WSS connection when it emits an
`IncomingMessage`. A `context_query` consequently causes context and player state
to be rebroadcast to every client instead of replying only to its requester.

### Tracking across controllers

Android watch tracking partly combines receiver events with identity remembered by
the phone that initiated the cast. A different controller replacing playback can
leave stale local identity until defensive title/context checks catch it.

### Queue size

Every `playlist_status` contains the complete queue and is sent again after queue
changes and resynchronization. This is simple and useful for reconnect, but should
be explicitly bounded.

## V1 decisions

### Receiver is authoritative

The receiver owns:

- the current playback identifier;
- ordered queue items;
- the current item;
- playback state and position;
- a monotonic queue revision.

Controllers may connect, mutate state, receive updates, and disconnect without
owning playback lifetime.

### Bounded active queue

V1 will keep full `playlist_status` snapshots rather than introduce paging and
deltas immediately.

- Maximum active queue: 200 items.
- Maximum items in one append command: 50.
- Larger catalogs use the existing lazy-window approach.
- Queue status contains compact item summaries and never echoes URLs, headers, or
  other sensitive media request data.

This matches the existing linked-page session limit and keeps phone UI,
reconnection, and tracking logic straightforward. Paging and delta sync are
deferred until a demonstrated use case requires more than 200 receiver-resident
items.

### Stable identifiers

Add these identifiers:

- `requestId`: supplied by a controller to correlate a command result.
- `playbackId`: generated by the receiver whenever `playlist` replaces playback.
- `itemId`: supplied by a controller or generated by the receiver for each item.
- `queueRevision`: incremented whenever queue order, membership, or current item
  changes.

Replacement creates a new `playbackId`. Append, remove, move, and jump preserve it.

### Optional stale-automation guard

Queue mutations may include `ifPlaybackId`. The receiver rejects the command with
`stale_playback` when another controller has replaced playback.

This guard is recommended for Android lazy queueing, linked websites, and
long-running scripts. Interactive human controls may omit it.

### Explicit stop semantics

- Closing WSS means detach.
- MCP shutdown means detach for receiver-owned PlayBridge playback.
- `control/stop` is the only ordinary operation that stops receiver playback.
- Sender-owned local proxy teardown may make that sender's local media unavailable,
  but must not be disguised as normal receiver session ownership.

## Protocol changes

All V1 changes are additive and capability-gated. Legacy commands and index fields
remain accepted.

### Capability advertisement

Add a `features` array to `auth_response`, with initial values such as:

```json
{
  "type": "auth_response",
  "success": true,
  "features": [
    "queue_crud_v1",
    "stable_item_ids",
    "command_results"
  ]
}
```

New senders use V1 operations only when advertised. Receivers and senders continue
to tolerate unknown fields and feature names.

### Command envelope

Add optional `requestId` to the authenticated command envelope.

### Media and status models

Add optional fields:

- `PlayPayload.itemId`
- `PlaylistStatus.playbackId`
- `PlaylistStatus.queueRevision`
- `PlaylistStatus.currentItemId`
- `PlaylistStatusItem.itemId`
- `PlaylistStatusItem.tmdbId`
- `Status.playbackId`
- `Status.currentItemId`

The receiver assigns missing item IDs for legacy senders. Sensitive fields such as
media URLs and request headers remain excluded from receiver status frames.

### Command result

Add a receiver-to-sender `command_result` frame:

```json
{
  "type": "command_result",
  "requestId": "req-123",
  "ok": true,
  "playbackId": "pb-456",
  "queueRevision": 18
}
```

Structured errors include:

- `stale_playback`
- `item_not_found`
- `queue_full`
- `invalid_command`
- `unsupported`
- `no_active_playback`

The result is sent only to the connection that issued the command. Subsequent
state changes remain broadcast to every authenticated controller.

### Queue commands

Retain `playlist`, `queue_add`, and `playlist_jump`, while adding:

- `queue_query`
- `queue_remove`
- `queue_move`
- `queue_clear`

`queue_add` gains a batch `items` form while retaining legacy `item`.

`playlist_jump` gains `itemId` while retaining legacy `index`. New clients prefer
item IDs.

Mutations accept optional `ifPlaybackId`.

### Queue snapshot

V1 continues to use `playlist_status` as the canonical complete snapshot, enriched
with playback, item, and revision identifiers. At the 200-item bound, the snapshot
remains small enough for LAN use and simple reconnect behavior.

`queue_query` returns a targeted snapshot to its requester. Broadcast snapshots are
still emitted after queue mutations and current-item changes.

## Android TV implementation

### Connection-aware command routing

Replace the anonymous incoming flow with a routed model such as:

```text
InboundCommand(connectionId, message)
```

Add `sendTo(connectionId, json)` alongside the existing broadcast method. Pairing
and authentication remain handled inside `WebSocketServer`.

### Receiver queue state

Extend or wrap `PlaybackCoordinator` state with:

- `playbackId`
- `queueRevision`
- stable item IDs
- a 200-item limit

Add operations for batch append, remove, move, clear, and jump by item ID. Preserve
legacy index navigation.

Queue mutations must be serialized so commands arriving from different clients
cannot interleave partially. A mutation either applies completely or returns an
error.

### Results and broadcasts

- Return `command_result` to the originating connection.
- Broadcast the resulting `playlist_status` to all clients after a successful
  mutation.
- Make `context_query` and `queue_query` requester-targeted.
- Preserve normal status, queue-change, and playback broadcasts for observers.

An accepted result should mean the queue owner applied the mutation, not merely
that `ServerService` placed an item into an intermediate pending queue.

### Tracking metadata

Echo the compact tracking fields already carried by `visualMetadata`, adding at
least TMDB ID and stable item identity. Continue excluding URLs and headers.

## Android phone implementation

### Connection state

Parse and retain:

- receiver feature flags;
- `playbackId`;
- `queueRevision`;
- `currentItemId`;
- stable queue item IDs;
- correlated command results.

Keep legacy parsing for receivers that only send index-based `playlist_status`.

### Library tracking

- Reset or reattach tracking when `playbackId` changes.
- Identify the current item by `currentItemId`, with index fallback for legacy
  receivers.
- Prefer receiver-echoed TMDB/season/episode metadata over identity remembered only
  by the initiating phone.
- Preserve current title and freshness safeguards during migration.

Another controller may pause, seek, jump, or append without taking ownership of
library tracking. Replacing the playlist creates a new playback identity and ends
or reattaches the old tracking context explicitly.

### Android lazy queue coordinator

Capture the receiver's `playbackId` after the initial playlist command. Include it
as `ifPlaybackId` on subsequent lazy `queue_add` commands. Stop the old plan when
the receiver reports `stale_playback`.

Use stable item IDs to reconcile the phone plan with the TV queue. Retain the
existing metadata-based fallback for legacy receivers.

### Website casting

The website continues to operate through the approved Android phone:

```text
Website -> Android consent/network-policy layer -> authenticated receiver
```

The website never receives the TV credential. The linked-page coordinator records
the resulting `playbackId`, guards later appends with `ifPlaybackId`, and ends its
automation when playback is replaced.

The existing origin, header, private-network, and item-count protections remain in
force.

## CLI, MCP, agents, and scripts

PlayBridge receiver operations become device-centric:

- `get_state(device)`
- `playlist_set(device, items, startItemId?)`
- `queue_add(device, items, ifPlaybackId?)`
- `queue_remove(device, itemIds, ifPlaybackId?)`
- `queue_move(device, itemId, beforeItemId?, ifPlaybackId?)`
- `queue_jump(device, itemId)`
- `queue_clear(device, ifPlaybackId?)`
- `control(device, command)`

The CLI uses the saved receiver credential to open or reuse an authenticated WSS
connection, submits a request ID, waits for the targeted receiver result, and
detaches without stopping playback.

MCP may retain connections internally for latency, but connection lifetime and
process-local session IDs are not exposed as ordinary receiver playback state.

Scripts may use one-shot CLI commands, a long-lived CLI event stream, or direct WSS.
All paths use the same protocol semantics.

## Compatibility and cross-platform ripple

The AsyncAPI contract is the source of truth. Implementation order for schema work:

1. Update `protocol/asyncapi.yaml`.
2. Update `protocol/docs/WSS_FLOW.md`.
3. Mirror generated payload changes in `protocol/proto/messages.proto`.
4. Regenerate Kotlin, Swift, and Dart bindings.
5. Update hand-written JSON consumers.

Affected consumers include:

- shared Android protocol parsing/building;
- Android phone sender and Android TV receiver;
- Rust Cast Core and CLI/MCP;
- Apple phone and Apple TV;
- Desktop sender and receiver;
- any extension bridge code that manually handles affected fields.

Old clients continue using full snapshots and indexes. New clients gate CRUD and
stable-ID behavior on receiver features. New receivers continue accepting legacy
single-item `queue_add` and index-based `playlist_jump`.

## Rollout phases

### Phase 1: contract and identity

- Add feature advertisement, request IDs, playback IDs, item IDs, queue revision,
  command results, and queue limits.
- Update protocol documentation and generated models.

### Phase 2: Android TV receiver

- Preserve command origin.
- Serialize queue mutations.
- Implement stable IDs and queue CRUD.
- Add targeted results and queries.
- Enforce resource limits.

### Phase 3: Android phone

- Parse V1 state and results.
- Migrate library tracking to playback/item identity.
- Guard library and linked-page lazy queueing.
- Preserve legacy fallback behavior.

### Phase 4: CLI/MCP

- Add device-centric PlayBridge operations.
- Remove ordinary PlayBridge control dependence on managed send sessions.
- Detach without sending stop.
- Keep sender sessions only for sender-owned resources and other transports.

### Phase 5: remaining platforms (implemented)

- Update Apple and Desktop receivers.
- Update Apple and Desktop senders.
- Update Rust receiver/runtime consumers and any exposed ABI JSON where needed.

### Deferred: queue sync v2

Only if real requirements exceed the 200-item active queue, design a separately
advertised protocol with paged queue reads and delta events. Do not overload V1
`playlist_status` with partial snapshots because legacy clients assume it is
complete.

## Required tests

### Protocol

- AsyncAPI integrity and local-reference validation.
- Generated binding consistency.
- Additive parsing by legacy/tolerant consumers.
- Feature-gated behavior against older receivers.

### Android TV

- Two authenticated clients can observe and mutate the same queue.
- Disconnecting either client does not stop playback.
- Replace creates a new playback ID.
- Append/remove/move/jump preserve playback ID and advance queue revision.
- Stale `ifPlaybackId` is rejected without changing the queue.
- Duplicate request IDs are idempotent or return the prior result.
- Batch append is atomic and respects the queue limit.
- Results are requester-targeted; state updates reach all clients.
- Sensitive URLs and headers never appear in status or logs.

### Android phone

- Library tracking follows item IDs through append, move, and jump.
- Tracking resets safely when another controller replaces playback.
- Lazy queueing stops after `stale_playback`.
- Linked-page automation cannot append into replacement playback.
- Reconnect reconstructs the current queue and tracking identity.
- Legacy index-only receivers remain usable.

### CLI/MCP and scripts

- Independent controllers append without replacing playback.
- One-shot operations do not stop playback on disconnect.
- Receiver errors are returned to the caller through `command_result`.
- Local-file proxy lifetime remains explicit and tested separately.
- A direct-WSS script and MCP controller can safely share one receiver queue.

## Verification commands

Protocol:

```bash
cd protocol
ruby scripts/check-spec.rb
./generate.sh --check
```

Android TV:

```bash
cd tv/android
zsh -c "source ~/.zshrc && ./gradlew test"
zsh -c "source ~/.zshrc && ./gradlew :player:app:assembleDebug"
```

Android phone:

```bash
cd mobile/android
zsh -c "source ~/.zshrc && ./gradlew :app:testFossDebugUnitTest"
zsh -c "source ~/.zshrc && ./gradlew :app:assembleDebug"
```

Rust/CLI checks must cover Cast Core, receiver runtime, and the CLI after their
corresponding implementation phase.

## Completion criteria

V1 is complete when:

- Android, iOS, agents, and scripts can control the same receiver-owned queue;
- approved websites continue operating only through their approved phone gateway;
- controller disconnect never implicitly stops receiver playback;
- queue mutations have stable identity and receiver-confirmed results;
- Android library tracking survives other controllers' non-replacement actions;
- stale automation cannot mutate replacement playback;
- the active queue is bounded and large catalogs use lazy filling;
- legacy controllers remain functional during rollout.
