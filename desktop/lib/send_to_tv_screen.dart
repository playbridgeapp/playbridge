import 'dart:io';

import 'package:desktop_drop/desktop_drop.dart';
import 'package:file_selector/file_selector.dart';
import 'package:flutter/material.dart';

import 'context_menu_installer.dart';
import 'native_host_installer.dart';
import 'tv_connection_store.dart';
import 'tv_discovery.dart';
import 'tv_sender_client.dart';
import 'tv_sender_controller.dart';

/// The desktop's **sender** surface (D3: lives in the main window). Lists TVs
/// discovered on the LAN, lets the user pair/connect, and manages the active
/// connection. Casting actual content is wired once local-file casting (WS-2)
/// lands; this screen establishes and manages the link.
class SendToTvScreen extends StatefulWidget {
  const SendToTvScreen({super.key, required this.controller});

  final TvSenderController controller;

  @override
  State<SendToTvScreen> createState() => _SendToTvScreenState();
}

class _SendToTvScreenState extends State<SendToTvScreen> {
  bool _dragging = false;

  TvSenderController get controller => widget.controller;

  /// Extensions accepted via drag-and-drop and the file picker.
  static const _mediaExts = {
    'mp4',
    'm4v',
    'mkv',
    'webm',
    'avi',
    'mov',
    'wmv',
    'flv',
    'mp3',
    'flac',
    'm4a',
    'aac',
    'ogg',
    'wav',
  };

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) => _build(context),
    );
  }

  Widget _build(BuildContext context) {
    // Drag-and-drop anywhere on the screen → cast as a playlist.
    return DropTarget(
      onDragEntered: (_) => setState(() => _dragging = true),
      onDragExited: (_) => setState(() => _dragging = false),
      onDragDone: _onDrop,
      child: Stack(
        children: [
          _content(context),
          if (_dragging) const Positioned.fill(child: _DropOverlay()),
        ],
      ),
    );
  }

  Widget _content(BuildContext context) {
    final state = controller.state;
    if (state == SenderConnectionState.waitingForCodeInput ||
        state == SenderConnectionState.verifyingCode) {
      return _PinInputView(
        isVerifying: state == SenderConnectionState.verifyingCode,
        lastCodeWrong: controller.lastSasWrong,
        attemptsLeft: controller.sasAttemptsLeft,
        onSubmit: (code) {
          controller.submitSasCode(code);
        },
        onCancel: () {
          controller.disconnect();
        },
      );
    }

    final discovered = controller.discovered;
    final paired = controller.pairedTvs;
    // Paired TVs not currently visible on the network (so the user can still
    // reconnect / forget them).
    final discoveredUuids = discovered.map((d) => d.uuid).toSet();
    final offlinePaired =
        paired.where((p) => !discoveredUuids.contains(p.uuid)).toList();

    return ListView(
      padding: const EdgeInsets.fromLTRB(28, 24, 28, 28),
      children: [
        const Text(
          'Send to TV',
          style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 4),
        Text(
          'Cast from this computer to a PlayBridge TV.',
          style: TextStyle(
              fontSize: 13, color: Colors.white.withValues(alpha: 0.6)),
        ),
        const SizedBox(height: 20),
        _StatusBanner(
          state: controller.state,
          activeTv: controller.activeTv,
          onDisconnect: controller.disconnect,
        ),
        if (controller.state == SenderConnectionState.connected) ...[
          const SizedBox(height: 12),
          Row(
            children: [
              FilledButton.icon(
                onPressed: _pickAndCast,
                icon: const Icon(Icons.video_file, size: 18),
                label: const Text('Cast files…'),
              ),
              const SizedBox(width: 12),
              Text(
                'or drag files here',
                style: TextStyle(
                    fontSize: 12, color: Colors.white.withValues(alpha: 0.45)),
              ),
            ],
          ),
          if (controller.isCasting) ...[
            const SizedBox(height: 12),
            Row(
              children: [
                const Icon(Icons.cast_connected,
                    size: 16, color: Colors.tealAccent),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Casting — controls are in the Now Playing tab.',
                    style: TextStyle(
                        fontSize: 12,
                        color: Colors.white.withValues(alpha: 0.6)),
                  ),
                ),
              ],
            ),
          ],
        ],
        const SizedBox(height: 20),
        _SectionLabel(
          icon: Icons.wifi_tethering,
          label: 'On your network',
        ),
        const SizedBox(height: 8),
        if (discovered.isEmpty)
          _EmptyHint(
            text: paired.isEmpty
                ? 'Searching for TVs… make sure the TV app is open on the same network.'
                : 'No TVs visible right now. Searching…',
          )
        else
          ...discovered.map((tv) => _DiscoveredRow(
                tv: tv,
                paired: controller.pairedTvs.any((p) => p.uuid == tv.uuid),
                busy: _isBusy(controller.state),
                onTap: () => controller.connectToDiscovered(tv),
                onForget: () => controller.forget(tv.uuid),
              )),
        if (offlinePaired.isNotEmpty) ...[
          const SizedBox(height: 24),
          _SectionLabel(icon: Icons.devices_other, label: 'Paired (offline)'),
          const SizedBox(height: 8),
          ...offlinePaired.map((tv) => _PairedRow(
                tv: tv,
                busy: _isBusy(controller.state),
                onReconnect: () => controller.reconnect(tv),
                onForget: () => controller.forget(tv.uuid),
              )),
        ],
        const SizedBox(height: 28),
        _SectionLabel(icon: Icons.extension, label: 'Browser extension'),
        const SizedBox(height: 8),
        Text(
          'Lets the PlayBridge browser extension cast through this app. Run once '
          'per browser, then reload the extension.',
          style: TextStyle(
              fontSize: 12, color: Colors.white.withValues(alpha: 0.55)),
        ),
        const SizedBox(height: 10),
        Align(
          alignment: Alignment.centerLeft,
          child: OutlinedButton.icon(
            onPressed: () => _setupBrowserCasting(context),
            icon: const Icon(Icons.download_done, size: 18),
            label: const Text('Set up browser casting'),
          ),
        ),
      ],
    );
  }

  Future<void> _setupBrowserCasting(BuildContext context) async {
    final hostResult = await NativeHostInstaller.install();
    final menuResult = await ContextMenuInstaller.install();
    if (!context.mounted) return;
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Browser casting setup'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Native-messaging host registered for:'),
            const SizedBox(height: 8),
            ...hostResult.entries.map((e) => Padding(
                  padding: const EdgeInsets.symmetric(vertical: 2),
                  child: Text('${e.key}: ${e.value}',
                      style: const TextStyle(fontSize: 13)),
                )),
            const SizedBox(height: 12),
            const Text('"Play on TV" right-click menu:'),
            const SizedBox(height: 8),
            ...menuResult.entries.map((e) => Padding(
                  padding: const EdgeInsets.symmetric(vertical: 2),
                  child: Text('${e.key}: ${e.value}',
                      style: const TextStyle(fontSize: 13)),
                )),
            const SizedBox(height: 12),
            Text(
              'Reload the extension in your browser; it should connect within a '
              'few seconds.',
              style: TextStyle(
                  fontSize: 12, color: Colors.white.withValues(alpha: 0.6)),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('OK'),
          ),
        ],
      ),
    );
  }

  bool _isBusy(SenderConnectionState s) =>
      s == SenderConnectionState.connecting ||
      s == SenderConnectionState.waitingForChallenge ||
      s == SenderConnectionState.waitingForCodeInput ||
      s == SenderConnectionState.verifyingCode;

  Future<void> _pickAndCast() async {
    final group = XTypeGroup(label: 'Media', extensions: _mediaExts.toList());
    final picked = await openFiles(acceptedTypeGroups: [group]);
    if (picked.isEmpty) return;
    await _castFiles(picked.map((x) => File(x.path)).toList());
  }

  /// Handle a drag-and-drop: filter to media files, require a connection, cast.
  void _onDrop(DropDoneDetails detail) {
    setState(() => _dragging = false);
    final files = detail.files
        .where((f) => _mediaExts.contains(_ext(f.path)))
        .map((f) => File(f.path))
        .toList();
    if (files.isEmpty) {
      _snack('No supported media files in that drop.');
      return;
    }
    if (controller.state != SenderConnectionState.connected) {
      _snack('Connect to a TV first, then drop files to cast.');
      return;
    }
    _castFiles(files);
  }

  /// Cast one or more files (single → one item, several → a playlist).
  Future<void> _castFiles(List<File> files) async {
    final ok = await controller.castLocalFiles(files);
    if (!ok) _snack('Could not cast — is a TV still connected?');
  }

  void _snack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(message)));
  }

  static String _ext(String path) {
    final dot = path.lastIndexOf('.');
    return dot >= 0 ? path.substring(dot + 1).toLowerCase() : '';
  }
}

