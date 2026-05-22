import 'package:flutter/material.dart';

import 'pairing_store.dart';
import 'server.dart';

class PairScreen extends StatefulWidget {
  const PairScreen({
    super.key,
    required this.store,
    required this.deviceName,
    required this.hostInfo,
    required this.port,
    required this.phase,
    required this.discoveryError,
    required this.pendingRequest,
    required this.onAllow,
    required this.onDeny,
  });

  final PairingStore store;
  final String deviceName;
  final String hostInfo;
  final int port;
  final PairingPhase phase;
  final String? discoveryError;
  final PendingPairingRequest? pendingRequest;
  final VoidCallback onAllow;
  final VoidCallback onDeny;

  @override
  State<PairScreen> createState() => _PairScreenState();
}

class _PairScreenState extends State<PairScreen> {
  late List<PairedDeviceRecord> _devices;

  @override
  void initState() {
    super.initState();
    _devices = widget.store.pairedDevices;
  }

  void _openDevicesDialog() {
    showDialog<void>(
      context: context,
      barrierColor: Colors.black54,
      builder: (ctx) {
        // Local copy so the dialog can rebuild independently when forgetting.
        var local = List<PairedDeviceRecord>.from(_devices);
        return StatefulBuilder(builder: (ctx, setDialogState) {
          return Dialog(
            backgroundColor: const Color(0xFF1A1A1A),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
            child: SizedBox(
              width: 520,
              child: Padding(
                padding: const EdgeInsets.all(32),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        const Icon(Icons.devices, size: 20, color: Colors.white54),
                        const SizedBox(width: 10),
                        const Text(
                          'Paired Devices',
                          style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600),
                        ),
                        const Spacer(),
                        if (local.isNotEmpty)
                          TextButton(
                            onPressed: () async {
                              await widget.store.forgetAllDevices();
                              final updated = widget.store.pairedDevices;
                              setDialogState(() => local = updated);
                              setState(() => _devices = updated);
                            },
                            style: TextButton.styleFrom(
                                foregroundColor: Colors.redAccent),
                            child: const Text('Remove All'),
                          ),
                        const SizedBox(width: 4),
                        IconButton(
                          onPressed: () => Navigator.of(ctx).pop(),
                          icon: const Icon(Icons.close, size: 18),
                          color: Colors.white54,
                          padding: EdgeInsets.zero,
                          constraints: const BoxConstraints(),
                        ),
                      ],
                    ),
                    const SizedBox(height: 20),
                    if (local.isEmpty)
                      const Padding(
                        padding: EdgeInsets.symmetric(vertical: 24),
                        child: Center(
                          child: Text(
                            'No paired devices.',
                            style: TextStyle(color: Colors.white38),
                          ),
                        ),
                      )
                    else
                      ConstrainedBox(
                        constraints: const BoxConstraints(maxHeight: 320),
                        child: ListView.separated(
                          shrinkWrap: true,
                          itemCount: local.length,
                          separatorBuilder: (_, __) =>
                              const Divider(height: 1, color: Colors.white12),
                          itemBuilder: (_, i) {
                            final device = local[i];
                            return _DialogDeviceRow(
                              device: device,
                              onForget: () async {
                                await widget.store.forgetDevice(device.deviceUUID);
                                final updated = widget.store.pairedDevices;
                                setDialogState(() => local = updated);
                                setState(() => _devices = updated);
                              },
                            );
                          },
                        ),
                      ),
                  ],
                ),
              ),
            ),
          );
        });
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final isAuthed = widget.phase == PairingPhase.authenticated;
    final hasPending = widget.pendingRequest != null;

    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 560),
        child: Padding(
          padding: const EdgeInsets.all(48),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (isAuthed) ...[
                const Icon(Icons.check_circle_outline,
                    size: 64, color: Colors.tealAccent),
                const SizedBox(height: 20),
                Text('Connected',
                    style: Theme.of(context).textTheme.headlineMedium),
                const SizedBox(height: 8),
                const Text(
                  'Phone is paired and ready to cast.',
                  style: TextStyle(color: Colors.white60),
                ),
                const SizedBox(height: 32),
                _InfoLine(label: 'Device', value: widget.deviceName),
                _InfoLine(
                    label: 'Address',
                    value: '${widget.hostInfo} : ${widget.port}'),
                _InfoLine(
                  label: 'Discovery',
                  value: widget.discoveryError == null
                      ? '_playbridge._tcp.'
                      : 'failed: ${widget.discoveryError}',
                  error: widget.discoveryError != null,
                ),
                if (_devices.isNotEmpty) ...[
                  const SizedBox(height: 24),
                  _ManageDevicesButton(
                      count: _devices.length, onTap: _openDevicesDialog),
                ],
              ] else if (hasPending) ...[
                const Icon(Icons.phonelink_ring,
                    size: 64, color: Colors.tealAccent),
                const SizedBox(height: 20),
                Text(
                  'Allow device to connect?',
                  style: Theme.of(context).textTheme.headlineMedium,
                ),
                const SizedBox(height: 16),
                Text(
                  widget.pendingRequest!.deviceName,
                  style: const TextStyle(
                    fontSize: 36,
                    fontWeight: FontWeight.bold,
                    color: Colors.white,
                  ),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 32),
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    FilledButton(
                      autofocus: true,
                      onPressed: widget.onAllow,
                      style: FilledButton.styleFrom(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 40, vertical: 16),
                      ),
                      child:
                          const Text('Allow', style: TextStyle(fontSize: 18)),
                    ),
                    const SizedBox(width: 24),
                    OutlinedButton(
                      onPressed: widget.onDeny,
                      style: OutlinedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 40, vertical: 16),
                      ),
                      child:
                          const Text('Deny', style: TextStyle(fontSize: 18)),
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                const Text(
                  'Request expires in 30 seconds',
                  style: TextStyle(color: Colors.white38, fontSize: 13),
                ),
              ] else ...[
                const Icon(Icons.cast, size: 64, color: Colors.white54),
                const SizedBox(height: 20),
                Text(
                  'Pair with PlayBridge',
                  style: Theme.of(context).textTheme.headlineMedium,
                ),
                const SizedBox(height: 8),
                Text(
                  'Open PlayBridge on your phone and select "${widget.deviceName}".',
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Colors.white60),
                ),
                const SizedBox(height: 32),
                _InfoLine(label: 'Device', value: widget.deviceName),
                _InfoLine(
                    label: 'Address',
                    value: '${widget.hostInfo} : ${widget.port}'),
                _InfoLine(
                  label: 'Discovery',
                  value: widget.discoveryError == null
                      ? '_playbridge._tcp.'
                      : 'failed: ${widget.discoveryError}',
                  error: widget.discoveryError != null,
                ),
                if (_devices.isNotEmpty) ...[
                  const SizedBox(height: 24),
                  _ManageDevicesButton(
                      count: _devices.length, onTap: _openDevicesDialog),
                ],
              ],
            ],
          ),
        ),
      ),
    );
  }
}

