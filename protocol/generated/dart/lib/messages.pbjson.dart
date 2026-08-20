// This is a generated file - do not edit.
//
// Generated from messages.proto.

// @dart = 3.3

// ignore_for_file: annotate_overrides, camel_case_types, comment_references
// ignore_for_file: constant_identifier_names
// ignore_for_file: curly_braces_in_flow_control_structures
// ignore_for_file: deprecated_member_use_from_same_package, library_prefixes
// ignore_for_file: non_constant_identifier_names, unused_import

import 'dart:convert' as $convert;
import 'dart:core' as $core;
import 'dart:typed_data' as $typed_data;

@$core.Deprecated('Use mouseEventTypeDescriptor instead')
const MouseEventType$json = {
  '1': 'MouseEventType',
  '2': [
    {'1': 'MOUSE_MOVE', '2': 0},
    {'1': 'MOUSE_CLICK', '2': 1},
    {'1': 'MOUSE_SCROLL', '2': 2},
    {'1': 'MOUSE_DOWN', '2': 3},
    {'1': 'MOUSE_UP', '2': 4},
  ],
};

/// Descriptor for `MouseEventType`. Decode as a `google.protobuf.EnumDescriptorProto`.
final $typed_data.Uint8List mouseEventTypeDescriptor = $convert.base64Decode(
    'Cg5Nb3VzZUV2ZW50VHlwZRIOCgpNT1VTRV9NT1ZFEAASDwoLTU9VU0VfQ0xJQ0sQARIQCgxNT1'
    'VTRV9TQ1JPTEwQAhIOCgpNT1VTRV9ET1dOEAMSDAoITU9VU0VfVVAQBA==');

@$core.Deprecated('Use messageEnvelopeDescriptor instead')
const MessageEnvelope$json = {
  '1': 'MessageEnvelope',
  '2': [
    {'1': 'type', '3': 1, '4': 1, '5': 9, '10': 'type'},
    {'1': 'action', '3': 2, '4': 1, '5': 9, '9': 0, '10': 'action', '17': true},
    {'1': 'state', '3': 3, '4': 1, '5': 9, '9': 1, '10': 'state', '17': true},
    {'1': 'position', '3': 4, '4': 1, '5': 3, '9': 2, '10': 'position', '17': true},
    {'1': 'duration', '3': 5, '4': 1, '5': 3, '9': 3, '10': 'duration', '17': true},
    {'1': 'title', '3': 6, '4': 1, '5': 9, '9': 4, '10': 'title', '17': true},
  ],
  '8': [
    {'1': '_action'},
    {'1': '_state'},
    {'1': '_position'},
    {'1': '_duration'},
    {'1': '_title'},
  ],
};

/// Descriptor for `MessageEnvelope`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List messageEnvelopeDescriptor = $convert.base64Decode(
    'Cg9NZXNzYWdlRW52ZWxvcGUSEgoEdHlwZRgBIAEoCVIEdHlwZRIbCgZhY3Rpb24YAiABKAlIAF'
    'IGYWN0aW9uiAEBEhkKBXN0YXRlGAMgASgJSAFSBXN0YXRliAEBEh8KCHBvc2l0aW9uGAQgASgD'
    'SAJSCHBvc2l0aW9uiAEBEh8KCGR1cmF0aW9uGAUgASgDSANSCGR1cmF0aW9uiAEBEhkKBXRpdG'
    'xlGAYgASgJSARSBXRpdGxliAEBQgkKB19hY3Rpb25CCAoGX3N0YXRlQgsKCV9wb3NpdGlvbkIL'
    'CglfZHVyYXRpb25CCAoGX3RpdGxl');

@$core.Deprecated('Use seriesEpisodeRefDescriptor instead')
const SeriesEpisodeRef$json = {
  '1': 'SeriesEpisodeRef',
  '2': [
    {'1': 'season', '3': 1, '4': 1, '5': 5, '10': 'season'},
    {'1': 'episode', '3': 2, '4': 1, '5': 5, '10': 'episode'},
    {'1': 'title', '3': 3, '4': 1, '5': 9, '9': 0, '10': 'title', '17': true},
  ],
  '8': [
    {'1': '_title'},
  ],
};

/// Descriptor for `SeriesEpisodeRef`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List seriesEpisodeRefDescriptor = $convert.base64Decode(
    'ChBTZXJpZXNFcGlzb2RlUmVmEhYKBnNlYXNvbhgBIAEoBVIGc2Vhc29uEhgKB2VwaXNvZGUYAi'
    'ABKAVSB2VwaXNvZGUSGQoFdGl0bGUYAyABKAlIAFIFdGl0bGWIAQFCCAoGX3RpdGxl');

