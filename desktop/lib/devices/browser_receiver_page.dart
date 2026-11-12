import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../tv_sender_controller.dart';
import '../tv_discovery.dart';
import '../ui/pb_theme.dart';
import '../ui/pb_widgets.dart';
import 'cast_files.dart';
import 'devices_common.dart';

/// Address as a person types it on a TV: no `http://` or trailing slash.
@visibleForTesting
String displayAddress(String url) {
  var out = url.startsWith('http://') ? url.substring(7) : url;
  while (out.endsWith('/')) {
    out = out.substring(0, out.length - 1);
  }
  return out;
}

/// "TV without the app": turns the sender-hosted browser receiver on or off,
/// shows the address to open on the TV, takes the code the TV shows, and
/// manages the paired browser.
class BrowserReceiverPage extends StatelessWidget {
  const BrowserReceiverPage({
    super.key,
    required this.controller,
    required this.onBack,
  });

  final TvSenderController controller;
  final VoidCallback onBack;

  Future<void> _start(BuildContext context) async {
    try {
      await controller.startBrowserReceiver();
    } on Object catch (error) {
      if (!context.mounted) return;
      devicesSnack(context, 'Could not start browser receiver: $error');
    }
  }

  Future<void> _stop(BuildContext context) async {
    try {
      await controller.stopBrowserReceiver();
    } on Object catch (error) {
      if (!context.mounted) return;
      devicesSnack(context, 'Could not stop browser receiver: $error');
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) => _build(context),
    );
  }

  Widget _build(BuildContext context) {
    final running = controller.browserReceiverRunning;
    final primary = controller.browserPrimaryUrl;
    final others = [
      for (final url in controller.browserHost?.urls ?? const <String>[])
        if (url != primary) url,
    ];
    final active = controller.activeTv;
    final browserActive = controller.isConnected &&
        active != null &&
        active.protocol == TvProtocol.webBrowser;
    final lastError = active?.protocol == TvProtocol.webBrowser
        ? controller.lastBrowserError
        : null;
    final requests = controller.browserPairingRequests;

    return Align(
      alignment: Alignment.topCenter,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 760),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Align(
              alignment: Alignment.centerLeft,
              child: PbButton(
                label: 'Devices',
                icon: Icons.chevron_left,
                variant: PbButtonVariant.secondary,
                onPressed: onBack,
              ),
            ),
            const SizedBox(height: 24),
            const Text(
              'TV without the app',
              style: TextStyle(fontSize: 26, fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 8),
            const Text(
              'Any TV, console or computer with a modern web browser can be a '
              'receiver for now. Nothing is installed on it.',
              style: TextStyle(
                fontSize: 15,
                height: 1.6,
                color: PbColors.textSecondary,
              ),
            ),
            const SizedBox(height: 24),
            if (!running)
              _Card(
                children: [
                  const _CardTitle('Turn on the receiver'),
                  const Text(
                    'The address for the TV only exists while this is turned on. '
                    'It starts only when you ask.',
                    style: TextStyle(color: PbColors.textSecondary),
                  ),
                  Align(
                    alignment: Alignment.centerLeft,
                    child: PbButton(
                      label: 'Turn on',
                      onPressed: () => _start(context),
                    ),
                  ),
                ],
              )
            else ...[
              _Card(
                children: [
                  const _CardTitle('1. On the TV, open the browser and go to'),
                  if (primary == null)
                    const Text(
                      'Looking for this computer’s address…',
                      style: TextStyle(color: PbColors.textSecondary),
                    )
                  else
                    Container(
                      padding: const EdgeInsets.fromLTRB(20, 16, 12, 16),
                      decoration: BoxDecoration(
                        color: PbColors.background,
                        borderRadius:
                            BorderRadius.circular(PbMetrics.rowRadius),
                      ),
                      child: Wrap(
                        alignment: WrapAlignment.spaceBetween,
                        crossAxisAlignment: WrapCrossAlignment.center,
                        runSpacing: 8,
                        spacing: 16,
                        children: [
                          SelectableText(
                            displayAddress(primary),
                            style: const TextStyle(
                              fontSize: 30,
                              fontWeight: FontWeight.w700,
                              letterSpacing: 0.6,
                            ),
                          ),
                          PbButton(
                            label: 'Copy',
                            variant: PbButtonVariant.secondary,
                            onPressed: () async {
                              await Clipboard.setData(
                                  ClipboardData(text: primary));
                              if (context.mounted) {
                                devicesSnack(context, 'URL copied');
                              }
                            },
                          ),
                        ],
                      ),
                    ),
                  Text(
                    others.isEmpty
                        ? 'The TV and this computer must be on the same network.'
                        : 'Not loading? Try ${others.map(displayAddress).join(' or ')}. '
                            'The TV and this computer must be on the same network.',
                    style: const TextStyle(
                      fontSize: 13,
                      color: PbColors.textSecondary,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 24),
              _Card(
                children: [
                  const _CardTitle('2. Type the code the TV shows'),
                  if (requests.isEmpty)
                    _CodeEntry(
                      key: const ValueKey('waiting'),
                      onConnect: null,
                      status: const _Status(
                        PbColors.warning,
                        'Waiting for the TV to open the page',
                      ),
                    )
                  else
                    for (final request in requests)
                      _CodeEntry(
                        key: ValueKey(request.sessionId),
                        onConnect: (code) async {
                          try {
                            await controller.approveBrowser(
                                request.sessionId, code);
                          } on Object catch (error) {
                            if (context.mounted) {
                              devicesSnack(context, error.toString());
                            }
                          }
                        },
                        status: _Status(
                          PbColors.warning,
                          '${request.name} is waiting to pair',
                        ),
                      ),
                  if (browserActive)
                    Container(
                      padding: const EdgeInsets.fromLTRB(14, 10, 8, 10),
                      decoration: BoxDecoration(
                        color: PbColors.success.withValues(alpha: 0.08),
                        borderRadius:
                            BorderRadius.circular(PbMetrics.rowRadius),
                        border: Border.all(
                          color: PbColors.success.withValues(alpha: 0.24),
                        ),
                      ),
                      child: Wrap(
                        crossAxisAlignment: WrapCrossAlignment.center,
                        spacing: 8,
                        runSpacing: 4,
                        children: [
                          const StatusDot(PbColors.success),
                          Text('${active.name} is ready to receive media.'),
                          PbButton(
                            label: 'Cast files…',
                            icon: Icons.video_file,
                            variant: PbButtonVariant.secondary,
                            onPressed: () =>
                                pickAndCastFiles(context, controller),
                          ),
                          PbButton(
                            label: 'Forget this browser',
                            variant: PbButtonVariant.quiet,
                            onPressed: () async {
                              try {
                                await controller.forgetActiveBrowserReceiver();
                              } on Object catch (error) {
                                if (context.mounted) {
                                  devicesSnack(
                                    context,
                                    'Could not forget browser: $error',
                                  );
                                }
                              }
                            },
                          ),
                        ],
                      ),
                    ),
                  if (lastError != null && lastError.isNotEmpty)
                    Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: PbColors.error.withValues(alpha: 0.10),
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(
                          color: PbColors.error.withValues(alpha: 0.28),
                        ),
                      ),
                      child: Text(
                        lastError,
                        style: const TextStyle(
                          fontSize: 13,
                          color: PbColors.error,
                        ),
                      ),
                    ),
                ],
              ),
              const SizedBox(height: 24),
              Wrap(
                alignment: WrapAlignment.spaceBetween,
                crossAxisAlignment: WrapCrossAlignment.center,
                spacing: 16,
                runSpacing: 8,
                children: [
                  const Text(
                    'This address only works while this is turned on.',
                    style: TextStyle(color: PbColors.textSecondary),
                  ),
                  PbButton(
                    label: 'Turn off',
                    variant: PbButtonVariant.secondary,
                    onPressed: () => _stop(context),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _Card extends StatelessWidget {
  const _Card({required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: PbColors.card,
        borderRadius: BorderRadius.circular(PbMetrics.cardRadius),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (var i = 0; i < children.length; i++) ...[
            if (i > 0) const SizedBox(height: 14),
            children[i],
          ],
        ],
      ),
    );
  }
}

class _CardTitle extends StatelessWidget {
  const _CardTitle(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Semantics(
        header: true,
        child: Text(
          text,
          style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
        ),
      );
}

class _Status {
  const _Status(this.color, this.text);

  final Color color;
  final String text;
}

/// Code box plus Connect for one browser waiting to pair. [onConnect] null
/// means nothing is waiting yet, so the box is disabled.
class _CodeEntry extends StatefulWidget {
  const _CodeEntry({super.key, required this.onConnect, required this.status});

  final Future<void> Function(String code)? onConnect;
  final _Status status;

  @override
  State<_CodeEntry> createState() => _CodeEntryState();
}

class _CodeEntryState extends State<_CodeEntry> {
  final _code = TextEditingController();

  @override
  void dispose() {
    _code.dispose();
    super.dispose();
  }

  void _connect() {
    final code = _code.text.trim();
    if (code.length == 6) widget.onConnect?.call(code);
  }

  @override
  Widget build(BuildContext context) {
    final enabled = widget.onConnect != null;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          crossAxisAlignment: WrapCrossAlignment.end,
          spacing: 12,
          runSpacing: 12,
          children: [
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text(
                  'Code from the TV',
                  style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
                ),
                const SizedBox(height: 8),
                SizedBox(
                  width: 220,
                  child: CodeField(
                    controller: _code,
                    enabled: enabled,
                    hint: '6 digits',
                    onChanged: (_) => setState(() {}),
                    onSubmitted: (_) => _connect(),
                  ),
                ),
              ],
            ),
            PbButton(
              label: 'Connect',
              onPressed:
                  enabled && _code.text.trim().length == 6 ? _connect : null,
            ),
          ],
        ),
        const SizedBox(height: 14),
        Row(
          children: [
            StatusDot(widget.status.color),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                widget.status.text,
                style: const TextStyle(color: PbColors.textSecondary),
              ),
            ),
          ],
        ),
      ],
    );
  }
}