// ─── Manage Devices button ────────────────────────────────────────────────────

class _ManageDevicesButton extends StatelessWidget {
  const _ManageDevicesButton({required this.count, required this.onTap});
  final int count;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return TextButton.icon(
      onPressed: onTap,
      icon: const Icon(Icons.devices, size: 15),
      label: Text('Manage Paired Devices ($count)'),
      style: TextButton.styleFrom(
        foregroundColor: Colors.white38,
        textStyle: const TextStyle(fontSize: 13),
      ),
    );
  }
}

// ─── Dialog device row ────────────────────────────────────────────────────────

class _DialogDeviceRow extends StatelessWidget {
  const _DialogDeviceRow({required this.device, required this.onForget});
  final PairedDeviceRecord device;
  final VoidCallback onForget;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(vertical: 4),
      leading: const Icon(Icons.smartphone, size: 18, color: Colors.white38),
      title: Text(device.deviceName, style: const TextStyle(fontSize: 14)),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            _formatDate(device.lastConnected),
            style: const TextStyle(color: Colors.white38, fontSize: 11),
          ),
          const SizedBox(height: 2),
          Text(
            device.deviceUUID,
            style: const TextStyle(
              color: Colors.white24,
              fontSize: 10,
              fontFamily: 'monospace',
            ),
          ),
        ],
      ),
      trailing: TextButton.icon(
        onPressed: onForget,
        icon: const Icon(Icons.delete_outline, color: Colors.redAccent, size: 15),
        label: const Text('Forget',
            style: TextStyle(color: Colors.redAccent, fontSize: 13)),
      ),
    );
  }

  static String _formatDate(DateTime dt) {
    final now = DateTime.now();
    final diff = now.difference(dt);
    if (diff.inDays == 0) {
      return 'Today ${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';
    }
    if (diff.inDays == 1) return 'Yesterday';
    return '${dt.year}-${dt.month.toString().padLeft(2, '0')}-${dt.day.toString().padLeft(2, '0')}';
  }
}

// ─── Info line ────────────────────────────────────────────────────────────────

class _InfoLine extends StatelessWidget {
  const _InfoLine(
      {required this.label, required this.value, this.error = false});
  final String label;
  final String value;
  final bool error;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox(
            width: 90,
            child: Text(label,
                style: const TextStyle(color: Colors.white38, fontSize: 13)),
          ),
          Flexible(
            child: Text(
              value,
              style: TextStyle(
                fontSize: 13,
                color: error ? Colors.redAccent : Colors.white70,
                fontFamily: 'monospace',
              ),
            ),
          ),
        ],
      ),
    );
  }
}