@$core.Deprecated('Use visualMetadataDescriptor instead')
const VisualMetadata$json = {
  '1': 'VisualMetadata',
  '2': [
    {'1': 'title', '3': 1, '4': 1, '5': 9, '10': 'title'},
    {'1': 'year', '3': 2, '4': 1, '5': 9, '9': 0, '10': 'year', '17': true},
    {'1': 'rating', '3': 3, '4': 1, '5': 9, '9': 1, '10': 'rating', '17': true},
    {'1': 'runtime', '3': 4, '4': 1, '5': 9, '9': 2, '10': 'runtime', '17': true},
    {'1': 'overview', '3': 5, '4': 1, '5': 9, '9': 3, '10': 'overview', '17': true},
    {'1': 'genres', '3': 6, '4': 3, '5': 9, '10': 'genres'},
    {'1': 'cast', '3': 7, '4': 3, '5': 9, '10': 'cast'},
    {'1': 'director', '3': 8, '4': 3, '5': 9, '10': 'director'},
    {'1': 'backdrop_url', '3': 9, '4': 1, '5': 9, '9': 4, '10': 'backdropUrl', '17': true},
    {'1': 'poster_url', '3': 10, '4': 1, '5': 9, '9': 5, '10': 'posterUrl', '17': true},
    {'1': 'logo_url', '3': 11, '4': 1, '5': 9, '9': 6, '10': 'logoUrl', '17': true},
    {'1': 'season', '3': 12, '4': 1, '5': 5, '9': 7, '10': 'season', '17': true},
    {'1': 'episode', '3': 13, '4': 1, '5': 5, '9': 8, '10': 'episode', '17': true},
    {'1': 'episode_title', '3': 14, '4': 1, '5': 9, '9': 9, '10': 'episodeTitle', '17': true},
    {'1': 'imdb_id', '3': 15, '4': 1, '5': 9, '9': 10, '10': 'imdbId', '17': true},
    {'1': 'tmdb_id', '3': 16, '4': 1, '5': 9, '9': 11, '10': 'tmdbId', '17': true},
  ],
  '8': [
    {'1': '_year'},
    {'1': '_rating'},
    {'1': '_runtime'},
    {'1': '_overview'},
    {'1': '_backdrop_url'},
    {'1': '_poster_url'},
    {'1': '_logo_url'},
    {'1': '_season'},
    {'1': '_episode'},
    {'1': '_episode_title'},
    {'1': '_imdb_id'},
    {'1': '_tmdb_id'},
  ],
};

/// Descriptor for `VisualMetadata`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List visualMetadataDescriptor = $convert.base64Decode(
    'Cg5WaXN1YWxNZXRhZGF0YRIUCgV0aXRsZRgBIAEoCVIFdGl0bGUSFwoEeWVhchgCIAEoCUgAUg'
    'R5ZWFyiAEBEhsKBnJhdGluZxgDIAEoCUgBUgZyYXRpbmeIAQESHQoHcnVudGltZRgEIAEoCUgC'
    'UgdydW50aW1liAEBEh8KCG92ZXJ2aWV3GAUgASgJSANSCG92ZXJ2aWV3iAEBEhYKBmdlbnJlcx'
    'gGIAMoCVIGZ2VucmVzEhIKBGNhc3QYByADKAlSBGNhc3QSGgoIZGlyZWN0b3IYCCADKAlSCGRp'
    'cmVjdG9yEiYKDGJhY2tkcm9wX3VybBgJIAEoCUgEUgtiYWNrZHJvcFVybIgBARIiCgpwb3N0ZX'
    'JfdXJsGAogASgJSAVSCXBvc3RlclVybIgBARIeCghsb2dvX3VybBgLIAEoCUgGUgdsb2dvVXJs'
    'iAEBEhsKBnNlYXNvbhgMIAEoBUgHUgZzZWFzb26IAQESHQoHZXBpc29kZRgNIAEoBUgIUgdlcG'
    'lzb2RliAEBEigKDWVwaXNvZGVfdGl0bGUYDiABKAlICVIMZXBpc29kZVRpdGxliAEBEhwKB2lt'
    'ZGJfaWQYDyABKAlIClIGaW1kYklkiAEBEhwKB3RtZGJfaWQYECABKAlIC1IGdG1kYklkiAEBQg'
    'cKBV95ZWFyQgkKB19yYXRpbmdCCgoIX3J1bnRpbWVCCwoJX292ZXJ2aWV3Qg8KDV9iYWNrZHJv'
    'cF91cmxCDQoLX3Bvc3Rlcl91cmxCCwoJX2xvZ29fdXJsQgkKB19zZWFzb25CCgoIX2VwaXNvZG'
    'VCEAoOX2VwaXNvZGVfdGl0bGVCCgoIX2ltZGJfaWRCCgoIX3RtZGJfaWQ=');

