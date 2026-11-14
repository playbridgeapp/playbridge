import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:playbridge_cast_core/playbridge_cast_core.dart';

import 'desktop_together.dart';
import '../ui/pb_theme.dart';
import '../ui/pb_widgets.dart';

/// Watch Together: start or join a party, show a code for a phone, and run the
/// party (video, devices, controls).
class TogetherScreen extends StatelessWidget {
  const TogetherScreen({super.key, required this.together, this.onOpenVideo});

  final DesktopTogether together;

  /// Brings the player to the front. When null the "Open the video" button is
  /// left out of the party screen.
  final VoidCallback? onOpenVideo;

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
          TogetherInParty(:final party) => _PartyView(
              together: together,
              party: party,
              onOpenVideo: onOpenVideo,
            ),
          TogetherEnded(:final message) =>
            _EndedView(message: message, onDone: together.dismissEnded),
        },
      );
}

// ─── Shared pieces ──────────────────────────────────────────────────────────

const _secondary = TextStyle(fontSize: 14, color: PbColors.textSecondary);
const _small = TextStyle(fontSize: 13, color: PbColors.textSecondary);

class _Page extends StatelessWidget {
  const _Page({required this.title, this.subtitle, required this.children});

  final String title;
  final String? subtitle;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => ListView(
        padding: const EdgeInsets.fromLTRB(32, 28, 32, 28),
        children: [
          Semantics(
            header: true,
            child: Text(title,
                style: const TextStyle(
                    fontSize: 26,
                    height: 40 / 26,
                    fontWeight: FontWeight.w700,
                    color: PbColors.text)),
          ),
          if (subtitle != null) ...[
            const SizedBox(height: 6),
            Text(subtitle!,
                style: const TextStyle(
                    fontSize: 15, color: PbColors.textSecondary)),
          ],
          const SizedBox(height: 24),
          ...children,
        ],
      );
}

/// A rounded content panel with a heading.
class _Panel extends StatelessWidget {
  const _Panel({required this.title, required this.children});

  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(24),
        decoration: BoxDecoration(
          color: PbColors.card,
          borderRadius: BorderRadius.circular(PbMetrics.cardRadius),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Semantics(
              header: true,
              child: Text(title,
                  style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                      color: PbColors.text)),
            ),
            const SizedBox(height: 12),
            ...children,
          ],
        ),
      );
}

/// A pill-shaped text field on the page background.
class _PillField extends StatelessWidget {
  const _PillField({
    required this.controller,
    required this.hint,
    required this.label,
    this.onSubmitted,
    this.radius = PbMetrics.pillRadius,
    this.keyboardType,
  });

  final TextEditingController controller;
  final String hint;
  final String label;
  final ValueChanged<String>? onSubmitted;
  final double radius;
  final TextInputType? keyboardType;

  @override
  Widget build(BuildContext context) {
    OutlineInputBorder border(Color color, [double width = 1]) =>
        OutlineInputBorder(
          borderRadius: BorderRadius.circular(radius),
          borderSide: BorderSide(color: color, width: width),
        );
    return SizedBox(
      height: PbMetrics.controlHeight,
      child: Semantics(
        label: label,
        textField: true,
        child: TextField(
          controller: controller,
          keyboardType: keyboardType,
          onSubmitted: onSubmitted,
          style: const TextStyle(fontSize: 14),
          decoration: InputDecoration(
            hintText: hint,
            hintStyle: const TextStyle(color: PbColors.textSecondary),
            filled: true,
            fillColor: PbColors.background,
            contentPadding: const EdgeInsets.symmetric(horizontal: 16),
            border: border(PbColors.outline),
            enabledBorder: border(PbColors.outline),
            focusedBorder: border(PbColors.accent, 2),
          ),
        ),
      ),
    );
  }
}

/// A small status dot followed by text.
class _StatusLine extends StatelessWidget {
  const _StatusLine(this.text, {this.color = PbColors.warning});

  final String text;
  final Color color;

  @override
  Widget build(BuildContext context) => Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 8,
            height: 8,
            decoration: BoxDecoration(color: color, shape: BoxShape.circle),
          ),
          const SizedBox(width: 8),
          Flexible(
            child: Text(text, style: TextStyle(fontSize: 14, color: color)),
          ),
        ],
      );
}