/// Full-screen translucent hint shown while files are dragged over the screen.
class _DropOverlay extends StatelessWidget {
  const _DropOverlay();

  @override
  Widget build(BuildContext context) {
    return Container(
      color: Colors.black.withValues(alpha: 0.55),
      alignment: Alignment.center,
      child: Container(
        padding: const EdgeInsets.all(28),
        decoration: BoxDecoration(
          color: Colors.tealAccent.withValues(alpha: 0.10),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
              color: Colors.tealAccent.withValues(alpha: 0.6), width: 2),
        ),
        child: const Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.playlist_add, size: 40, color: Colors.tealAccent),
            SizedBox(height: 12),
            Text('Drop to cast',
                style: TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.w700,
                    color: Colors.white)),
            SizedBox(height: 4),
            Text('Multiple files play as a playlist',
                style: TextStyle(fontSize: 13, color: Colors.white70)),
          ],
        ),
      ),
    );
  }
}

class _StatusBanner extends StatelessWidget {
  const _StatusBanner({
    required this.state,
    required this.activeTv,
    required this.onDisconnect,
  });

  final SenderConnectionState state;
  final TvRecord? activeTv;
  final VoidCallback onDisconnect;

  @override
  Widget build(BuildContext context) {
    final (icon, color, text) = _describe();
    final connected = state == SenderConnectionState.connected;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withValues(alpha: 0.30)),
      ),
      child: Row(
        children: [
          Icon(icon, size: 18, color: color),
          const SizedBox(width: 12),
          Expanded(
            child: Text(text,
                style: const TextStyle(fontSize: 14, color: Colors.white)),
          ),
          if (connected)
            TextButton(
              onPressed: onDisconnect,
              child: const Text('Disconnect'),
            ),
        ],
      ),
    );
  }

  (IconData, Color, String) _describe() {
    final name = activeTv?.name;
    return switch (state) {
      SenderConnectionState.disconnected => (
          Icons.tv_off,
          Colors.white54,
          'Not connected to a TV.',
        ),
      SenderConnectionState.connecting => (
          Icons.cast,
          Colors.tealAccent,
          'Connecting…',
        ),
      SenderConnectionState.waitingForChallenge => (
          Icons.hourglass_top,
          Colors.amberAccent,
          'Establishing secure pairing…',
        ),
      SenderConnectionState.waitingForCodeInput => (
          Icons.lock_open,
          Colors.amberAccent,
          'Enter pairing code on screen.',
        ),
      SenderConnectionState.verifyingCode => (
          Icons.hourglass_top,
          Colors.tealAccent,
          'Verifying code…',
        ),
      SenderConnectionState.connected => (
          Icons.cast_connected,
          Colors.greenAccent,
          name != null ? 'Connected to $name.' : 'Connected.',
        ),
      SenderConnectionState.pairingDenied => (
          Icons.block,
          Colors.redAccent,
          'Pairing was denied on the TV.',
        ),
      SenderConnectionState.authFailed => (
          Icons.lock_reset,
          Colors.amberAccent,
          'The TV rejected the saved token — pair again.',
        ),
      SenderConnectionState.pinMismatch => (
          Icons.gpp_bad,
          Colors.redAccent,
          'Security check failed: the TV\'s certificate changed. Refused to '
              'connect — re-pair if you trust this TV.',
        ),
      SenderConnectionState.error => (
          Icons.error_outline,
          Colors.redAccent,
          'Connection error. Check the TV is reachable and try again.',
        ),
    };
  }
}