@$core.Deprecated('Use subtitleResourceDescriptor instead')
const SubtitleResource$json = {
  '1': 'SubtitleResource',
  '2': [
    {'1': 'url', '3': 1, '4': 1, '5': 9, '10': 'url'},
    {'1': 'headers', '3': 2, '4': 3, '5': 11, '6': '.playbridge.SubtitleResource.HeadersEntry', '10': 'headers'},
    {'1': 'label', '3': 3, '4': 1, '5': 9, '9': 0, '10': 'label', '17': true},
    {'1': 'language', '3': 4, '4': 1, '5': 9, '9': 1, '10': 'language', '17': true},
  ],
  '3': [SubtitleResource_HeadersEntry$json],
  '8': [
    {'1': '_label'},
    {'1': '_language'},
  ],
};

@$core.Deprecated('Use subtitleResourceDescriptor instead')
const SubtitleResource_HeadersEntry$json = {
  '1': 'HeadersEntry',
  '2': [
    {'1': 'key', '3': 1, '4': 1, '5': 9, '10': 'key'},
    {'1': 'value', '3': 2, '4': 1, '5': 9, '10': 'value'},
  ],
  '7': {'7': true},
};

/// Descriptor for `SubtitleResource`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List subtitleResourceDescriptor = $convert.base64Decode(
    'ChBTdWJ0aXRsZVJlc291cmNlEhAKA3VybBgBIAEoCVIDdXJsEkMKB2hlYWRlcnMYAiADKAsyKS'
    '5wbGF5YnJpZGdlLlN1YnRpdGxlUmVzb3VyY2UuSGVhZGVyc0VudHJ5UgdoZWFkZXJzEhkKBWxh'
    'YmVsGAMgASgJSABSBWxhYmVsiAEBEh8KCGxhbmd1YWdlGAQgASgJSAFSCGxhbmd1YWdliAEBGj'
    'oKDEhlYWRlcnNFbnRyeRIQCgNrZXkYASABKAlSA2tleRIUCgV2YWx1ZRgCIAEoCVIFdmFsdWU6'
    'AjgBQggKBl9sYWJlbEILCglfbGFuZ3VhZ2U=');

@$core.Deprecated('Use playPayloadDescriptor instead')
const PlayPayload$json = {
  '1': 'PlayPayload',
  '2': [
    {'1': 'url', '3': 1, '4': 1, '5': 9, '10': 'url'},
    {'1': 'title', '3': 2, '4': 1, '5': 9, '9': 0, '10': 'title', '17': true},
    {'1': 'headers', '3': 3, '4': 3, '5': 11, '6': '.playbridge.PlayPayload.HeadersEntry', '10': 'headers'},
    {'1': 'content_type', '3': 4, '4': 1, '5': 9, '9': 1, '10': 'contentType', '17': true},
    {'1': 'subtitles', '3': 5, '4': 3, '5': 9, '10': 'subtitles'},
    {'1': 'detected_by', '3': 6, '4': 1, '5': 9, '9': 2, '10': 'detectedBy', '17': true},
    {'1': 'player_mode', '3': 7, '4': 1, '5': 9, '9': 3, '10': 'playerMode', '17': true},
    {'1': 'preferred_audio_language', '3': 8, '4': 1, '5': 9, '9': 4, '10': 'preferredAudioLanguage', '17': true},
    {'1': 'preferred_subtitle_language', '3': 9, '4': 1, '5': 9, '9': 5, '10': 'preferredSubtitleLanguage', '17': true},
    {'1': 'default_video_quality', '3': 10, '4': 1, '5': 9, '9': 6, '10': 'defaultVideoQuality', '17': true},
    {'1': 'max_bitrate_cap_mbps', '3': 11, '4': 1, '5': 1, '9': 7, '10': 'maxBitrateCapMbps', '17': true},
    {'1': 'visual_metadata', '3': 12, '4': 1, '5': 11, '6': '.playbridge.VisualMetadata', '9': 8, '10': 'visualMetadata', '17': true},
    {'1': 'binge_group', '3': 13, '4': 1, '5': 9, '9': 9, '10': 'bingeGroup', '17': true},
    {'1': 'start_position_ms', '3': 14, '4': 1, '5': 3, '9': 10, '10': 'startPositionMs', '17': true},
    {'1': 'allowed_private_origins', '3': 15, '4': 3, '5': 9, '10': 'allowedPrivateOrigins'},
    {'1': 'subtitle_resources', '3': 16, '4': 3, '5': 11, '6': '.playbridge.SubtitleResource', '10': 'subtitleResources'},
  ],
  '3': [PlayPayload_HeadersEntry$json],
  '8': [
    {'1': '_title'},
    {'1': '_content_type'},
    {'1': '_detected_by'},
    {'1': '_player_mode'},
    {'1': '_preferred_audio_language'},
    {'1': '_preferred_subtitle_language'},
    {'1': '_default_video_quality'},
    {'1': '_max_bitrate_cap_mbps'},
    {'1': '_visual_metadata'},
    {'1': '_binge_group'},
    {'1': '_start_position_ms'},
  ],
};

