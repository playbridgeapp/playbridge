import 'package:flutter/material.dart';

import '../ui/pb_theme.dart';
import '../ui/pb_widgets.dart';
import 'play_request.dart';

/// Asks for a web link to play. Returns the accepted http(s) link, or null if
/// the user cancels.
Future<String?> showPasteLinkDialog(BuildContext context) => showDialog<String>(
      context: context,
      builder: (_) => const PasteLinkDialog(),
    );

class PasteLinkDialog extends StatefulWidget {
  const PasteLinkDialog({super.key});

  @override
  State<PasteLinkDialog> createState() => _PasteLinkDialogState();
}

class _PasteLinkDialogState extends State<PasteLinkDialog> {
  final _controller = TextEditingController();
  String? _error;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _submit() {
    final link = parsePlayableLink(_controller.text);
    if (link == null) {
      setState(() => _error = 'Enter a web address that starts with http:// '
          'or https://');
      return;
    }
    Navigator.of(context).pop(link);
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 480),
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text(
                'Paste link',
                style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w600,
                  color: PbColors.text,
                ),
              ),
              const SizedBox(height: 6),
              const Text(
                'A link to a video or song.',
                style: TextStyle(color: PbColors.textSecondary),
              ),
              const SizedBox(height: 16),
              TextField(
                controller: _controller,
                autofocus: true,
                keyboardType: TextInputType.url,
                onChanged: (_) {
                  if (_error != null) setState(() => _error = null);
                },
                onSubmitted: (_) => _submit(),
                decoration: InputDecoration(
                  hintText: 'https://',
                  errorText: _error,
                  errorMaxLines: 2,
                ),
              ),
              const SizedBox(height: 20),
              Wrap(
                alignment: WrapAlignment.end,
                spacing: 8,
                runSpacing: 8,
                children: [
                  PbButton(
                    label: 'Cancel',
                    variant: PbButtonVariant.quiet,
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                  PbButton(label: 'Play', onPressed: _submit),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
