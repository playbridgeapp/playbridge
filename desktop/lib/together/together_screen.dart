import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:playbridge_cast_core/playbridge_cast_core.dart';

import 'desktop_together.dart';

/// Watch Together: start or join a party, show a code for a phone, and run the
/// party (video, devices, controls).
class TogetherScreen extends StatelessWidget {
  const TogetherScreen({super.key, required this.together});

  final DesktopTogether together;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: Listenable.merge([together, together.sender]),
        builder: (context, _) => switch (together.state) {
          TogetherIdle() => _StartView(together: together),
          TogetherStarting() || TogetherRegistering() => const _Busy(),
          TogetherShowingCode(:final code) =>
            _CodeView(code: code, onCancel: together.cancelCode),
          TogetherInvited(:final from, :final relay) => _InvitedView(
              from: from,
              relay: relay,
              onJoin: together.acceptInvite,
              onDecline: together.declineInvite,
            ),
          TogetherInParty(:final party) =>
            _PartyView(together: together, party: party),
          TogetherEnded(:final message) =>
            _EndedView(message: message, onDone: together.dismissEnded),
        },
      );
}

const _muted = Color(0x99FFFFFF);

class _Page extends StatelessWidget {
  const _Page({required this.title, this.subtitle, required this.children});

  final String title;
  final String? subtitle;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => ListView(
        padding: const EdgeInsets.fromLTRB(28, 24, 28, 28),
        children: [
          Text(title,
              style:
                  const TextStyle(fontSize: 22, fontWeight: FontWeight.w700)),
          if (subtitle != null) ...[
            const SizedBox(height: 4),
            Text(subtitle!,
                style: const TextStyle(fontSize: 13, color: _muted)),
          ],
          const SizedBox(height: 20),
          ...children,
        ],
      );
}

class _Card extends StatelessWidget {
  const _Card({required this.icon, required this.title, required this.child});

  final IconData icon;
  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) => Container(
        margin: const EdgeInsets.only(bottom: 16),
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: Colors.white.withValues(alpha: 0.035),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: Colors.white.withValues(alpha: 0.10)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              Icon(icon, size: 19, color: Colors.tealAccent),
              const SizedBox(width: 10),
              Text(title,
                  style: const TextStyle(
                      fontSize: 15, fontWeight: FontWeight.w600)),
            ]),
            const SizedBox(height: 12),
            child,
          ],
        ),
      );
}

class _Busy extends StatelessWidget {
  const _Busy();

  @override
  Widget build(BuildContext context) =>
      const Center(child: CircularProgressIndicator());
}

// ─── Start or join ──────────────────────────────────────────────────────────

class _StartView extends StatefulWidget {
  const _StartView({required this.together});

  final DesktopTogether together;

  @override
  State<_StartView> createState() => _StartViewState();
}

class _StartViewState extends State<_StartView> {
  late bool _watchHere = widget.together.watchHereByDefault;
  final _link = TextEditingController();
  late final _relay = TextEditingController(
      text: widget.together.usesSharedRelay ? '' : widget.together.relayUrl);

  @override
  void dispose() {
    _link.dispose();
    _relay.dispose();
    super.dispose();
  }

  Future<void> _paste() async {
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    final text = data?.text?.trim();
    if (text != null && text.isNotEmpty) setState(() => _link.text = text);
  }