@$core.Deprecated('Use playPayloadDescriptor instead')
const PlayPayload_HeadersEntry$json = {
  '1': 'HeadersEntry',
  '2': [
    {'1': 'key', '3': 1, '4': 1, '5': 9, '10': 'key'},
    {'1': 'value', '3': 2, '4': 1, '5': 9, '10': 'value'},
  ],
  '7': {'7': true},
};

/// Descriptor for `PlayPayload`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List playPayloadDescriptor = $convert.base64Decode(
    'CgtQbGF5UGF5bG9hZBIQCgN1cmwYASABKAlSA3VybBIZCgV0aXRsZRgCIAEoCUgAUgV0aXRsZY'
    'gBARI+CgdoZWFkZXJzGAMgAygLMiQucGxheWJyaWRnZS5QbGF5UGF5bG9hZC5IZWFkZXJzRW50'
    'cnlSB2hlYWRlcnMSJgoMY29udGVudF90eXBlGAQgASgJSAFSC2NvbnRlbnRUeXBliAEBEhwKCX'
    'N1YnRpdGxlcxgFIAMoCVIJc3VidGl0bGVzEiQKC2RldGVjdGVkX2J5GAYgASgJSAJSCmRldGVj'
    'dGVkQnmIAQESJAoLcGxheWVyX21vZGUYByABKAlIA1IKcGxheWVyTW9kZYgBARI9ChhwcmVmZX'
    'JyZWRfYXVkaW9fbGFuZ3VhZ2UYCCABKAlIBFIWcHJlZmVycmVkQXVkaW9MYW5ndWFnZYgBARJD'
    'ChtwcmVmZXJyZWRfc3VidGl0bGVfbGFuZ3VhZ2UYCSABKAlIBVIZcHJlZmVycmVkU3VidGl0bG'
    'VMYW5ndWFnZYgBARI3ChVkZWZhdWx0X3ZpZGVvX3F1YWxpdHkYCiABKAlIBlITZGVmYXVsdFZp'
    'ZGVvUXVhbGl0eYgBARI0ChRtYXhfYml0cmF0ZV9jYXBfbWJwcxgLIAEoAUgHUhFtYXhCaXRyYX'
    'RlQ2FwTWJwc4gBARJICg92aXN1YWxfbWV0YWRhdGEYDCABKAsyGi5wbGF5YnJpZGdlLlZpc3Vh'
    'bE1ldGFkYXRhSAhSDnZpc3VhbE1ldGFkYXRhiAEBEiQKC2JpbmdlX2dyb3VwGA0gASgJSAlSCm'
    'JpbmdlR3JvdXCIAQESLwoRc3RhcnRfcG9zaXRpb25fbXMYDiABKANIClIPc3RhcnRQb3NpdGlv'
    'bk1ziAEBEjYKF2FsbG93ZWRfcHJpdmF0ZV9vcmlnaW5zGA8gAygJUhVhbGxvd2VkUHJpdmF0ZU'
    '9yaWdpbnMSSwoSc3VidGl0bGVfcmVzb3VyY2VzGBAgAygLMhwucGxheWJyaWRnZS5TdWJ0aXRs'
    'ZVJlc291cmNlUhFzdWJ0aXRsZVJlc291cmNlcxo6CgxIZWFkZXJzRW50cnkSEAoDa2V5GAEgAS'
    'gJUgNrZXkSFAoFdmFsdWUYAiABKAlSBXZhbHVlOgI4AUIICgZfdGl0bGVCDwoNX2NvbnRlbnRf'
    'dHlwZUIOCgxfZGV0ZWN0ZWRfYnlCDgoMX3BsYXllcl9tb2RlQhsKGV9wcmVmZXJyZWRfYXVkaW'
    '9fbGFuZ3VhZ2VCHgocX3ByZWZlcnJlZF9zdWJ0aXRsZV9sYW5ndWFnZUIYChZfZGVmYXVsdF92'
    'aWRlb19xdWFsaXR5QhcKFV9tYXhfYml0cmF0ZV9jYXBfbWJwc0ISChBfdmlzdWFsX21ldGFkYX'
    'RhQg4KDF9iaW5nZV9ncm91cEIUChJfc3RhcnRfcG9zaXRpb25fbXM=');

@$core.Deprecated('Use playlistPayloadDescriptor instead')
const PlaylistPayload$json = {
  '1': 'PlaylistPayload',
  '2': [
    {'1': 'items', '3': 1, '4': 3, '5': 11, '6': '.playbridge.PlayPayload', '10': 'items'},
    {'1': 'start_index', '3': 2, '4': 1, '5': 5, '10': 'startIndex'},
    {'1': 'visual_metadata', '3': 3, '4': 1, '5': 11, '6': '.playbridge.VisualMetadata', '9': 0, '10': 'visualMetadata', '17': true},
  ],
  '8': [
    {'1': '_visual_metadata'},
  ],
};

