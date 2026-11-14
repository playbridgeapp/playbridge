import '../ui/protocol_chip.dart';
import 'package:flutter/material.dart';

import '../tv_discovery.dart';
import '../tv_sender_controller.dart';
import '../ui/pb_theme.dart';
import '../ui/pb_widgets.dart';

/// One row of the "Play on" list: this computer (null [id]) or a TV the sender
/// controller knows about.
class PlayTargetOption {
  const PlayTargetOption({
    required this.id,
    required this.name,
    required this.subtitle,
    required this.selected,
    required this.enabled,
    this.needsPairing = false,
    this.protocol,
    this.saved = false,
  });

  /// Receiver identity key, or null for this computer.
  final String? id;
  final String name;
  final String subtitle;
  final bool selected;

  /// False for saved TVs that are not on the network right now.
  final bool enabled;

  /// A PlayBridge TV that was found but never paired; pairing happens on Devices.
  final bool needsPairing;

  /// How the TV is reached; null for this computer.
  final TvProtocol? protocol;

  /// A TV that has been paired or used before.
  final bool saved;

  bool get isComputer => id == null;
}

/// Name shown on the "Plays on" pill for the controller's current destination.
String playTargetLabel(TvSenderController sender) =>
    sender.selection.isLocal ? 'This computer' : sender.selectionName;

/// True when the current destination is a TV rather than this computer.
bool playsOnTv(TvSenderController sender) => !sender.selection.isLocal;

/// This computer first, then every TV the controller knows (discovered plus
/// saved) that is available now: the current destination first, then saved
/// TVs, then ones only found nearby.
List<PlayTargetOption> buildPlayTargetOptions(
  TvSenderController sender,
  String computerName,
) {
  final selectedId = sender.selection.identityKey;
  final online = {for (final tv in sender.discovered) tv.identityKey: tv};
  final known = <String, DiscoveredTv>{
    for (final tv in sender.pairedTvs)
      tv.identityKey: sender.deviceById(tv.identityKey)!,
    ...online,
  };

  final savedIds = {for (final tv in sender.pairedTvs) tv.identityKey};
  final rows = <PlayTargetOption>[];
  for (final entry in known.entries) {
    final tv = entry.value;
    final id = entry.key;
    final isOnline = online.containsKey(id);
    final selected = id == selectedId;
    // TVs that are not available now are left out; Devices lists them.
    if (!isOnline && !selected) continue;
    final connected = selected && sender.selectedTvConnected;
    final needsPairing = isOnline && sender.needsPairing(tv);
    final status = connected
        ? 'connected'
        : needsPairing
            ? 'needs pairing'
            : isOnline
                ? 'on your network'
                : 'not on your network';
    rows.add(PlayTargetOption(
      id: id,
      name: tv.name,
      subtitle: status,
      protocol: tv.protocol,
      saved: savedIds.contains(id),
      selected: selected,
      enabled: connected || (isOnline && sender.canConnectTo(tv)),
      needsPairing: needsPairing,
    ));
  }
  // Current destination, then saved TVs, then ones only found nearby.
  int rank(PlayTargetOption o) => o.selected
      ? 0
      : o.saved
          ? 1
          : 2;
  rows.sort((a, b) {
    final r = rank(a).compareTo(rank(b));
    return r != 0 ? r : a.name.toLowerCase().compareTo(b.name.toLowerCase());
  });

  return [
    PlayTargetOption(
      id: null,
      name: 'This computer',
      subtitle: computerName,
      selected: sender.selection.isLocal,
      enabled: true,
    ),
    ...rows,
  ];
}

/// Applies a choice from the picker with the controller's existing paths:
/// `disconnect()` for this computer (as the browser extension's "select local"
/// does) and `selectDevice()` for a TV. [onFindDevices] handles TVs that still
/// need pairing; [onMessage] gets a plain-language failure.
Future<void> choosePlayTarget(
  TvSenderController sender,
  PlayTargetOption option, {
  required VoidCallback onFindDevices,
  required ValueChanged<String> onMessage,
}) async {
  if (option.selected && (option.isComputer || sender.selectedTvConnected)) {
    return;
  }
  if (option.isComputer) {
    await sender.disconnect();
    return;
  }
  if (option.needsPairing) {
    onFindDevices();
    return;
  }
  final result = await sender.selectDevice(option.id!);
  if (result['ok'] != true) {
    onMessage("Couldn't connect to ${option.name}");
  }
}