  @override
  Widget build(BuildContext context) {
    final together = widget.together;
    final tv = together.sender.selection.isLocal
        ? null
        : together.sender.selectionName;
    return _Page(
      title: 'Watch Together',
      subtitle:
          'Watch the same video in sync with friends, each on your own screen.',
      children: [
        _WatchHereSwitch(
          value: _watchHere,
          tv: tv,
          onChanged: (value) => setState(() => _watchHere = value),
        ),
        const SizedBox(height: 12),
        _Card(
          icon: Icons.celebration_outlined,
          title: 'Start a party',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                together.usesSharedRelay
                    ? "PlayBridge's shared relay: up to 4 devices for 3 hours."
                    : 'Your relay: ${together.relayUrl}',
                style: const TextStyle(fontSize: 13, color: _muted),
              ),
              const SizedBox(height: 12),
              FilledButton.icon(
                onPressed: () => together.start(watchHere: _watchHere),
                icon: const Icon(Icons.play_arrow, size: 18),
                label: const Text('Start a party'),
              ),
              const SizedBox(height: 4),
              Theme(
                data: Theme.of(context)
                    .copyWith(dividerColor: Colors.transparent),
                child: ExpansionTile(
                  tilePadding: EdgeInsets.zero,
                  title: const Text('Use your own relay',
                      style: TextStyle(fontSize: 13, color: _muted)),
                  children: [
                    TextField(
                      controller: _relay,
                      decoration: const InputDecoration(
                        hintText: 'https://your-relay.example.workers.dev',
                        helperText:
                            'Leave empty for the shared relay. Deploy your own from together/room-worker.',
                        isDense: true,
                      ),
                      onSubmitted: (value) =>
                          widget.together.setRelayUrl(value),
                    ),
                    const SizedBox(height: 8),
                    Align(
                      alignment: Alignment.centerRight,
                      child: TextButton(
                        onPressed: () =>
                            widget.together.setRelayUrl(_relay.text),
                        child: const Text('Save relay'),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
        _Card(
          icon: Icons.link,
          title: 'Join with a link',
          child: Row(children: [
            Expanded(
              child: TextField(
                controller: _link,
                decoration: const InputDecoration(
                  hintText: 'Paste an invite link',
                  isDense: true,
                ),
                onSubmitted: (value) =>
                    together.join(value, watchHere: _watchHere),
              ),
            ),
            IconButton(
              tooltip: 'Paste',
              onPressed: _paste,
              icon: const Icon(Icons.content_paste, size: 18),
            ),
            const SizedBox(width: 4),
            FilledButton(
              onPressed: () => together.join(_link.text, watchHere: _watchHere),
              child: const Text('Join'),
            ),
          ]),
        ),
        _Card(
          icon: Icons.qr_code_2,
          title: 'Let a phone add this computer',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Show a code, then enter it or scan it in PlayBridge on the phone '
                "(Watch together → Add a TV). A phone paired with this computer "
                'can add it without a code.',
                style: TextStyle(fontSize: 13, color: _muted),
              ),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: together.showCode,
                icon: const Icon(Icons.pin_outlined, size: 18),
                label: const Text('Show a code'),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _WatchHereSwitch extends StatelessWidget {
  const _WatchHereSwitch({
    required this.value,
    required this.onChanged,
    this.tv,
  });

  final bool value;
  final ValueChanged<bool> onChanged;

  /// The TV this computer sends to, if any.
  final String? tv;

  @override
  Widget build(BuildContext context) => SwitchListTile(
        contentPadding: EdgeInsets.zero,
        value: value,
        onChanged: onChanged,
        // ignore: deprecated_member_use (activeThumbColor needs a newer Flutter than some dev machines)
        activeColor: Colors.tealAccent,
        title: const Text('Watch on this computer'),
        subtitle: Text(
          value
              ? 'The video plays here, in sync with everyone.'
              : tv == null
                  ? 'This computer is a remote for the party.'
                  : 'This computer is a remote; add $tv to watch on it.',
          style: const TextStyle(fontSize: 12, color: _muted),
        ),
      );
}

// ─── Pairing code ───────────────────────────────────────────────────────────

class _CodeView extends StatelessWidget {
  const _CodeView({required this.code, required this.onCancel});

  final PartyCode code;
  final VoidCallback onCancel;

  @override
  Widget build(BuildContext context) => _Page(
        title: 'Add this computer from a phone',
        subtitle:
            'In PlayBridge on the phone, open Watch together → Add a TV, then enter this code or scan it.',
        children: [
          Center(
            child: Column(children: [
              SelectableText(
                code.code,
                style: const TextStyle(
                  fontSize: 44,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 6,
                ),
              ),
              const SizedBox(height: 20),
              if (code.qrSize > 0 &&
                  code.qrModules.length == code.qrSize * code.qrSize)
                Container(
                  color: Colors.white,
                  padding: const EdgeInsets.all(12),
                  child: SizedBox.square(
                    dimension: 200,
                    child: CustomPaint(
                        painter: _QrPainter(code.qrSize, code.qrModules)),
                  ),
                ),
              const SizedBox(height: 20),
              OutlinedButton(onPressed: onCancel, child: const Text('Cancel')),
            ]),
          ),
        ],
      );
}

/// Draws a QR code from rows of `1` (dark) and `0`.
class _QrPainter extends CustomPainter {
  const _QrPainter(this.size, this.modules);

  final int size;
  final String modules;

  @override
  void paint(Canvas canvas, Size canvasSize) {
    final cell = canvasSize.width / size;
    final paint = Paint()..color = Colors.black;
    for (var y = 0; y < size; y++) {
      for (var x = 0; x < size; x++) {
        if (modules.codeUnitAt(y * size + x) == 0x31) {
          canvas.drawRect(
              Rect.fromLTWH(x * cell, y * cell, cell + 0.5, cell + 0.5), paint);
        }
      }
    }
  }

  @override
  bool shouldRepaint(_QrPainter old) =>
      old.size != size || old.modules != modules;
}

class _InvitedView extends StatelessWidget {
  const _InvitedView({
    required this.from,
    required this.relay,
    required this.onJoin,
    required this.onDecline,
  });

  final String from;
  final String relay;
  final VoidCallback onJoin;
  final VoidCallback onDecline;

  @override
  Widget build(BuildContext context) => _Page(
        title: 'Join the watch party?',
        subtitle: '${from.isEmpty ? 'Someone' : from} invited this computer'
            '${relay.isEmpty ? '' : ' (relay: $relay)'}.',
        children: [
          Row(children: [
            FilledButton(onPressed: onJoin, child: const Text('Join')),
            const SizedBox(width: 12),
            OutlinedButton(onPressed: onDecline, child: const Text('Decline')),
          ]),
        ],
      );
}

class _EndedView extends StatelessWidget {
  const _EndedView({required this.message, required this.onDone});

  final String message;
  final VoidCallback onDone;

  @override
  Widget build(BuildContext context) => _Page(
        title: 'Watch Together',
        children: [
          Text(message),
          const SizedBox(height: 16),
          Align(
            alignment: Alignment.centerLeft,
            child: FilledButton(onPressed: onDone, child: const Text('OK')),
          ),
        ],
      );
}

// ─── In a party ─────────────────────────────────────────────────────────────

class _PartyView extends StatefulWidget {
  const _PartyView({required this.together, required this.party});

  final DesktopTogether together;
  final Party party;

  @override
  State<_PartyView> createState() => _PartyViewState();
}

class _PartyViewState extends State<_PartyView> {
  final _video = TextEditingController();
  final _code = TextEditingController();

  @override
  void dispose() {
    _video.dispose();
    _code.dispose();
    super.dispose();
  }

  void _snack(String message) => ScaffoldMessenger.maybeOf(context)
      ?.showSnackBar(SnackBar(content: Text(message)));

  @override
  Widget build(BuildContext context) {
    final together = widget.together;
    final party = widget.party;
    final sender = together.sender;
    final others = party.members.entries.where((e) => e.key != party.me);
    final status = party.notice ??
        (party.connected ? null : 'Connecting to ${party.relay}…');
    return _Page(
      title: party.host ? 'Your watch party' : 'Watch party',
      subtitle: status ??
          '${party.members.length} ${party.members.length == 1 ? 'device' : 'devices'} on ${party.relay}',
      children: [
        _VideoCard(together: together, party: party),
        _WatchHereSwitch(
          value: party.watching,
          tv: sender.selection.isLocal ? null : sender.selectionName,
          onChanged: together.setWatchHere,
        ),
        const SizedBox(height: 12),
        if (party.canChangeVideo ||
            (party.policy == TogetherPolicy.ask && !party.locked))
          _Card(
            icon: Icons.add_link,
            title: party.canChangeVideo ? 'Play a video' : 'Suggest a video',
            child: Row(children: [
              Expanded(
                child: TextField(
                  controller: _video,
                  decoration: const InputDecoration(
                    hintText: 'Paste a video link',
                    isDense: true,
                  ),
                  onSubmitted: (_) => _share(),
                ),
              ),
              const SizedBox(width: 8),
              FilledButton(onPressed: _share, child: const Text('Play')),
            ]),
          ),
        if (party.requests.isNotEmpty)
          _Card(
            icon: Icons.front_hand_outlined,
            title: 'Requests',
            child: Column(
              children: [
                for (final request in party.requests)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(request.title),
                    subtitle: Text(
                        '${party.members[request.from]?.name ?? 'Someone'} asks to play this'
                        '${request.site == null ? '' : ' (${request.site})'}'),
                    trailing: Wrap(spacing: 4, children: [
                      TextButton(
                        onPressed: () =>
                            together.answer(request, allowed: false),
                        child: const Text('No'),
                      ),
                      TextButton(
                        onPressed: () => together.answer(request,
                            allowed: true, always: true),
                        child: const Text('Always'),
                      ),
                      FilledButton(
                        onPressed: () =>
                            together.answer(request, allowed: true),
                        child: const Text('Play'),
                      ),
                    ]),
                  ),
              ],
            ),
          ),
        _Card(
          icon: Icons.group_add_outlined,
          title: 'Invite',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Wrap(spacing: 12, runSpacing: 8, children: [
                FilledButton.icon(
                  onPressed: () async {
                    await Clipboard.setData(ClipboardData(text: party.invite));
                    _snack('Invite link copied');
                  },
                  icon: const Icon(Icons.copy, size: 18),
                  label: const Text('Copy invite link'),
                ),
                if (sender.selectedTvSupportsWatchTogether && !_hasOwnTv(party))
                  OutlinedButton.icon(
                    onPressed: () => together.addConnectedTv(),
                    icon: const Icon(Icons.connected_tv, size: 18),
                    label: Text('Add ${sender.selectionName}'),
                  ),
              ]),
              const SizedBox(height: 6),
              const Text(
                'Anyone with the link can join until the party ends. Send it privately.',
                style: TextStyle(fontSize: 12, color: _muted),
              ),
              const SizedBox(height: 12),
              Row(children: [
                Expanded(
                  child: TextField(
                    controller: _code,
                    decoration: const InputDecoration(
                      hintText: 'Add a TV by the code it shows',
                      isDense: true,
                    ),
                    onSubmitted: (_) => _addByCode(),
                  ),
                ),
                const SizedBox(width: 8),
                OutlinedButton(
                    onPressed: _addByCode, child: const Text('Send invite')),
              ]),
            ],
          ),
        ),
        _Card(
          icon: Icons.devices_other,
          title: 'Devices',
          child: Column(children: [
            _MemberTile(
              name: '${party.members[party.me]?.name ?? 'This computer'} (you)',
              kind: TogetherDeviceKind.desktop,
              host: party.host,
            ),
            for (final entry in others)
              _MemberTile(
                name: entry.value.name.isEmpty ? 'A device' : entry.value.name,
                kind: entry.value.kind,
                host: entry.value.host,
                own: party.own.contains(entry.key),
                waiting: party.waitingFor.contains(entry.key),
                trailing: Wrap(spacing: 4, children: [
                  if (party.continueWithout.contains(entry.key))
                    TextButton(
                      onPressed: () => together.continueWithout(entry.key),
                      child: const Text('Continue without'),
                    ),
                  if (party.host && !party.own.contains(entry.key))
                    TextButton(
                      onPressed: () => _confirmSignIn(entry.key, entry.value),
                      child: const Text('Share sign-in'),
                    ),
                ]),
              ),
          ]),
        ),
        if (party.host)
          _Card(
            icon: Icons.admin_panel_settings_outlined,
            title: 'Host controls',
            child: Column(children: [
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                value: party.locked,
                onChanged: together.lock,
                // ignore: deprecated_member_use (activeThumbColor needs a newer Flutter than some dev machines)
                activeColor: Colors.tealAccent,
                title: const Text('Lock controls'),
                subtitle: const Text(
                    'Only you can play, pause, seek or change the video.',
                    style: TextStyle(fontSize: 12, color: _muted)),
              ),
              Row(children: [
                const Expanded(child: Text('Who can change the video')),
                DropdownButton<TogetherPolicy>(
                  value: party.policy,
                  onChanged: (value) {
                    if (value != null) together.setPolicy(value);
                  },
                  items: const [
                    DropdownMenuItem(
                        value: TogetherPolicy.onlyHost, child: Text('Only me')),
                    DropdownMenuItem(
                        value: TogetherPolicy.ask, child: Text('Ask me')),
                    DropdownMenuItem(
                        value: TogetherPolicy.anyone, child: Text('Anyone')),
                  ],
                ),
              ]),
            ]),
          ),
        Align(
          alignment: Alignment.centerLeft,
          child: OutlinedButton.icon(
            style: OutlinedButton.styleFrom(foregroundColor: Colors.redAccent),
            onPressed: together.leave,
            icon: const Icon(Icons.logout, size: 18),
            label: const Text('Leave the party'),
          ),
        ),
      ],
    );
  }

  bool _hasOwnTv(Party party) => party.own
      .any((member) => party.members[member]?.kind == TogetherDeviceKind.tv);

  void _share() {
    final link = _video.text.trim();
    if (link.isEmpty) return;
    _snack(widget.together.shareVideo(url: link));
    _video.clear();
  }

  void _addByCode() {
    final code = _code.text.trim();
    if (code.isEmpty) return;
    widget.together.addTvByCode(code);
    _code.clear();
  }

  Future<void> _confirmSignIn(String member, PartyMember who) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Share your sign-in with ${who.name}?'),
        content: const Text(
            "They get the cookies or login for this video's site, so it plays for them "
            'too. Only do this for people you trust.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Share')),
        ],
      ),
    );
    if (ok == true) widget.together.shareSignIn(member);
  }
}