/// Descriptor for `PlaylistPayload`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List playlistPayloadDescriptor = $convert.base64Decode(
    'Cg9QbGF5bGlzdFBheWxvYWQSLQoFaXRlbXMYASADKAsyFy5wbGF5YnJpZGdlLlBsYXlQYXlsb2'
    'FkUgVpdGVtcxIfCgtzdGFydF9pbmRleBgCIAEoBVIKc3RhcnRJbmRleBJICg92aXN1YWxfbWV0'
    'YWRhdGEYAyABKAsyGi5wbGF5YnJpZGdlLlZpc3VhbE1ldGFkYXRhSABSDnZpc3VhbE1ldGFkYX'
    'RhiAEBQhIKEF92aXN1YWxfbWV0YWRhdGE=');

@$core.Deprecated('Use queueAddPayloadDescriptor instead')
const QueueAddPayload$json = {
  '1': 'QueueAddPayload',
  '2': [
    {'1': 'item', '3': 1, '4': 1, '5': 11, '6': '.playbridge.PlayPayload', '10': 'item'},
  ],
};

/// Descriptor for `QueueAddPayload`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List queueAddPayloadDescriptor = $convert.base64Decode(
    'Cg9RdWV1ZUFkZFBheWxvYWQSKwoEaXRlbRgBIAEoCzIXLnBsYXlicmlkZ2UuUGxheVBheWxvYW'
    'RSBGl0ZW0=');

@$core.Deprecated('Use playlistJumpPayloadDescriptor instead')
const PlaylistJumpPayload$json = {
  '1': 'PlaylistJumpPayload',
  '2': [
    {'1': 'index', '3': 1, '4': 1, '5': 5, '10': 'index'},
  ],
};

/// Descriptor for `PlaylistJumpPayload`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List playlistJumpPayloadDescriptor = $convert.base64Decode(
    'ChNQbGF5bGlzdEp1bXBQYXlsb2FkEhQKBWluZGV4GAEgASgFUgVpbmRleA==');

@$core.Deprecated('Use browserPayloadDescriptor instead')
const BrowserPayload$json = {
  '1': 'BrowserPayload',
  '2': [
    {'1': 'url', '3': 1, '4': 1, '5': 9, '10': 'url'},
    {'1': 'browser_mode', '3': 2, '4': 1, '5': 9, '9': 0, '10': 'browserMode', '17': true},
    {'1': 'desktop_mode', '3': 3, '4': 1, '5': 8, '9': 1, '10': 'desktopMode', '17': true},
  ],
  '8': [
    {'1': '_browser_mode'},
    {'1': '_desktop_mode'},
  ],
};

/// Descriptor for `BrowserPayload`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List browserPayloadDescriptor = $convert.base64Decode(
    'Cg5Ccm93c2VyUGF5bG9hZBIQCgN1cmwYASABKAlSA3VybBImCgxicm93c2VyX21vZGUYAiABKA'
    'lIAFILYnJvd3Nlck1vZGWIAQESJgoMZGVza3RvcF9tb2RlGAMgASgISAFSC2Rlc2t0b3BNb2Rl'
    'iAEBQg8KDV9icm93c2VyX21vZGVCDwoNX2Rlc2t0b3BfbW9kZQ==');

@$core.Deprecated('Use controlPayloadDescriptor instead')
const ControlPayload$json = {
  '1': 'ControlPayload',
  '2': [
    {'1': 'command', '3': 1, '4': 1, '5': 9, '10': 'command'},
  ],
};

/// Descriptor for `ControlPayload`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List controlPayloadDescriptor = $convert.base64Decode(
    'Cg5Db250cm9sUGF5bG9hZBIYCgdjb21tYW5kGAEgASgJUgdjb21tYW5k');

@$core.Deprecated('Use remotePayloadDescriptor instead')
const RemotePayload$json = {
  '1': 'RemotePayload',
  '2': [
    {'1': 'key', '3': 1, '4': 1, '5': 9, '10': 'key'},
  ],
};

/// Descriptor for `RemotePayload`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List remotePayloadDescriptor = $convert.base64Decode(
    'Cg1SZW1vdGVQYXlsb2FkEhAKA2tleRgBIAEoCVIDa2V5');

@$core.Deprecated('Use mousePayloadDescriptor instead')
const MousePayload$json = {
  '1': 'MousePayload',
  '2': [
    {'1': 'event', '3': 1, '4': 1, '5': 9, '10': 'event'},
    {'1': 'dx', '3': 2, '4': 1, '5': 2, '10': 'dx'},
    {'1': 'dy', '3': 3, '4': 1, '5': 2, '10': 'dy'},
  ],
};