/// Centred prompt card used for the screens that ask one thing of the person.
class _PromptCard extends StatelessWidget {
  const _PromptCard({
    required this.label,
    required this.children,
    this.align = CrossAxisAlignment.start,
  });

  final String label;
  final List<Widget> children;
  final CrossAxisAlignment align;

  @override
  Widget build(BuildContext context) => Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(32),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Semantics(
              label: label,
              container: true,
              child: Container(
                padding: const EdgeInsets.all(24),
                decoration: BoxDecoration(
                  color: PbColors.card,
                  borderRadius: BorderRadius.circular(20),
                  border: Border.all(color: PbColors.outline),
                ),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: align,
                  children: children,
                ),
              ),
            ),
          ),
        ),
      );
}

class _PromptTitle extends StatelessWidget {
  const _PromptTitle(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Semantics(
        header: true,
        child: Text(text,
            style: const TextStyle(
                fontSize: 20,
                fontWeight: FontWeight.w700,
                color: PbColors.text)),
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

  @override
  void dispose() {
    _link.dispose();
    super.dispose();
  }

  Future<void> _paste() async {
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    final text = data?.text?.trim();
    if (text != null && text.isNotEmpty) setState(() => _link.text = text);
  }

  Future<void> _editRelay() => showDialog<void>(
        context: context,
        builder: (context) => _RelayDialog(together: widget.together),
      );

  @override
  Widget build(BuildContext context) {
    final together = widget.together;
    final tv = together.sender.selection.isLocal
        ? null
        : together.sender.selectionName;
    return _Page(
      title: 'Watch Together',
      subtitle:
          'Watch the same video in step with friends, each on your own screen.',
      children: [
        Container(
          padding: const EdgeInsets.all(28),
          decoration: BoxDecoration(
            color: PbColors.selected,
            borderRadius: BorderRadius.circular(PbMetrics.cardRadius),
          ),
          child: Wrap(
            alignment: WrapAlignment.spaceBetween,
            crossAxisAlignment: WrapCrossAlignment.center,
            spacing: 24,
            runSpacing: 16,
            children: [
              ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 520),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Semantics(
                      header: true,
                      child: const Text('Start a party',
                          style: TextStyle(
                              fontSize: 20,
                              fontWeight: FontWeight.w600,
                              color: PbColors.onSelected)),
                    ),
                    const SizedBox(height: 6),
                    const Text(
                      'You get a link to send. Then pick a video, and play, pause and seek stay in step for everyone.',
                      style: TextStyle(
                          height: 1.5, color: PbColors.onSelectedSecondary),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      together.usesSharedRelay
                          ? "Uses PlayBridge's shared relay: up to 4 devices for 3 hours."
                          : 'Uses your relay: ${together.relayUrl}',
                      style: const TextStyle(
                          fontSize: 13, color: PbColors.onSelectedSecondary),
                    ),
                  ],
                ),
              ),
              PbButton(
                label: 'Start a party',
                onPressed: () => together.start(watchHere: _watchHere),
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        PbSwitchRow(
          title: 'Watch on this computer',
          subtitle: _watchHereHint(_watchHere, tv),
          value: _watchHere,
          onChanged: (value) => setState(() => _watchHere = value),
        ),
        const SizedBox(height: 24),
        LayoutBuilder(builder: (context, constraints) {
          final join = _Panel(
            title: 'Join with a link',
            children: [
              const Text('Paste the invite link a friend sent you',
                  style: _secondary),
              const SizedBox(height: 12),
              Wrap(spacing: 8, runSpacing: 8, children: [
                SizedBox(
                  width: 260,
                  child: _PillField(
                    controller: _link,
                    hint: 'Invite link',
                    label: 'Invite link',
                    onSubmitted: (value) =>
                        together.join(value, watchHere: _watchHere),
                  ),
                ),
                PbIconButton(
                  icon: Icons.content_paste,
                  tooltip: 'Paste',
                  variant: PbButtonVariant.secondary,
                  onPressed: _paste,
                ),
                PbButton(
                  label: 'Join',
                  variant: PbButtonVariant.secondary,
                  onPressed: () =>
                      together.join(_link.text, watchHere: _watchHere),
                ),
              ]),
            ],
          );
          final phone = _Panel(
            title: 'Add this computer from a phone',
            children: [
              const Text(
                'Already in a party on your phone? Show a code here and enter or scan it there. Paired phones can add this computer without a code.',
                style: TextStyle(height: 1.5, color: PbColors.textSecondary),
              ),
              const SizedBox(height: 12),
              PbButton(
                label: 'Show a code',
                variant: PbButtonVariant.secondary,
                onPressed: together.showCode,
              ),
            ],
          );
          if (constraints.maxWidth < 640) {
            return Column(children: [join, const SizedBox(height: 16), phone]);
          }
          return IntrinsicHeight(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Expanded(child: join),
                const SizedBox(width: 16),
                Expanded(child: phone),
              ],
            ),
          );
        }),
        const SizedBox(height: 24),
        Align(
          alignment: Alignment.centerLeft,
          child: PbButton(
            label: 'Use your own relay…',
            variant: PbButtonVariant.quiet,
            onPressed: _editRelay,
          ),
        ),
      ],
    );
  }
}

