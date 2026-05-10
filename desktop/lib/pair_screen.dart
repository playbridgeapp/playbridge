import 'dart:ui' show ImageFilter;

import 'package:flutter/material.dart';

import 'server.dart';

class PairScreen extends StatelessWidget {
  const PairScreen({
    super.key,
    required this.pin,
    required this.deviceName,
    required this.hostInfo,
    required this.port,
    required this.phase,
    required this.discoveryError,
  });

  final String pin;
  final String deviceName;
  final String hostInfo;
  final int port;
  final PairingPhase phase;
  final String? discoveryError;

  @override
  Widget build(BuildContext context) {
    final isAuthed = phase == PairingPhase.authenticated;
    final isAwaitingPin = phase == PairingPhase.awaitingPin;

    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 560),
        child: Padding(
          padding: const EdgeInsets.all(48),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (isAuthed) ...[
                const Icon(Icons.check_circle_outline, size: 64, color: Colors.tealAccent),
                const SizedBox(height: 20),
                Text('Connected', style: Theme.of(context).textTheme.headlineMedium),
                const SizedBox(height: 8),
                const Text(
                  'Phone is paired and ready to cast.',
                  style: TextStyle(color: Colors.white60),
                ),
                const SizedBox(height: 32),
                _InfoLine(label: 'Device', value: deviceName),
                _InfoLine(label: 'Address', value: '$hostInfo : $port'),
                _InfoLine(
                  label: 'Discovery',
                  value: discoveryError == null ? '_playbridge._tcp.' : 'failed: $discoveryError',
                  error: discoveryError != null,
                ),
              ] else ...[
                Icon(
                  isAwaitingPin ? Icons.phonelink_ring : Icons.cast,
                  size: 64,
                  color: isAwaitingPin ? Colors.tealAccent : Colors.white54,
                ),
                const SizedBox(height: 20),
                Text(
                  isAwaitingPin ? 'Phone is pairing…' : 'Pair with PlayBridge',
                  style: Theme.of(context).textTheme.headlineMedium,
                ),
                const SizedBox(height: 8),
                Text(
                  'On the phone, pick "$deviceName" then enter this PIN:',
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Colors.white60),
                ),
                const SizedBox(height: 32),
                _PinDisplay(pin: pin),
                const SizedBox(height: 32),
                _InfoLine(label: 'Device', value: deviceName),
                _InfoLine(label: 'Address', value: '$hostInfo : $port'),
                _InfoLine(
                  label: 'Discovery',
                  value: discoveryError == null ? '_playbridge._tcp.' : 'failed: $discoveryError',
                  error: discoveryError != null,
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _PinDisplay extends StatelessWidget {
  const _PinDisplay({required this.pin});
  final String pin;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (final ch in pin.split(''))
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 6),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(14),
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 12, sigmaY: 12),
                child: Container(
                  width: 64,
                  height: 80,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.07),
                    border: Border.all(color: Colors.white.withValues(alpha: 0.18)),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: Text(
                    ch,
                    style: const TextStyle(
                      fontSize: 44,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 2,
                    ),
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }
}

class _InfoLine extends StatelessWidget {
  const _InfoLine({required this.label, required this.value, this.error = false});
  final String label;
  final String value;
  final bool error;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox(
            width: 90,
            child: Text(label, style: const TextStyle(color: Colors.white38, fontSize: 13)),
          ),
          Text(
            value,
            style: TextStyle(
              fontSize: 13,
              color: error ? Colors.redAccent : Colors.white70,
              fontFamily: 'monospace',
            ),
          ),
        ],
      ),
    );
  }
}