/// Descriptor for `MousePayload`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List mousePayloadDescriptor = $convert.base64Decode(
    'CgxNb3VzZVBheWxvYWQSFAoFZXZlbnQYASABKAlSBWV2ZW50Eg4KAmR4GAIgASgCUgJkeBIOCg'
    'JkeRgDIAEoAlICZHk=');

@$core.Deprecated('Use browserControlPayloadDescriptor instead')
const BrowserControlPayload$json = {
  '1': 'BrowserControlPayload',
  '2': [
    {'1': 'action', '3': 1, '4': 1, '5': 9, '10': 'action'},
  ],
};

/// Descriptor for `BrowserControlPayload`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List browserControlPayloadDescriptor = $convert.base64Decode(
    'ChVCcm93c2VyQ29udHJvbFBheWxvYWQSFgoGYWN0aW9uGAEgASgJUgZhY3Rpb24=');

@$core.Deprecated('Use statusMessageDescriptor instead')
const StatusMessage$json = {
  '1': 'StatusMessage',
  '2': [
    {'1': 'type', '3': 1, '4': 1, '5': 9, '10': 'type'},
    {'1': 'state', '3': 2, '4': 1, '5': 9, '10': 'state'},
    {'1': 'position', '3': 3, '4': 1, '5': 3, '10': 'position'},
    {'1': 'duration', '3': 4, '4': 1, '5': 3, '10': 'duration'},
    {'1': 'title', '3': 5, '4': 1, '5': 9, '9': 0, '10': 'title', '17': true},
  ],
  '8': [
    {'1': '_title'},
  ],
};

/// Descriptor for `StatusMessage`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List statusMessageDescriptor = $convert.base64Decode(
    'Cg1TdGF0dXNNZXNzYWdlEhIKBHR5cGUYASABKAlSBHR5cGUSFAoFc3RhdGUYAiABKAlSBXN0YX'
    'RlEhoKCHBvc2l0aW9uGAMgASgDUghwb3NpdGlvbhIaCghkdXJhdGlvbhgEIAEoA1IIZHVyYXRp'
    'b24SGQoFdGl0bGUYBSABKAlIAFIFdGl0bGWIAQFCCAoGX3RpdGxl');

@$core.Deprecated('Use contextMessageDescriptor instead')
const ContextMessage$json = {
  '1': 'ContextMessage',
  '2': [
    {'1': 'type', '3': 1, '4': 1, '5': 9, '10': 'type'},
    {'1': 'active', '3': 2, '4': 1, '5': 9, '10': 'active'},
  ],
};

/// Descriptor for `ContextMessage`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List contextMessageDescriptor = $convert.base64Decode(
    'Cg5Db250ZXh0TWVzc2FnZRISCgR0eXBlGAEgASgJUgR0eXBlEhYKBmFjdGl2ZRgCIAEoCVIGYW'
    'N0aXZl');

@$core.Deprecated('Use playlistItemInfoDescriptor instead')
const PlaylistItemInfo$json = {
  '1': 'PlaylistItemInfo',
  '2': [
    {'1': 'index', '3': 1, '4': 1, '5': 5, '10': 'index'},
    {'1': 'title', '3': 2, '4': 1, '5': 9, '10': 'title'},
  ],
};

/// Descriptor for `PlaylistItemInfo`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List playlistItemInfoDescriptor = $convert.base64Decode(
    'ChBQbGF5bGlzdEl0ZW1JbmZvEhQKBWluZGV4GAEgASgFUgVpbmRleBIUCgV0aXRsZRgCIAEoCV'
    'IFdGl0bGU=');

@$core.Deprecated('Use playlistStatusMessageDescriptor instead')
const PlaylistStatusMessage$json = {
  '1': 'PlaylistStatusMessage',
  '2': [
    {'1': 'type', '3': 1, '4': 1, '5': 9, '10': 'type'},
    {'1': 'items', '3': 2, '4': 3, '5': 11, '6': '.playbridge.PlaylistItemInfo', '10': 'items'},
    {'1': 'current_index', '3': 3, '4': 1, '5': 5, '10': 'currentIndex'},
    {'1': 'total_count', '3': 4, '4': 1, '5': 5, '10': 'totalCount'},
  ],
};

/// Descriptor for `PlaylistStatusMessage`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List playlistStatusMessageDescriptor = $convert.base64Decode(
    'ChVQbGF5bGlzdFN0YXR1c01lc3NhZ2USEgoEdHlwZRgBIAEoCVIEdHlwZRIyCgVpdGVtcxgCIA'
    'MoCzIcLnBsYXlicmlkZ2UuUGxheWxpc3RJdGVtSW5mb1IFaXRlbXMSIwoNY3VycmVudF9pbmRl'
    'eBgDIAEoBVIMY3VycmVudEluZGV4Eh8KC3RvdGFsX2NvdW50GAQgASgFUgp0b3RhbENvdW50');

