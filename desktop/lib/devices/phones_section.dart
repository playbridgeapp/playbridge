import 'package:flutter/material.dart';

import '../pairing_store.dart';
import '../ui/pb_widgets.dart';
import 'devices_common.dart';

/// "Last here …" text for a paired phone, from the time it last connected.
@visibleForTesting
String formatLastHere(DateTime last, DateTime now) {
  final days = DateTime(now.year, now.month, now.day)
      .difference(DateTime(last.year, last.month, last.day))
      .inDays;
  if (days <= 0) return 'Last here today';
  if (days == 1) return 'Last here yesterday';
  if (days < 7) return 'Last here $days days ago';
  return 'Last here ${last.year}-${last.month.toString().padLeft(2, '0')}-'
      '${last.day.toString().padLeft(2, '0')}';
}

/// "Phones that can cast here": paired phones read straight from the store,
/// each with Forget. [onChanged] runs after every removal so the receiver
/// refreshes the tokens it accepts.
class PhonesSection extends StatefulWidget {
  const PhonesSection({
    super.key,
    required this.store,
    required this.onChanged,
  });

  final PairingStore store;
  final VoidCallback onChanged;

  @override
  State<PhonesSection> createState() => _PhonesSectionState();
}

class _PhonesSectionState extends State<PhonesSection> {
  Future<void> _forget(PairedDeviceRecord device) async {
    await widget.store.forgetDevice(device.deviceUUID);
    widget.onChanged();
    if (mounted) setState(() {});
  }

  Future<void> _forgetAll() async {
    await widget.store.forgetAllDevices();
    widget.onChanged();
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final phones = widget.store.pairedDevices;
    final now = DateTime.now();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        PbSectionHeading(
          'Phones that can cast here',
          trailing: phones.length > 1
              ? PbButton(
                  label: 'Forget all',
                  variant: PbButtonVariant.quiet,
                  onPressed: _forgetAll,
                )
              : null,
        ),
        SizedBox(height: phones.isEmpty ? 6 : 0),
        if (phones.isEmpty)
          DevicesHint(
            'No phones yet. Open PlayBridge on your phone and choose '
            '"${widget.store.deviceName}".',
          )
        else
          for (final phone in phones)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: DeviceRow(
                title: phone.deviceName,
                subtitle: formatLastHere(phone.lastConnected, now),
                minHeight: 56,
                actions: [
                  PbButton(
                    label: 'Forget',
                    variant: PbButtonVariant.quiet,
                    onPressed: () => _forget(phone),
                  ),
                ],
              ),
            ),
      ],
    );
  }
}
