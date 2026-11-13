import 'package:flutter/material.dart';

import '../ui/pb_theme.dart';
import '../ui/pb_widgets.dart';
import 'devices_common.dart';

/// Pairing-code card shown over the dimmed Devices page while a PlayBridge TV
/// waits for the 6-digit code it displays.
///
/// The code is submitted as soon as the sixth digit is typed, or with Pair.
class PairTvCard extends StatefulWidget {
  const PairTvCard({
    super.key,
    required this.tvName,
    required this.isVerifying,
    required this.lastCodeWrong,
    required this.attemptsLeft,
    required this.onSubmit,
    required this.onCancel,
  });

  /// Name of the TV being paired; null falls back to "your TV".
  final String? tvName;
  final bool isVerifying;
  final bool lastCodeWrong;
  final int attemptsLeft;
  final ValueChanged<String> onSubmit;
  final VoidCallback onCancel;

  @override
  State<PairTvCard> createState() => _PairTvCardState();
}

class _PairTvCardState extends State<PairTvCard> {
  final _code = TextEditingController();

  @override
  void didUpdateWidget(PairTvCard old) {
    super.didUpdateWidget(old);
    // A wrong code was rejected: start the next attempt from an empty box.
    if (widget.lastCodeWrong && !old.lastCodeWrong ||
        old.isVerifying && !widget.isVerifying) {
      _code.clear();
    }
  }

  @override
  void dispose() {
    _code.dispose();
    super.dispose();
  }

  void _submit() {
    final code = _code.text.trim();
    if (code.length == 6) widget.onSubmit(code);
  }

  @override
  Widget build(BuildContext context) {
    final name = widget.tvName ?? 'your TV';
    final verifying = widget.isVerifying;
    return Semantics(
      label: 'Pair with $name',
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 460),
        child: Container(
          padding: const EdgeInsets.all(32),
          decoration: BoxDecoration(
            color: PbColors.card,
            borderRadius: BorderRadius.circular(20),
            border: Border.all(color: PbColors.outline),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'Pair with $name',
                style:
                    const TextStyle(fontSize: 22, fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 6),
              const Text(
                'The TV is showing a 6-digit code. Type it here.',
                style: TextStyle(fontSize: 15, color: PbColors.textSecondary),
              ),
              const SizedBox(height: 20),
              const Text(
                'Code from the TV',
                style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 8),
              CodeField(
                controller: _code,
                autofocus: true,
                enabled: !verifying,
                fontSize: 32,
                height: 64,
                borderWidth: 2,
                borderColor: PbColors.accent,
                onChanged: (v) {
                  if (v.length == 6) widget.onSubmit(v);
                },
                onSubmitted: (_) => _submit(),
              ),
              const SizedBox(height: 8),
              if (verifying)
                const Row(
                  children: [
                    SizedBox(
                      width: 14,
                      height: 14,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                    SizedBox(width: 10),
                    Text(
                      'Checking the code…',
                      style: TextStyle(
                        fontSize: 13,
                        color: PbColors.textSecondary,
                      ),
                    ),
                  ],
                )
              else if (widget.lastCodeWrong)
                Text(
                  "That code didn't match. ${widget.attemptsLeft} "
                  '${widget.attemptsLeft == 1 ? 'try' : 'tries'} left.',
                  style: const TextStyle(fontSize: 13, color: PbColors.error),
                )
              else
                const Text(
                  'You only do this once for each TV.',
                  style: TextStyle(fontSize: 13, color: PbColors.textSecondary),
                ),
              const SizedBox(height: 20),
              Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  PbButton(
                    label: 'Cancel',
                    variant: PbButtonVariant.secondary,
                    onPressed: widget.onCancel,
                  ),
                  const SizedBox(width: 12),
                  PbButton(
                    label: 'Pair',
                    onPressed: verifying ? null : _submit,
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