String _watchHereHint(bool value, String? tv) => value
    ? 'The video plays here, in step with everyone.'
    : tv == null
        ? 'This computer is a remote for the party.'
        : 'This computer is a remote; add $tv to watch on it.';

class _RelayDialog extends StatefulWidget {
  const _RelayDialog({required this.together});

  final DesktopTogether together;

  @override
  State<_RelayDialog> createState() => _RelayDialogState();
}

class _RelayDialogState extends State<_RelayDialog> {
  late final _relay = TextEditingController(
      text: widget.together.usesSharedRelay ? '' : widget.together.relayUrl);

  @override
  void dispose() {
    _relay.dispose();
    super.dispose();
  }

  void _save() {
    widget.together.setRelayUrl(_relay.text);
    Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) => Dialog(
        backgroundColor: PbColors.card,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
          side: const BorderSide(color: PbColors.outline),
        ),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 480),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const _PromptTitle('Use your own relay'),
                const SizedBox(height: 12),
                _PillField(
                  controller: _relay,
                  hint: 'https://your-relay.example.workers.dev',
                  label: 'Relay address',
                  onSubmitted: (_) => _save(),
                ),
                const SizedBox(height: 8),
                const Text(
                  'Leave empty for the shared relay. Deploy your own from together/room-worker.',
                  style: _small,
                ),
                const SizedBox(height: 16),
                Row(mainAxisAlignment: MainAxisAlignment.end, children: [
                  PbButton(
                    label: 'Cancel',
                    variant: PbButtonVariant.quiet,
                    onPressed: () => Navigator.pop(context),
                  ),
                  const SizedBox(width: 8),
                  PbButton(label: 'Save relay', onPressed: _save),
                ]),
              ],
            ),
          ),
        ),
      );
}

// ─── Pairing code, invite, ended ────────────────────────────────────────────

/// Groups a nine-digit code as "482 913 057"; anything else is left as is.
String groupPartyCode(String code) => RegExp(r'^\d{9}$').hasMatch(code)
    ? '${code.substring(0, 3)} ${code.substring(3, 6)} ${code.substring(6)}'
    : code;

class _CodeView extends StatelessWidget {
  const _CodeView({required this.code, required this.onCancel});

  final PartyCode code;
  final VoidCallback onCancel;