class _VideoCard extends StatelessWidget {
  const _VideoCard({required this.together, required this.party});

  final DesktopTogether together;
  final Party party;

  @override
  Widget build(BuildContext context) {
    final item = party.item;
    final canControl = party.host || !party.locked;
    final waiting = party.waitingFor
        .where((m) => m != party.me)
        .map((m) => party.members[m]?.name ?? 'a device')
        .toList();
    return _Card(
      icon: Icons.ondemand_video,
      title: item == null ? 'Nothing playing yet' : item.title,
      child: item == null
          ? Text(
              party.canChangeVideo
                  ? 'Paste a link below, or send a video from the browser extension.'
                  : 'Waiting for the host to pick a video.',
              style: const TextStyle(fontSize: 13, color: _muted),
            )
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (item.site != null)
                  Text(item.site!,
                      style: const TextStyle(fontSize: 12, color: _muted)),
                const SizedBox(height: 8),
                // A live party shares play and pause only: no position, no seeking.
                Row(children: [
                  if (!party.live)
                    IconButton(
                      tooltip: 'Back 10 seconds',
                      onPressed:
                          canControl ? () => together.seekBy(-10000) : null,
                      icon: const Icon(Icons.replay_10),
                    ),
                  IconButton.filled(
                    tooltip: party.playing ? 'Pause' : 'Play',
                    onPressed: canControl
                        ? () => party.playing
                            ? together.pauseParty()
                            : together.playParty()
                        : null,
                    icon: Icon(party.playing ? Icons.pause : Icons.play_arrow),
                  ),
                  if (!party.live)
                    IconButton(
                      tooltip: 'Forward 10 seconds',
                      onPressed:
                          canControl ? () => together.seekBy(10000) : null,
                      icon: const Icon(Icons.forward_10),
                    ),
                  const SizedBox(width: 8),
                  party.live
                      ? const Text('Live', style: TextStyle(color: _muted))
                      : _PartyClock(together: together, party: party),
                ]),
                if (waiting.isNotEmpty)
                  Text('Waiting for ${waiting.join(', ')}…',
                      style: const TextStyle(fontSize: 12, color: _muted)),
                if (party.locked && !party.host)
                  const Text('The host locked the controls.',
                      style: TextStyle(fontSize: 12, color: _muted)),
              ],
            ),
    );
  }
}