/// Opens the destination list under (or above) the widget owning [anchor].
Future<void> showPlayTargetPicker({
  required BuildContext anchor,
  required TvSenderController sender,
  required String computerName,
  required VoidCallback onFindDevices,
  required ValueChanged<String> onMessage,
}) {
  final box = anchor.findRenderObject() as RenderBox?;
  final overlay = Overlay.of(anchor).context.findRenderObject() as RenderBox?;
  if (box == null || overlay == null) return Future.value();
  final rect = box.localToGlobal(Offset.zero, ancestor: overlay) & box.size;
  final screen = overlay.size;
  final below = screen.height - rect.bottom;
  final placeAbove = below < 340 && rect.top > below;
  final panelWidth = (screen.width - 16).clamp(0.0, 420.0);

  return showGeneralDialog<void>(
    context: anchor,
    barrierDismissible: true,
    barrierLabel: 'Close destination list',
    barrierColor: Colors.transparent,
    transitionDuration: Duration.zero,
    pageBuilder: (dialogContext, _, __) {
      void close() => Navigator.of(dialogContext).pop();
      return Stack(children: [
        Positioned(
          left: (rect.right - panelWidth)
              .clamp(8.0, (screen.width - panelWidth - 8).clamp(8.0, 1e9)),
          top: placeAbove ? null : rect.bottom + 8,
          bottom: placeAbove ? screen.height - rect.top + 8 : null,
          child: SizedBox(
            width: panelWidth,
            child: PlayTargetPanel(
              sender: sender,
              computerName: computerName,
              onClose: close,
              onFindDevices: onFindDevices,
              onMessage: onMessage,
            ),
          ),
        ),
      ]);
    },
  );
}

/// The "Play on" list itself (HomePlayOn mockup).
class PlayTargetPanel extends StatelessWidget {
  const PlayTargetPanel({
    super.key,
    required this.sender,
    required this.computerName,
    required this.onClose,
    required this.onFindDevices,
    required this.onMessage,
  });

  final TvSenderController sender;
  final String computerName;
  final VoidCallback onClose;
  final VoidCallback onFindDevices;
  final ValueChanged<String> onMessage;