  @override
  Widget build(BuildContext context) => _PromptCard(
        label: 'Add this computer from a phone',
        align: CrossAxisAlignment.center,
        children: [
          const _PromptTitle('Add this computer from a phone'),
          const SizedBox(height: 16),
          if (code.qrSize > 0 &&
              code.qrModules.length == code.qrSize * code.qrSize) ...[
            Container(
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(12),
              ),
              padding: const EdgeInsets.all(12),
              child: SizedBox.square(
                dimension: 160,
                child: CustomPaint(
                    painter: _QrPainter(code.qrSize, code.qrModules)),
              ),
            ),
            const SizedBox(height: 16),
          ],
          Semantics(
            label: 'Code ${code.code}',
            child: ExcludeSemantics(
              child: SelectableText(
                groupPartyCode(code.code),
                style: const TextStyle(
                  fontSize: 30,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 2.4,
                  color: PbColors.text,
                ),
              ),
            ),
          ),
          const SizedBox(height: 16),
          const Text(
            'In PlayBridge on the phone, open Watch Together, choose Add a TV, then scan or type this.',
            textAlign: TextAlign.center,
            style: TextStyle(height: 1.5, color: PbColors.textSecondary),
          ),
          const SizedBox(height: 16),
          PbButton(
              label: 'Cancel',
              variant: PbButtonVariant.secondary,
              onPressed: onCancel),
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
  Widget build(BuildContext context) => _PromptCard(
        label: 'Invited to a party',
        children: [
          const _PromptTitle('Join the watch party?'),
          const SizedBox(height: 6),
          Text(
            '${from.isEmpty ? 'Someone' : from} invited this computer'
            '${relay.isEmpty ? '' : ' (relay: $relay)'}. '
            'Play, pause and seek will follow the party.',
            style: const TextStyle(height: 1.5, color: PbColors.textSecondary),
          ),
          const SizedBox(height: 16),
          Row(mainAxisAlignment: MainAxisAlignment.end, children: [
            PbButton(
                label: 'Decline',
                variant: PbButtonVariant.secondary,
                onPressed: onDecline),
            const SizedBox(width: 8),
            PbButton(label: 'Join', onPressed: onJoin, autofocus: true),
          ]),
        ],
      );
}

class _EndedView extends StatelessWidget {
  const _EndedView({required this.message, required this.onDone});

  final String message;
  final VoidCallback onDone;

  @override
  Widget build(BuildContext context) => _PromptCard(
        label: 'Watch Together',
        children: [
          const _PromptTitle('Watch Together'),
          const SizedBox(height: 6),
          Text(message,
              style:
                  const TextStyle(height: 1.5, color: PbColors.textSecondary)),
          const SizedBox(height: 16),
          Align(
            alignment: Alignment.centerRight,
            child: PbButton(label: 'OK', onPressed: onDone, autofocus: true),
          ),
        ],
      );
}

// ─── In a party ─────────────────────────────────────────────────────────────

class _PartyView extends StatefulWidget {
  const _PartyView({
    required this.together,
    required this.party,
    this.onOpenVideo,
  });

  final DesktopTogether together;
  final Party party;
  final VoidCallback? onOpenVideo;

  @override
  State<_PartyView> createState() => _PartyViewState();
}

class _PartyViewState extends State<_PartyView> {
  final _video = TextEditingController();

  @override
  void dispose() {
    _video.dispose();
    super.dispose();
  }

  void _snack(String message) => ScaffoldMessenger.maybeOf(context)
      ?.showSnackBar(SnackBar(content: Text(message)));

  Future<void> _invite() => showDialog<void>(
        context: context,
        builder: (context) => _InviteDialog(
          together: widget.together,
          onCopied: () => _snack('Invite link copied'),
        ),
      );