class _DiscoveredRow extends StatelessWidget {
  const _DiscoveredRow({
    required this.tv,
    required this.paired,
    required this.busy,
    required this.onTap,
    required this.onForget,
  });

  final DiscoveredTv tv;
  final bool paired;
  final bool busy;
  final VoidCallback onTap;
  final VoidCallback onForget;

  @override
  Widget build(BuildContext context) {
    final secure = tv.wssPort != null;
    return _Row(
      leadingIcon: Icons.tv,
      title: tv.name,
      subtitle: '${tv.host}${secure ? '  ·  encrypted' : '  ·  insecure'}',
      subtitleColor: secure ? Colors.greenAccent : Colors.amberAccent,
      trailing: paired
          ? Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                FilledButton(
                  onPressed: busy ? null : onTap,
                  child: const Text('Connect'),
                ),
                IconButton(
                  tooltip: 'Forget',
                  iconSize: 18,
                  icon: const Icon(Icons.delete_outline),
                  onPressed: onForget,
                ),
              ],
            )
          : FilledButton(
              onPressed: busy ? null : onTap,
              child: const Text('Pair'),
            ),
    );
  }
}

class _PairedRow extends StatelessWidget {
  const _PairedRow({
    required this.tv,
    required this.busy,
    required this.onReconnect,
    required this.onForget,
  });

