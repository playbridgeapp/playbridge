import 'package:flutter/painting.dart';

/// Picture scaling offered by the desktop player. Matches Android TV
/// (`MediaSettingsPanel` Fit / Zoom / Fill).
enum VideoScalingMode {
  fit,
  zoom,
  fill,
}

const videoScalingModes = VideoScalingMode.values;

String videoScalingId(VideoScalingMode mode) => switch (mode) {
      VideoScalingMode.fit => 'Fit',
      VideoScalingMode.zoom => 'Zoom',
      VideoScalingMode.fill => 'Fill',
    };

/// Menu title. Zoom/Fill use the TV secondary names.
String videoScalingLabel(VideoScalingMode mode) => switch (mode) {
      VideoScalingMode.fit => 'Fit',
      VideoScalingMode.zoom => 'Crop to fill',
      VideoScalingMode.fill => 'Stretch',
    };

String videoScalingDescription(VideoScalingMode mode) => switch (mode) {
      VideoScalingMode.fit => 'Show the whole picture with letterboxing',
      VideoScalingMode.zoom => 'Fill the screen while preserving aspect ratio',
      VideoScalingMode.fill =>
        'Fill the screen without preserving aspect ratio',
    };

VideoScalingMode parseVideoScalingMode(String? raw) {
  switch (raw) {
    case 'Zoom':
      return VideoScalingMode.zoom;
    case 'Fill':
      return VideoScalingMode.fill;
    case 'Fixed Width':
    case 'Fixed Height':
    case 'Fit':
    default:
      return VideoScalingMode.fit;
  }
}

VideoScalingMode nextVideoScalingMode(VideoScalingMode current) =>
    videoScalingModes[(current.index + 1) % videoScalingModes.length];

/// media_kit [Video] scales the mpv texture itself; panscan/keepaspect do not.
BoxFit videoScalingBoxFit(VideoScalingMode mode) => switch (mode) {
      VideoScalingMode.fit => BoxFit.contain,
      VideoScalingMode.zoom => BoxFit.cover,
      VideoScalingMode.fill => BoxFit.fill,
    };