  @override
  Widget build(BuildContext context) {
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 420, maxHeight: 520),
      child: Material(
        color: PbColors.raised,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(14),
          side: const BorderSide(color: PbColors.outline),
        ),
        child: Padding(
          padding: const EdgeInsets.all(10),
          child: ListenableBuilder(
            listenable: sender,
            builder: (context, _) {
              final options = buildPlayTargetOptions(sender, computerName);
              return FocusTraversalGroup(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Padding(
                      padding:
                          EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                      child: Text(
                        'Play on',
                        style: TextStyle(
                          fontSize: 13,
                          color: PbColors.textSecondary,
                        ),
                      ),
                    ),
                    Flexible(
                      child: SingleChildScrollView(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            for (final option in options)
                              _OptionRow(
                                option: option,
                                onTap: option.enabled
                                    ? () {
                                        onClose();
                                        choosePlayTarget(
                                          sender,
                                          option,
                                          onFindDevices: onFindDevices,
                                          onMessage: onMessage,
                                        );
                                      }
                                    : null,
                              ),
                          ],
                        ),
                      ),
                    ),
                    const Padding(
                      padding:
                          EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                      child: Divider(height: 1, color: PbColors.outline),
                    ),
                    PbPressable(
                      onTap: () {
                        onClose();
                        onFindDevices();
                      },
                      semanticLabel: 'Find more devices',
                      builder: (context, hovered, focused) => Container(
                        height: PbMetrics.controlHeight,
                        padding: const EdgeInsets.symmetric(horizontal: 12),
                        alignment: Alignment.centerLeft,
                        decoration: BoxDecoration(
                          color: hovered
                              ? PbColors.selected.withValues(alpha: 0.5)
                              : Colors.transparent,
                          borderRadius:
                              BorderRadius.circular(PbMetrics.navRadius),
                          border: focused
                              ? Border.all(color: PbColors.accent, width: 2)
                              : null,
                        ),
                        child: const Text(
                          'Find more devices…',
                          style: TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.w500,
                            color: PbColors.accent,
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}

class _OptionRow extends StatelessWidget {
  const _OptionRow({required this.option, required this.onTap});

  final PlayTargetOption option;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final selected = option.selected;
    final dim = !option.enabled;
    return PbPressable(
      onTap: onTap,
      selected: selected,
      semanticLabel: '${option.name}, ${option.subtitle}',
      builder: (context, hovered, focused) => Container(
        constraints: const BoxConstraints(minHeight: 56),
        padding: const EdgeInsets.symmetric(horizontal: 12),
        decoration: BoxDecoration(
          color: selected
              ? PbColors.selected
              : (hovered ? PbColors.selected.withValues(alpha: 0.5) : null),
          borderRadius: BorderRadius.circular(PbMetrics.navRadius),
          border: focused ? Border.all(color: PbColors.accent, width: 2) : null,
        ),
        child: Row(
          children: [
            Expanded(
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      option.name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: selected
                            ? PbColors.onSelected
                            : (dim ? PbColors.textSecondary : PbColors.text),
                      ),
                    ),
                    const SizedBox(height: 2),
                    Row(
                      children: [
                        if (option.protocol != null) ...[
                          // The popup is the raised colour, so the neutral chip uses the
                          // darker fill to stay visible.
                          ProtocolChip(option.protocol!, onSelected: true),
                          const SizedBox(width: 8),
                        ],
                        Flexible(
                          child: Text(
                            option.subtitle,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 13,
                              color: selected
                                  ? PbColors.onSelectedSecondary
                                  : PbColors.textSecondary,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
            if (selected) ...[
              const SizedBox(width: 12),
              const Icon(Icons.check, size: 18, color: PbColors.onSelected),
            ],
          ],
        ),
      ),
    );
  }
}

/// The "Plays on" pill: shows the current destination and opens the picker.
class PlayOnPill extends StatefulWidget {
  const PlayOnPill({
    super.key,
    required this.sender,
    required this.computerName,
    required this.onFindDevices,
    required this.onMessage,
  });

  final TvSenderController sender;
  final String computerName;
  final VoidCallback onFindDevices;
  final ValueChanged<String> onMessage;

  @override
  State<PlayOnPill> createState() => _PlayOnPillState();
}

class _PlayOnPillState extends State<PlayOnPill> {
  bool _open = false;

  Future<void> _toggle(BuildContext anchor) async {
    setState(() => _open = true);
    await showPlayTargetPicker(
      anchor: anchor,
      sender: widget.sender,
      computerName: widget.computerName,
      onFindDevices: widget.onFindDevices,
      onMessage: widget.onMessage,
    );
    if (mounted) setState(() => _open = false);
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: widget.sender,
      builder: (context, _) {
        final label = playTargetLabel(widget.sender);
        return Builder(
          builder: (anchor) => PbPressable(
            onTap: () => _toggle(anchor),
            semanticLabel: 'Plays on $label. Choose where to play',
            builder: (context, hovered, focused) => Container(
              height: PbMetrics.controlHeight,
              constraints: const BoxConstraints(maxWidth: 280),
              padding: const EdgeInsets.symmetric(horizontal: 16),
              decoration: BoxDecoration(
                color: _open || hovered ? PbColors.raised : Colors.transparent,
                borderRadius: BorderRadius.circular(PbMetrics.pillRadius),
                border: Border.all(
                  color: _open || focused ? PbColors.accent : PbColors.outline,
                  width: _open || focused ? 2 : 1,
                ),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Flexible(
                    child: Text(
                      label,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: PbColors.text,
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Icon(
                    _open ? Icons.keyboard_arrow_up : Icons.keyboard_arrow_down,
                    size: 16,
                    color: PbColors.text,
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}