/// The party position, ticking while it plays.
class _PartyClock extends StatelessWidget {
  const _PartyClock({required this.together, required this.party});

  final DesktopTogether together;
  final Party party;

  @override
  Widget build(BuildContext context) => StreamBuilder<void>(
        stream: party.playing
            ? Stream<void>.periodic(const Duration(milliseconds: 500))
            : const Stream.empty(),
        builder: (context, _) => Text(
          _format(together.positionNow(party)),
          style: const TextStyle(
              fontFeatures: [FontFeature.tabularFigures()], color: _muted),
        ),
      );

  static String _format(int ms) {
    final total = ms ~/ 1000;
    final h = total ~/ 3600;
    final m = (total % 3600) ~/ 60;
    final s = (total % 60).toString().padLeft(2, '0');
    return h > 0 ? '$h:${m.toString().padLeft(2, '0')}:$s' : '$m:$s';
  }
}

class _MemberTile extends StatelessWidget {
  const _MemberTile({
    required this.name,
    required this.kind,
    required this.host,
    this.own = false,
    this.waiting = false,
    this.trailing,
  });

  final String name;
  final TogetherDeviceKind kind;
  final bool host;
  final bool own;
  final bool waiting;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final tags = [
      if (host) 'host',
      if (own) 'yours',
      if (waiting) 'loading',
    ];
    return ListTile(
      contentPadding: EdgeInsets.zero,
      dense: true,
      leading: Icon(switch (kind) {
        TogetherDeviceKind.tv => Icons.tv,
        TogetherDeviceKind.phone => Icons.smartphone,
        TogetherDeviceKind.desktop => Icons.computer,
        TogetherDeviceKind.cli => Icons.terminal,
      }),
      title: Text(name),
      subtitle: tags.isEmpty
          ? null
          : Text(tags.join(' · '),
              style: const TextStyle(fontSize: 12, color: _muted)),
      trailing: trailing,
    );
  }
}