  final TvRecord tv;
  final bool busy;
  final VoidCallback onReconnect;
  final VoidCallback onForget;

  @override
  Widget build(BuildContext context) {
    return _Row(
      leadingIcon: Icons.tv_off,
      title: tv.name,
      subtitle: 'Last seen ${tv.host}',
      subtitleColor: Colors.white38,
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextButton(
            onPressed: busy ? null : onReconnect,
            child: const Text('Reconnect'),
          ),
          IconButton(
            tooltip: 'Forget',
            iconSize: 18,
            icon: const Icon(Icons.delete_outline),
            onPressed: onForget,
          ),
        ],
      ),
    );
  }
}

class _Row extends StatelessWidget {
  const _Row({
    required this.leadingIcon,
    required this.title,
    required this.subtitle,
    required this.subtitleColor,
    required this.trailing,
  });

  final IconData leadingIcon;
  final String title;
  final String subtitle;
  final Color subtitleColor;
  final Widget trailing;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.05),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.white.withValues(alpha: 0.08)),
      ),
      child: Row(
        children: [
          Icon(leadingIcon, size: 20, color: Colors.white70),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 14, fontWeight: FontWeight.w600)),
                const SizedBox(height: 2),
                Text(subtitle,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: 12, color: subtitleColor)),
              ],
            ),
          ),
          const SizedBox(width: 8),
          trailing,
        ],
      ),
    );
  }
}

class _SectionLabel extends StatelessWidget {
  const _SectionLabel({required this.icon, required this.label});

  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(icon, size: 15, color: Colors.white.withValues(alpha: 0.5)),
        const SizedBox(width: 8),
        Text(
          label.toUpperCase(),
          style: TextStyle(
            fontSize: 11,
            letterSpacing: 0.8,
            fontWeight: FontWeight.w600,
            color: Colors.white.withValues(alpha: 0.5),
          ),
        ),
      ],
    );
  }
}