@$core.Deprecated('Use authMessageDescriptor instead')
const AuthMessage$json = {
  '1': 'AuthMessage',
  '2': [
    {'1': 'type', '3': 1, '4': 1, '5': 9, '10': 'type'},
    {'1': 'token', '3': 2, '4': 1, '5': 9, '9': 0, '10': 'token', '17': true},
  ],
  '8': [
    {'1': '_token'},
  ],
};

/// Descriptor for `AuthMessage`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List authMessageDescriptor = $convert.base64Decode(
    'CgtBdXRoTWVzc2FnZRISCgR0eXBlGAEgASgJUgR0eXBlEhkKBXRva2VuGAIgASgJSABSBXRva2'
    'VuiAEBQggKBl90b2tlbg==');

@$core.Deprecated('Use authResponseDescriptor instead')
const AuthResponse$json = {
  '1': 'AuthResponse',
  '2': [
    {'1': 'type', '3': 1, '4': 1, '5': 9, '10': 'type'},
    {'1': 'success', '3': 2, '4': 1, '5': 8, '10': 'success'},
    {'1': 'token', '3': 3, '4': 1, '5': 9, '9': 0, '10': 'token', '17': true},
    {'1': 'cert_fingerprint', '3': 4, '4': 1, '5': 9, '9': 1, '10': 'certFingerprint', '17': true},
  ],
  '8': [
    {'1': '_token'},
    {'1': '_cert_fingerprint'},
  ],
};

/// Descriptor for `AuthResponse`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List authResponseDescriptor = $convert.base64Decode(
    'CgxBdXRoUmVzcG9uc2USEgoEdHlwZRgBIAEoCVIEdHlwZRIYCgdzdWNjZXNzGAIgASgIUgdzdW'
    'NjZXNzEhkKBXRva2VuGAMgASgJSABSBXRva2VuiAEBEi4KEGNlcnRfZmluZ2VycHJpbnQYBCAB'
    'KAlIAVIPY2VydEZpbmdlcnByaW50iAEBQggKBl90b2tlbkITChFfY2VydF9maW5nZXJwcmludA'
    '==');

@$core.Deprecated('Use pairingRequestMessageDescriptor instead')
const PairingRequestMessage$json = {
  '1': 'PairingRequestMessage',
  '2': [
    {'1': 'type', '3': 1, '4': 1, '5': 9, '10': 'type'},
    {'1': 'device_name', '3': 2, '4': 1, '5': 9, '10': 'deviceName'},
    {'1': 'device_uuid', '3': 3, '4': 1, '5': 9, '10': 'deviceUUID'},
  ],
};

/// Descriptor for `PairingRequestMessage`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List pairingRequestMessageDescriptor = $convert.base64Decode(
    'ChVQYWlyaW5nUmVxdWVzdE1lc3NhZ2USEgoEdHlwZRgBIAEoCVIEdHlwZRIfCgtkZXZpY2Vfbm'
    'FtZRgCIAEoCVIKZGV2aWNlTmFtZRIfCgtkZXZpY2VfdXVpZBgDIAEoCVIKZGV2aWNlVVVJRA==');

@$core.Deprecated('Use pairingCommitMessageDescriptor instead')
const PairingCommitMessage$json = {
  '1': 'PairingCommitMessage',
  '2': [
    {'1': 'type', '3': 1, '4': 1, '5': 9, '10': 'type'},
    {'1': 'commit', '3': 2, '4': 1, '5': 9, '10': 'commit'},
    {'1': 'device_name', '3': 3, '4': 1, '5': 9, '10': 'deviceName'},
    {'1': 'device_uuid', '3': 4, '4': 1, '5': 9, '10': 'deviceUUID'},
  ],
};

/// Descriptor for `PairingCommitMessage`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List pairingCommitMessageDescriptor = $convert.base64Decode(
    'ChRQYWlyaW5nQ29tbWl0TWVzc2FnZRISCgR0eXBlGAEgASgJUgR0eXBlEhYKBmNvbW1pdBgCIA'
    'EoCVIGY29tbWl0Eh8KC2RldmljZV9uYW1lGAMgASgJUgpkZXZpY2VOYW1lEh8KC2RldmljZV91'
    'dWlkGAQgASgJUgpkZXZpY2VVVUlE');

@$core.Deprecated('Use pairingChallengeMessageDescriptor instead')
const PairingChallengeMessage$json = {
  '1': 'PairingChallengeMessage',
  '2': [
    {'1': 'type', '3': 1, '4': 1, '5': 9, '10': 'type'},
    {'1': 'tv_eph_pub', '3': 2, '4': 1, '5': 9, '10': 'tvEphPub'},
    {'1': 'nonce_t', '3': 3, '4': 1, '5': 9, '10': 'nonceT'},
  ],
};