  @override
  Widget build(BuildContext context) {
    final together = widget.together;
    final party = widget.party;
    final sender = together.sender;
    final count = party.members.length;
    final status = party.notice ??
        (party.connected ? null : 'Connecting to ${party.relay}…');
    final subtitle = status ??
        '$count ${count == 1 ? 'device' : 'devices'}'
            '${party.host ? ' · you are the host' : ''} · ${party.relay}';

    final main = <Widget>[
      _VideoCard(
        together: together,
        party: party,
        onOpenVideo: widget.onOpenVideo,
        watchHere: PbSwitchRow(
          title: 'Watch on this computer',
          subtitle: party.watching
              ? 'Off makes it a remote for the party'
              : _watchHereHint(false,
                  sender.selection.isLocal ? null : sender.selectionName),
          value: party.watching,
          onChanged: together.setWatchHere,
        ),
      ),
      if (party.canChangeVideo ||
          (party.policy == TogetherPolicy.ask && !party.locked)) ...[
        const SizedBox(height: 20),
        _sectionLabel(party.canChangeVideo
            ? (party.item == null ? 'Play a video' : 'Play something else')
            : 'Suggest a video'),
        const SizedBox(height: 10),
        Wrap(spacing: 8, runSpacing: 8, children: [
          SizedBox(
            width: 380,
            child: _PillField(
              controller: _video,
              hint:
                  'Paste a video link, or send one from the browser extension',
              label: 'Video link',
              onSubmitted: (_) => _share(),
            ),
          ),
          PbButton(
            label: party.canChangeVideo ? 'Play for everyone' : 'Suggest it',
            variant: PbButtonVariant.secondary,
            onPressed: _share,
          ),
        ]),
      ],
      if (party.requests.isNotEmpty) ...[
        const SizedBox(height: 20),
        const PbSectionHeading('Suggestions'),
        const SizedBox(height: 10),
        for (final request in party.requests)
          Padding(
            padding: const EdgeInsets.only(bottom: 6),
            child: PbListRow(
              minHeight: 60,
              title: request.title,
              subtitle:
                  '${party.members[request.from]?.name ?? 'Someone'} suggests this'
                  '${request.site == null ? '' : ' · ${request.site}'}',
              trailing: Wrap(spacing: 6, runSpacing: 4, children: [
                PbButton(
                  label: 'Play',
                  onPressed: () => together.answer(request, allowed: true),
                ),
                PbButton(
                  label: 'Always allow',
                  variant: PbButtonVariant.secondary,
                  onPressed: () =>
                      together.answer(request, allowed: true, always: true),
                ),
                PbButton(
                  label: 'No',
                  variant: PbButtonVariant.quiet,
                  onPressed: () => together.answer(request, allowed: false),
                ),
              ]),
            ),
          ),
      ],
    ];

    final aside = <Widget>[
      PbSectionHeading('Devices'),
      const SizedBox(height: 8),
      _DeviceRow(
        name: '${party.members[party.me]?.name ?? 'This computer'} (you)',
        kind: TogetherDeviceKind.desktop,
        host: party.host,
      ),
      for (final entry in party.members.entries.where((e) => e.key != party.me))
        Padding(
          padding: const EdgeInsets.only(top: 6),
          child: _DeviceRow(
            name: entry.value.name.isEmpty ? 'A device' : entry.value.name,
            kind: entry.value.kind,
            host: entry.value.host,
            own: party.own.contains(entry.key),
            waiting: party.waitingFor.contains(entry.key),
            actions: [
              if (party.continueWithout.contains(entry.key))
                PbButton(
                  label: 'Continue without',
                  variant: PbButtonVariant.quiet,
                  onPressed: () => together.continueWithout(entry.key),
                ),
              if (party.host && !party.own.contains(entry.key))
                PbButton(
                  label: 'Share sign-in',
                  variant: PbButtonVariant.quiet,
                  onPressed: () => _confirmSignIn(entry.key, entry.value),
                ),
            ],
          ),
        ),
      if (party.host) ...[
        const SizedBox(height: 24),
        const PbSectionHeading('Who can change the video'),
        const SizedBox(height: 8),
        _PolicyTabs(value: party.policy, onChanged: together.setPolicy),
        const SizedBox(height: 8),
        PbSwitchRow(
          title: 'Lock play, pause and seek',
          subtitle: 'Only you can use them',
          value: party.locked,
          onChanged: together.lock,
        ),
      ],
    ];

    return LayoutBuilder(builder: (context, constraints) {
      final wide = constraints.maxWidth >= 900;
      final header = Wrap(
        alignment: WrapAlignment.spaceBetween,
        crossAxisAlignment: WrapCrossAlignment.center,
        spacing: 16,
        runSpacing: 12,
        children: [
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Semantics(
                header: true,
                child: Text(party.host ? 'Your watch party' : 'Watch party',
                    style: const TextStyle(
                        fontSize: 26,
                        fontWeight: FontWeight.w700,
                        color: PbColors.text)),
              ),
              const SizedBox(height: 2),
              Text(subtitle, style: _secondary),
            ],
          ),
          Wrap(spacing: 8, children: [
            PbButton(label: 'Invite', onPressed: _invite),
            PbButton(
              label: 'Leave the party',
              variant: PbButtonVariant.secondary,
              onPressed: together.leave,
            ),
          ]),
        ],
      );
      if (!wide) {
        return ListView(
          padding: const EdgeInsets.fromLTRB(32, 28, 32, 28),
          children: [
            header,
            const SizedBox(height: 20),
            ...main,
            const SizedBox(height: 24),
            ...aside,
          ],
        );
      }
      return Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Expanded(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(32, 28, 32, 28),
              children: [header, const SizedBox(height: 20), ...main],
            ),
          ),
          Container(
            width: 340,
            color: PbColors.sidebar,
            child: ListView(
              padding: const EdgeInsets.fromLTRB(20, 28, 20, 28),
              children: aside,
            ),
          ),
        ],
      );
    });
  }

  Widget _sectionLabel(String text) => Semantics(
        header: true,
        child: Text(text,
            style: const TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w600,
                color: PbColors.text)),
      );

  void _share() {
    final link = _video.text.trim();
    if (link.isEmpty) return;
    _snack(widget.together.shareVideo(url: link));
    _video.clear();
  }

  Future<void> _confirmSignIn(String member, PartyMember who) async {
    final name = who.name.isEmpty ? 'this device' : who.name;
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => Dialog(
        backgroundColor: PbColors.card,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
          side: const BorderSide(color: PbColors.outline),
        ),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _PromptTitle('Share your sign-in with $name?'),
                const SizedBox(height: 6),
                const Text(
                  "They get the cookies or login for this video's site, so it plays for them too.",
                  style: TextStyle(height: 1.5, color: PbColors.textSecondary),
                ),
                const SizedBox(height: 12),
                Container(
                  width: double.infinity,
                  padding:
                      const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
                  decoration: BoxDecoration(
                    color: const Color(0xFF3A2C10),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: const Text('Only do this for people you trust.',
                      style: TextStyle(color: Color(0xFFF7E3B5))),
                ),
                const SizedBox(height: 16),
                Row(mainAxisAlignment: MainAxisAlignment.end, children: [
                  PbButton(
                    label: 'Cancel',
                    variant: PbButtonVariant.secondary,
                    onPressed: () => Navigator.pop(context, false),
                  ),
                  const SizedBox(width: 8),
                  PbButton(
                    label: 'Share sign-in',
                    onPressed: () => Navigator.pop(context, true),
                  ),
                ]),
              ],
            ),
          ),
        ),
      ),
    );
    if (ok == true) widget.together.shareSignIn(member);
  }
}