class _EmptyHint extends StatelessWidget {
  const _EmptyHint({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 16),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.03),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.white.withValues(alpha: 0.06)),
      ),
      child: Row(
        children: [
          const SizedBox(
            width: 16,
            height: 16,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(text,
                style: TextStyle(
                    fontSize: 13, color: Colors.white.withValues(alpha: 0.6))),
          ),
        ],
      ),
    );
  }
}

class _PinInputView extends StatefulWidget {
  const _PinInputView({
    required this.isVerifying,
    required this.onSubmit,
    required this.onCancel,
    this.lastCodeWrong = false,
    this.attemptsLeft = 3,
  });

  final bool isVerifying;
  final ValueChanged<String> onSubmit;
  final VoidCallback onCancel;
  final bool lastCodeWrong;
  final int attemptsLeft;

  @override
  State<_PinInputView> createState() => _PinInputViewState();
}

class _PinInputViewState extends State<_PinInputView> {
  final TextEditingController _pinController = TextEditingController();
  final FocusNode _focusNode = FocusNode();

  @override
  void dispose() {
    _pinController.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Container(
        padding: const EdgeInsets.all(32),
        margin: const EdgeInsets.symmetric(horizontal: 24),
        decoration: BoxDecoration(
          color: Colors.white.withValues(alpha: 0.03),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
            color: Colors.white.withValues(alpha: 0.08),
          ),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.2),
              blurRadius: 20,
              offset: const Offset(0, 8),
            ),
          ],
        ),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 360),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.security, size: 48, color: Colors.tealAccent),
              const SizedBox(height: 20),
              const Text(
                'Enter Pairing Code',
                style: TextStyle(fontSize: 20, fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 8),
              const Text(
                'Type the 6-digit code shown on your TV screen.',
                style: TextStyle(fontSize: 13, color: Colors.white60),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 32),
              if (widget.isVerifying)
                const SizedBox(
                  height: 80,
                  child: Center(
                    child: CircularProgressIndicator(color: Colors.tealAccent),
                  ),
                )
              else ...[
                SizedBox(
                  width: 240,
                  child: TextField(
                    controller: _pinController,
                    focusNode: _focusNode,
                    autofocus: true,
                    keyboardType: TextInputType.number,
                    textAlign: TextAlign.center,
                    maxLength: 6,
                    style: const TextStyle(
                      fontSize: 28,
                      fontWeight: FontWeight.bold,
                      letterSpacing: 12,
                      fontFamily: 'Courier',
                    ),
                    decoration: InputDecoration(
                      counterText: '',
                      hintText: '000000',
                      hintStyle: TextStyle(
                        color: Colors.white.withValues(alpha: 0.15),
                        letterSpacing: 12,
                      ),
                      enabledBorder: const UnderlineInputBorder(
                        borderSide: BorderSide(color: Colors.white24, width: 2),
                      ),
                      focusedBorder: const UnderlineInputBorder(
                        borderSide:
                            BorderSide(color: Colors.tealAccent, width: 2),
                      ),
                    ),
                    onChanged: (val) {
                      if (val.length == 6) {
                        widget.onSubmit(val);
                      }
                    },
                  ),
                ),
                if (widget.lastCodeWrong) ...[
                  const SizedBox(height: 12),
                  Text(
                    'Incorrect code — ${widget.attemptsLeft} '
                    '${widget.attemptsLeft == 1 ? 'try' : 'tries'} left',
                    style:
                        const TextStyle(fontSize: 13, color: Colors.redAccent),
                    textAlign: TextAlign.center,
                  ),
                ],
                const SizedBox(height: 32),
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    OutlinedButton(
                      onPressed: widget.onCancel,
                      style: OutlinedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 24, vertical: 12),
                      ),
                      child: const Text('Cancel'),
                    ),
                    const SizedBox(width: 16),
                    FilledButton(
                      onPressed: () {
                        final code = _pinController.text.trim();
                        if (code.length == 6) {
                          widget.onSubmit(code);
                        }
                      },
                      style: FilledButton.styleFrom(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 24, vertical: 12),
                      ),
                      child: const Text('Submit'),
                    ),
                  ],
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
