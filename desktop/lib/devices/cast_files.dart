import 'dart:io';

import 'package:file_selector/file_selector.dart';
import 'package:flutter/material.dart';

import '../tv_sender_controller.dart';

/// Extensions accepted by the "Cast files" picker.
const castMediaExtensions = {
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

/// Shows a floating message from async code that may outlive the screen.
void devicesSnack(BuildContext context, String message) {
  if (!context.mounted) return;
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
}

/// Opens the file picker and casts the chosen media to the connected TV
/// (one file becomes one item, several become a playlist).
Future<void> pickAndCastFiles(
  BuildContext context,
  TvSenderController controller,
) async {
  final group =
      XTypeGroup(label: 'Media', extensions: castMediaExtensions.toList());
  final picked = await openFiles(acceptedTypeGroups: [group]);
  if (picked.isEmpty) return;
  final ok =
      await controller.castLocalFiles(picked.map((x) => File(x.path)).toList());
  if (ok || !context.mounted) return;
  final detail = controller.lastCastError?.trim();
  devicesSnack(
    context,
    detail != null && detail.isNotEmpty
        ? 'Could not cast: ${detail.length > 200 ? '${detail.substring(0, 197)}…' : detail}'
        : 'Could not cast — is a TV still connected?',
  );
}