/// "Only me / Ask me / Anyone", three equal segments.
class _PolicyTabs extends StatelessWidget {
  const _PolicyTabs({required this.value, required this.onChanged});

  final TogetherPolicy value;
  final ValueChanged<TogetherPolicy> onChanged;

  static const _options = [
    (TogetherPolicy.onlyHost, 'Only me'),
    (TogetherPolicy.ask, 'Ask me'),
    (TogetherPolicy.anyone, 'Anyone'),
  ];

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(4),
        decoration: BoxDecoration(
          color: PbColors.background,
          borderRadius: BorderRadius.circular(26),
        ),
        child: Row(children: [
          for (var i = 0; i < _options.length; i++) ...[
            if (i > 0) const SizedBox(width: 4),
            Expanded(
              child: PbPressable(
                onTap: () => onChanged(_options[i].$1),
                selected: _options[i].$1 == value,
                semanticLabel: _options[i].$2,
                builder: (context, hovered, focused) {
                  final selected = _options[i].$1 == value;
                  return Container(
                    height: PbMetrics.controlHeight,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: selected
                          ? PbColors.selected
                          : (hovered ? PbColors.raised : Colors.transparent),
                      borderRadius: BorderRadius.circular(PbMetrics.pillRadius),
                      border: focused
                          ? Border.all(color: PbColors.accent, width: 2)
                          : null,
                    ),
                    child: Text(
                      _options[i].$2,
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight:
                            selected ? FontWeight.w600 : FontWeight.w500,
                        color: selected
                            ? PbColors.onSelected
                            : PbColors.textSecondary,
                      ),
                    ),
                  );
                },
              ),
            ),
          ],
        ]),
      );
}

class _VideoCard extends StatelessWidget {
  const _VideoCard({
    required this.together,
    required this.party,
    required this.watchHere,
    this.onOpenVideo,
  });

  final DesktopTogether together;
  final Party party;
  final Widget watchHere;
  final VoidCallback? onOpenVideo;