/// Descriptor for `PairingChallengeMessage`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List pairingChallengeMessageDescriptor = $convert.base64Decode(
    'ChdQYWlyaW5nQ2hhbGxlbmdlTWVzc2FnZRISCgR0eXBlGAEgASgJUgR0eXBlEhwKCnR2X2VwaF'
    '9wdWIYAiABKAlSCHR2RXBoUHViEhcKB25vbmNlX3QYAyABKAlSBm5vbmNlVA==');

@$core.Deprecated('Use pairingRevealMessageDescriptor instead')
const PairingRevealMessage$json = {
  '1': 'PairingRevealMessage',
  '2': [
    {'1': 'type', '3': 1, '4': 1, '5': 9, '10': 'type'},
    {'1': 'sender_eph_pub', '3': 2, '4': 1, '5': 9, '10': 'senderEphPub'},
    {'1': 'nonce_s', '3': 3, '4': 1, '5': 9, '10': 'nonceS'},
  ],
};

/// Descriptor for `PairingRevealMessage`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List pairingRevealMessageDescriptor = $convert.base64Decode(
    'ChRQYWlyaW5nUmV2ZWFsTWVzc2FnZRISCgR0eXBlGAEgASgJUgR0eXBlEiQKDnNlbmRlcl9lcG'
    'hfcHViGAIgASgJUgxzZW5kZXJFcGhQdWISFwoHbm9uY2VfcxgDIAEoCVIGbm9uY2VT');

@$core.Deprecated('Use pairingConfirmationMessageDescriptor instead')
const PairingConfirmationMessage$json = {
  '1': 'PairingConfirmationMessage',
  '2': [
    {'1': 'type', '3': 1, '4': 1, '5': 9, '10': 'type'},
    {'1': 'mac', '3': 2, '4': 1, '5': 9, '10': 'mac'},
  ],
};

/// Descriptor for `PairingConfirmationMessage`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List pairingConfirmationMessageDescriptor = $convert.base64Decode(
    'ChpQYWlyaW5nQ29uZmlybWF0aW9uTWVzc2FnZRISCgR0eXBlGAEgASgJUgR0eXBlEhAKA21hYx'
    'gCIAEoCVIDbWFj');

@$core.Deprecated('Use pairingApprovedMessageDescriptor instead')
const PairingApprovedMessage$json = {
  '1': 'PairingApprovedMessage',
  '2': [
    {'1': 'type', '3': 1, '4': 1, '5': 9, '10': 'type'},
    {'1': 'token', '3': 2, '4': 1, '5': 9, '10': 'token'},
    {'1': 'cert_fingerprint', '3': 3, '4': 1, '5': 9, '9': 0, '10': 'certFingerprint', '17': true},
  ],
  '8': [
    {'1': '_cert_fingerprint'},
  ],
};

/// Descriptor for `PairingApprovedMessage`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List pairingApprovedMessageDescriptor = $convert.base64Decode(
    'ChZQYWlyaW5nQXBwcm92ZWRNZXNzYWdlEhIKBHR5cGUYASABKAlSBHR5cGUSFAoFdG9rZW4YAi'
    'ABKAlSBXRva2VuEi4KEGNlcnRfZmluZ2VycHJpbnQYAyABKAlIAFIPY2VydEZpbmdlcnByaW50'
    'iAEBQhMKEV9jZXJ0X2ZpbmdlcnByaW50');

@$core.Deprecated('Use pairingDeniedMessageDescriptor instead')
const PairingDeniedMessage$json = {
  '1': 'PairingDeniedMessage',
  '2': [
    {'1': 'type', '3': 1, '4': 1, '5': 9, '10': 'type'},
  ],
};

/// Descriptor for `PairingDeniedMessage`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List pairingDeniedMessageDescriptor = $convert.base64Decode(
    'ChRQYWlyaW5nRGVuaWVkTWVzc2FnZRISCgR0eXBlGAEgASgJUgR0eXBl');

@$core.Deprecated('Use pingMessageDescriptor instead')
const PingMessage$json = {
  '1': 'PingMessage',
  '2': [
    {'1': 'type', '3': 1, '4': 1, '5': 9, '10': 'type'},
  ],
};

/// Descriptor for `PingMessage`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List pingMessageDescriptor = $convert.base64Decode(
    'CgtQaW5nTWVzc2FnZRISCgR0eXBlGAEgASgJUgR0eXBl');

@$core.Deprecated('Use pongMessageDescriptor instead')
const PongMessage$json = {
  '1': 'PongMessage',
  '2': [
    {'1': 'type', '3': 1, '4': 1, '5': 9, '10': 'type'},
  ],
};

/// Descriptor for `PongMessage`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List pongMessageDescriptor = $convert.base64Decode(
    'CgtQb25nTWVzc2FnZRISCgR0eXBlGAEgASgJUgR0eXBl');