  @override
  Widget build(BuildContext context) {
    final item = party.item;
    final canControl = party.host || !party.locked;
    final waiting = party.waitingFor
        .where((m) => m != party.me)
        .map((m) => party.members[m]?.name ?? 'a device')
        .toList();
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: PbColors.card,
        borderRadius: BorderRadius.circular(PbMetrics.cardRadius),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    item == null ? 'Nothing playing yet' : item.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w600,
                        color: PbColors.text),
                  ),
                  const SizedBox(height: 4),
                  if (item == null)
                    Text(
                      party.canChangeVideo
                          ? 'Paste a link below, or send a video from the browser extension.'
                          : 'Waiting for the host to pick a video.',
                      style: _secondary,
                    )
                  else ...[
                    if (item.site != null) Text(item.site!, style: _small),
                    if (waiting.isNotEmpty)
                      _StatusLine('Waiting for ${waiting.join(', ')}…')
                    else
                      _StatusLine(
                        party.playing
                            ? 'Playing for everyone'
                            : 'Paused for everyone',
                        color: party.playing
                            ? PbColors.success
                            : PbColors.textSecondary,
                      ),
                  ],
                ],
              ),
            ),
            if (item != null && onOpenVideo != null) ...[
              const SizedBox(width: 16),
              PbButton(
                label: 'Open the video',
                variant: PbButtonVariant.secondary,
                onPressed: onOpenVideo,
              ),
            ],
          ]),
          if (item != null) ...[
            const SizedBox(height: 16),
            Wrap(
              crossAxisAlignment: WrapCrossAlignment.center,
              spacing: 10,
              runSpacing: 8,
              children: [
                // A live party shares play and pause only: no position, no seeking.
                if (!party.live)
                  PbIconButton(
                    icon: Icons.replay_10,
                    tooltip: 'Back 10 seconds for everyone',
                    variant: PbButtonVariant.secondary,
                    onPressed:
                        canControl ? () => together.seekBy(-10000) : null,
                  ),
                PbIconButton(
                  icon: party.playing ? Icons.pause : Icons.play_arrow,
                  tooltip: party.playing
                      ? 'Pause for everyone'
                      : 'Play for everyone',
                  variant: PbButtonVariant.primary,
                  size: 52,
                  iconSize: 24,
                  onPressed: canControl
                      ? () => party.playing
                          ? together.pauseParty()
                          : together.playParty()
                      : null,
                ),
                if (!party.live)
                  PbIconButton(
                    icon: Icons.forward_10,
                    tooltip: 'Forward 10 seconds for everyone',
                    variant: PbButtonVariant.secondary,
                    onPressed: canControl ? () => together.seekBy(10000) : null,
                  ),
                party.live
                    ? const Text('Live', style: _secondary)
                    : _PartyClock(together: together, party: party),
              ],
            ),
            if (party.locked && !party.host)
              const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text('The host locked the controls.', style: _small),
              ),
          ],
          const SizedBox(height: 12),
          watchHere,
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
              fontFeatures: [FontFeature.tabularFigures()],
              color: PbColors.textSecondary),
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

class _DeviceRow extends StatelessWidget {
  const _DeviceRow({
    required this.name,
    required this.kind,
    required this.host,
    this.own = false,
    this.waiting = false,
    this.actions = const [],
  });

  final String name;
  final TogetherDeviceKind kind;
  final bool host;
  final bool own;
  final bool waiting;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    final tags = [
      if (host) 'Host',
      if (own) 'Yours',
      if (waiting) 'Loading',
    ];
    return Container(
      constraints: const BoxConstraints(minHeight: 52),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
      decoration: BoxDecoration(
        color: PbColors.card,
        borderRadius: BorderRadius.circular(PbMetrics.navRadius),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Icon(
              switch (kind) {
                TogetherDeviceKind.tv => Icons.tv,
                TogetherDeviceKind.phone => Icons.smartphone,
                TogetherDeviceKind.desktop => Icons.computer,
                TogetherDeviceKind.cli => Icons.terminal,
              },
              size: 18,
              color: PbColors.textSecondary,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Text(name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                      fontWeight: FontWeight.w500, color: PbColors.text)),
            ),
            if (tags.isNotEmpty) ...[
              const SizedBox(width: 8),
              Text(
                tags.join(' · '),
                style: TextStyle(
                    fontSize: 13,
                    color: waiting ? PbColors.warning : PbColors.textSecondary),
              ),
            ],
          ]),
          if (actions.isNotEmpty)
            Wrap(spacing: 4, runSpacing: 4, children: actions),
        ],
      ),
    );
  }
}

// ─── Invite dialog ──────────────────────────────────────────────────────────

class _InviteDialog extends StatefulWidget {
  const _InviteDialog({required this.together, required this.onCopied});

  final DesktopTogether together;
  final VoidCallback onCopied;

  @override
  State<_InviteDialog> createState() => _InviteDialogState();
}

class _InviteDialogState extends State<_InviteDialog> {
  final _code = TextEditingController();

  @override
  void dispose() {
    _code.dispose();
    super.dispose();
  }

  void _addByCode() {
    final code = _code.text.trim();
    if (code.isEmpty) return;
    widget.together.addTvByCode(code);
    _code.clear();
  }

  bool _hasOwnTv(Party party) => party.own
      .any((member) => party.members[member]?.kind == TogetherDeviceKind.tv);

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: Listenable.merge([widget.together, widget.together.sender]),
        builder: (context, _) {
          final party = widget.together.party;
          final sender = widget.together.sender;
          if (party == null) {
            // The party ended while the dialog was open.
            WidgetsBinding.instance.addPostFrameCallback((_) {
              if (mounted) Navigator.maybeOf(context)?.maybePop();
            });
            return const SizedBox.shrink();
          }
          final canAddTv =
              sender.selectedTvSupportsWatchTogether && !_hasOwnTv(party);
          return Dialog(
            backgroundColor: PbColors.card,
            insetPadding: const EdgeInsets.all(24),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(20),
              side: const BorderSide(color: PbColors.outline),
            ),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 520),
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(28),
                child: Semantics(
                  label: 'Invite to your party',
                  container: true,
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(children: [
                        const Expanded(
                          child: Text('Invite to your party',
                              style: TextStyle(
                                  fontSize: 22,
                                  fontWeight: FontWeight.w700,
                                  color: PbColors.text)),
                        ),
                        PbIconButton(
                          icon: Icons.close,
                          tooltip: 'Close',
                          variant: PbButtonVariant.secondary,
                          onPressed: () => Navigator.pop(context),
                        ),
                      ]),
                      const SizedBox(height: 20),
                      const Text('Friends somewhere else',
                          style: TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.w600,
                              color: PbColors.text)),
                      const SizedBox(height: 8),
                      PbButton(
                        label: 'Copy invite link',
                        icon: Icons.copy,
                        onPressed: () async {
                          await Clipboard.setData(
                              ClipboardData(text: party.invite));
                          widget.onCopied();
                        },
                      ),
                      const SizedBox(height: 8),
                      const Text(
                        'Anyone with the link can join until the party ends. Send it privately.',
                        style: _small,
                      ),
                      const SizedBox(height: 20),
                      const Divider(),
                      const SizedBox(height: 20),
                      if (canAddTv) ...[
                        const Text('Your own screens',
                            style: TextStyle(
                                fontSize: 15,
                                fontWeight: FontWeight.w600,
                                color: PbColors.text)),
                        const SizedBox(height: 8),
                        PbListRow(
                          title: 'Add ${sender.selectionName}',
                          subtitle: 'Connected to this computer',
                          minHeight: 56,
                          onTap: () => widget.together.addConnectedTv(),
                        ),
                        const SizedBox(height: 20),
                      ],
                      const Text('A TV that is showing a code',
                          style: TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.w600,
                              color: PbColors.text)),
                      const SizedBox(height: 8),
                      Wrap(spacing: 8, runSpacing: 8, children: [
                        SizedBox(
                          width: 240,
                          child: _PillField(
                            controller: _code,
                            hint: 'Code on the TV',
                            label: 'Code on the TV',
                            radius: 12,
                            keyboardType: TextInputType.number,
                            onSubmitted: (_) => _addByCode(),
                          ),
                        ),
                        PbButton(
                          label: 'Send invite',
                          variant: PbButtonVariant.secondary,
                          onPressed: _addByCode,
                        ),
                      ]),
                      const SizedBox(height: 8),
                      const Text(
                        'On that TV, open PlayBridge and choose Watch Together to see its code.',
                        style: _small,
                      ),
                    ],
                  ),
                ),
              ),
            ),
          );
        },
      );
}
