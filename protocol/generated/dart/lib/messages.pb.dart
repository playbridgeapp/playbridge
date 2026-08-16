// This is a generated file - do not edit.
//
// Generated from messages.proto.

// @dart = 3.3

// ignore_for_file: annotate_overrides, camel_case_types, comment_references
// ignore_for_file: constant_identifier_names
// ignore_for_file: curly_braces_in_flow_control_structures
// ignore_for_file: deprecated_member_use_from_same_package, library_prefixes
// ignore_for_file: non_constant_identifier_names

import 'dart:core' as $core;

import 'package:fixnum/fixnum.dart' as $fixnum;
import 'package:protobuf/protobuf.dart' as $pb;

export 'package:protobuf/protobuf.dart' show GeneratedMessageGenericExtensions;

export 'messages.pbenum.dart';

/// Top-level WebSocket frame. Consumers switch on `type`, then `action`.
class MessageEnvelope extends $pb.GeneratedMessage {
  factory MessageEnvelope({
    $core.String? type,
    $core.String? action,
    $core.String? state,
    $fixnum.Int64? position,
    $fixnum.Int64? duration,
    $core.String? title,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (action != null) result.action = action;
    if (state != null) result.state = state;
    if (position != null) result.position = position;
    if (duration != null) result.duration = duration;
    if (title != null) result.title = title;
    return result;
  }

  MessageEnvelope._();

  factory MessageEnvelope.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory MessageEnvelope.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'MessageEnvelope', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'type')
    ..aOS(2, _omitFieldNames ? '' : 'action')
    ..aOS(3, _omitFieldNames ? '' : 'state')
    ..aInt64(4, _omitFieldNames ? '' : 'position')
    ..aInt64(5, _omitFieldNames ? '' : 'duration')
    ..aOS(6, _omitFieldNames ? '' : 'title')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  MessageEnvelope clone() => MessageEnvelope()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  MessageEnvelope copyWith(void Function(MessageEnvelope) updates) => super.copyWith((message) => updates(message as MessageEnvelope)) as MessageEnvelope;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static MessageEnvelope create() => MessageEnvelope._();
  @$core.override
  MessageEnvelope createEmptyInstance() => create();
  static $pb.PbList<MessageEnvelope> createRepeated() => $pb.PbList<MessageEnvelope>();
  @$core.pragma('dart2js:noInline')
  static MessageEnvelope getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<MessageEnvelope>(create);
  static MessageEnvelope? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get type => $_getSZ(0);
  @$pb.TagNumber(1)
  set type($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasType() => $_has(0);
  @$pb.TagNumber(1)
  void clearType() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get action => $_getSZ(1);
  @$pb.TagNumber(2)
  set action($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasAction() => $_has(1);
  @$pb.TagNumber(2)
  void clearAction() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.String get state => $_getSZ(2);
  @$pb.TagNumber(3)
  set state($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasState() => $_has(2);
  @$pb.TagNumber(3)
  void clearState() => $_clearField(3);

  @$pb.TagNumber(4)
  $fixnum.Int64 get position => $_getI64(3);
  @$pb.TagNumber(4)
  set position($fixnum.Int64 value) => $_setInt64(3, value);
  @$pb.TagNumber(4)
  $core.bool hasPosition() => $_has(3);
  @$pb.TagNumber(4)
  void clearPosition() => $_clearField(4);

  @$pb.TagNumber(5)
  $fixnum.Int64 get duration => $_getI64(4);
  @$pb.TagNumber(5)
  set duration($fixnum.Int64 value) => $_setInt64(4, value);
  @$pb.TagNumber(5)
  $core.bool hasDuration() => $_has(4);
  @$pb.TagNumber(5)
  void clearDuration() => $_clearField(5);

  @$pb.TagNumber(6)
  $core.String get title => $_getSZ(5);
  @$pb.TagNumber(6)
  set title($core.String value) => $_setString(5, value);
  @$pb.TagNumber(6)
  $core.bool hasTitle() => $_has(5);
  @$pb.TagNumber(6)
  void clearTitle() => $_clearField(6);
}

class SeriesEpisodeRef extends $pb.GeneratedMessage {
  factory SeriesEpisodeRef({
    $core.int? season,
    $core.int? episode,
    $core.String? title,
  }) {
    final result = create();
    if (season != null) result.season = season;
    if (episode != null) result.episode = episode;
    if (title != null) result.title = title;
    return result;
  }

  SeriesEpisodeRef._();

  factory SeriesEpisodeRef.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory SeriesEpisodeRef.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'SeriesEpisodeRef', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..a<$core.int>(1, _omitFieldNames ? '' : 'season', $pb.PbFieldType.O3)
    ..a<$core.int>(2, _omitFieldNames ? '' : 'episode', $pb.PbFieldType.O3)
    ..aOS(3, _omitFieldNames ? '' : 'title')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  SeriesEpisodeRef clone() => SeriesEpisodeRef()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  SeriesEpisodeRef copyWith(void Function(SeriesEpisodeRef) updates) => super.copyWith((message) => updates(message as SeriesEpisodeRef)) as SeriesEpisodeRef;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static SeriesEpisodeRef create() => SeriesEpisodeRef._();
  @$core.override
  SeriesEpisodeRef createEmptyInstance() => create();
  static $pb.PbList<SeriesEpisodeRef> createRepeated() => $pb.PbList<SeriesEpisodeRef>();
  @$core.pragma('dart2js:noInline')
  static SeriesEpisodeRef getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<SeriesEpisodeRef>(create);
  static SeriesEpisodeRef? _defaultInstance;

  @$pb.TagNumber(1)
  $core.int get season => $_getIZ(0);
  @$pb.TagNumber(1)
  set season($core.int value) => $_setSignedInt32(0, value);
  @$pb.TagNumber(1)
  $core.bool hasSeason() => $_has(0);
  @$pb.TagNumber(1)
  void clearSeason() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.int get episode => $_getIZ(1);
  @$pb.TagNumber(2)
  set episode($core.int value) => $_setSignedInt32(1, value);
  @$pb.TagNumber(2)
  $core.bool hasEpisode() => $_has(1);
  @$pb.TagNumber(2)
  void clearEpisode() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.String get title => $_getSZ(2);
  @$pb.TagNumber(3)
  set title($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasTitle() => $_has(2);
  @$pb.TagNumber(3)
  void clearTitle() => $_clearField(3);
}

class VisualMetadata extends $pb.GeneratedMessage {
  factory VisualMetadata({
    $core.String? title,
    $core.String? year,
    $core.String? rating,
    $core.String? runtime,
    $core.String? overview,
    $core.Iterable<$core.String>? genres,
    $core.Iterable<$core.String>? cast,
    $core.Iterable<$core.String>? director,
    $core.String? backdropUrl,
    $core.String? posterUrl,
    $core.String? logoUrl,
    $core.int? season,
    $core.int? episode,
    $core.String? episodeTitle,
    $core.String? imdbId,
    $core.String? tmdbId,
  }) {
    final result = create();
    if (title != null) result.title = title;
    if (year != null) result.year = year;
    if (rating != null) result.rating = rating;
    if (runtime != null) result.runtime = runtime;
    if (overview != null) result.overview = overview;
    if (genres != null) result.genres.addAll(genres);
    if (cast != null) result.cast.addAll(cast);
    if (director != null) result.director.addAll(director);
    if (backdropUrl != null) result.backdropUrl = backdropUrl;
    if (posterUrl != null) result.posterUrl = posterUrl;
    if (logoUrl != null) result.logoUrl = logoUrl;
    if (season != null) result.season = season;
    if (episode != null) result.episode = episode;
    if (episodeTitle != null) result.episodeTitle = episodeTitle;
    if (imdbId != null) result.imdbId = imdbId;
    if (tmdbId != null) result.tmdbId = tmdbId;
    return result;
  }

  VisualMetadata._();

  factory VisualMetadata.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory VisualMetadata.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'VisualMetadata', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'title')
    ..aOS(2, _omitFieldNames ? '' : 'year')
    ..aOS(3, _omitFieldNames ? '' : 'rating')
    ..aOS(4, _omitFieldNames ? '' : 'runtime')
    ..aOS(5, _omitFieldNames ? '' : 'overview')
    ..pPS(6, _omitFieldNames ? '' : 'genres')
    ..pPS(7, _omitFieldNames ? '' : 'cast')
    ..pPS(8, _omitFieldNames ? '' : 'director')
    ..aOS(9, _omitFieldNames ? '' : 'backdropUrl')
    ..aOS(10, _omitFieldNames ? '' : 'posterUrl')
    ..aOS(11, _omitFieldNames ? '' : 'logoUrl')
    ..a<$core.int>(12, _omitFieldNames ? '' : 'season', $pb.PbFieldType.O3)
    ..a<$core.int>(13, _omitFieldNames ? '' : 'episode', $pb.PbFieldType.O3)
    ..aOS(14, _omitFieldNames ? '' : 'episodeTitle')
    ..aOS(15, _omitFieldNames ? '' : 'imdbId')
    ..aOS(16, _omitFieldNames ? '' : 'tmdbId')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  VisualMetadata clone() => VisualMetadata()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  VisualMetadata copyWith(void Function(VisualMetadata) updates) => super.copyWith((message) => updates(message as VisualMetadata)) as VisualMetadata;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static VisualMetadata create() => VisualMetadata._();
  @$core.override
  VisualMetadata createEmptyInstance() => create();
  static $pb.PbList<VisualMetadata> createRepeated() => $pb.PbList<VisualMetadata>();
  @$core.pragma('dart2js:noInline')
  static VisualMetadata getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<VisualMetadata>(create);
  static VisualMetadata? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get title => $_getSZ(0);
  @$pb.TagNumber(1)
  set title($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasTitle() => $_has(0);
  @$pb.TagNumber(1)
  void clearTitle() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get year => $_getSZ(1);
  @$pb.TagNumber(2)
  set year($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasYear() => $_has(1);
  @$pb.TagNumber(2)
  void clearYear() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.String get rating => $_getSZ(2);
  @$pb.TagNumber(3)
  set rating($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasRating() => $_has(2);
  @$pb.TagNumber(3)
  void clearRating() => $_clearField(3);

  @$pb.TagNumber(4)
  $core.String get runtime => $_getSZ(3);
  @$pb.TagNumber(4)
  set runtime($core.String value) => $_setString(3, value);
  @$pb.TagNumber(4)
  $core.bool hasRuntime() => $_has(3);
  @$pb.TagNumber(4)
  void clearRuntime() => $_clearField(4);

  @$pb.TagNumber(5)
  $core.String get overview => $_getSZ(4);
  @$pb.TagNumber(5)
  set overview($core.String value) => $_setString(4, value);
  @$pb.TagNumber(5)
  $core.bool hasOverview() => $_has(4);
  @$pb.TagNumber(5)
  void clearOverview() => $_clearField(5);

  @$pb.TagNumber(6)
  $pb.PbList<$core.String> get genres => $_getList(5);

  @$pb.TagNumber(7)
  $pb.PbList<$core.String> get cast => $_getList(6);

  @$pb.TagNumber(8)
  $pb.PbList<$core.String> get director => $_getList(7);

  @$pb.TagNumber(9)
  $core.String get backdropUrl => $_getSZ(8);
  @$pb.TagNumber(9)
  set backdropUrl($core.String value) => $_setString(8, value);
  @$pb.TagNumber(9)
  $core.bool hasBackdropUrl() => $_has(8);
  @$pb.TagNumber(9)
  void clearBackdropUrl() => $_clearField(9);

  @$pb.TagNumber(10)
  $core.String get posterUrl => $_getSZ(9);
  @$pb.TagNumber(10)
  set posterUrl($core.String value) => $_setString(9, value);
  @$pb.TagNumber(10)
  $core.bool hasPosterUrl() => $_has(9);
  @$pb.TagNumber(10)
  void clearPosterUrl() => $_clearField(10);

  @$pb.TagNumber(11)
  $core.String get logoUrl => $_getSZ(10);
  @$pb.TagNumber(11)
  set logoUrl($core.String value) => $_setString(10, value);
  @$pb.TagNumber(11)
  $core.bool hasLogoUrl() => $_has(10);
  @$pb.TagNumber(11)
  void clearLogoUrl() => $_clearField(11);

  @$pb.TagNumber(12)
  $core.int get season => $_getIZ(11);
  @$pb.TagNumber(12)
  set season($core.int value) => $_setSignedInt32(11, value);
  @$pb.TagNumber(12)
  $core.bool hasSeason() => $_has(11);
  @$pb.TagNumber(12)
  void clearSeason() => $_clearField(12);

  @$pb.TagNumber(13)
  $core.int get episode => $_getIZ(12);
  @$pb.TagNumber(13)
  set episode($core.int value) => $_setSignedInt32(12, value);
  @$pb.TagNumber(13)
  $core.bool hasEpisode() => $_has(12);
  @$pb.TagNumber(13)
  void clearEpisode() => $_clearField(13);

  @$pb.TagNumber(14)
  $core.String get episodeTitle => $_getSZ(13);
  @$pb.TagNumber(14)
  set episodeTitle($core.String value) => $_setString(13, value);
  @$pb.TagNumber(14)
  $core.bool hasEpisodeTitle() => $_has(13);
  @$pb.TagNumber(14)
  void clearEpisodeTitle() => $_clearField(14);

  @$pb.TagNumber(15)
  $core.String get imdbId => $_getSZ(14);
  @$pb.TagNumber(15)
  set imdbId($core.String value) => $_setString(14, value);
  @$pb.TagNumber(15)
  $core.bool hasImdbId() => $_has(14);
  @$pb.TagNumber(15)
  void clearImdbId() => $_clearField(15);

  @$pb.TagNumber(16)
  $core.String get tmdbId => $_getSZ(15);
  @$pb.TagNumber(16)
  set tmdbId($core.String value) => $_setString(15, value);
  @$pb.TagNumber(16)
  $core.bool hasTmdbId() => $_has(15);
  @$pb.TagNumber(16)
  void clearTmdbId() => $_clearField(16);
}

/// A sidecar subtitle is an independent network resource. Its headers are scoped
/// to this URL's origin and must never be inherited from an unrelated media URL.
class SubtitleResource extends $pb.GeneratedMessage {
  factory SubtitleResource({
    $core.String? url,
    $core.Iterable<$core.MapEntry<$core.String, $core.String>>? headers,
    $core.String? label,
    $core.String? language,
  }) {
    final result = create();
    if (url != null) result.url = url;
    if (headers != null) result.headers.addEntries(headers);
    if (label != null) result.label = label;
    if (language != null) result.language = language;
    return result;
  }

  SubtitleResource._();

  factory SubtitleResource.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory SubtitleResource.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'SubtitleResource', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'url')
    ..m<$core.String, $core.String>(2, _omitFieldNames ? '' : 'headers', entryClassName: 'SubtitleResource.HeadersEntry', keyFieldType: $pb.PbFieldType.OS, valueFieldType: $pb.PbFieldType.OS, packageName: const $pb.PackageName('playbridge'))
    ..aOS(3, _omitFieldNames ? '' : 'label')
    ..aOS(4, _omitFieldNames ? '' : 'language')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  SubtitleResource clone() => SubtitleResource()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  SubtitleResource copyWith(void Function(SubtitleResource) updates) => super.copyWith((message) => updates(message as SubtitleResource)) as SubtitleResource;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static SubtitleResource create() => SubtitleResource._();
  @$core.override
  SubtitleResource createEmptyInstance() => create();
  static $pb.PbList<SubtitleResource> createRepeated() => $pb.PbList<SubtitleResource>();
  @$core.pragma('dart2js:noInline')
  static SubtitleResource getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<SubtitleResource>(create);
  static SubtitleResource? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get url => $_getSZ(0);
  @$pb.TagNumber(1)
  set url($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasUrl() => $_has(0);
  @$pb.TagNumber(1)
  void clearUrl() => $_clearField(1);

  @$pb.TagNumber(2)
  $pb.PbMap<$core.String, $core.String> get headers => $_getMap(1);

  @$pb.TagNumber(3)
  $core.String get label => $_getSZ(2);
  @$pb.TagNumber(3)
  set label($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasLabel() => $_has(2);
  @$pb.TagNumber(3)
  void clearLabel() => $_clearField(3);

  @$pb.TagNumber(4)
  $core.String get language => $_getSZ(3);
  @$pb.TagNumber(4)
  set language($core.String value) => $_setString(3, value);
  @$pb.TagNumber(4)
  $core.bool hasLanguage() => $_has(3);
  @$pb.TagNumber(4)
  void clearLanguage() => $_clearField(4);
}

class PlayPayload extends $pb.GeneratedMessage {
  factory PlayPayload({
    $core.String? url,
    $core.String? title,
    $core.Iterable<$core.MapEntry<$core.String, $core.String>>? headers,
    $core.String? contentType,
    $core.Iterable<$core.String>? subtitles,
    $core.String? detectedBy,
    $core.String? playerMode,
    $core.String? preferredAudioLanguage,
    $core.String? preferredSubtitleLanguage,
    $core.String? defaultVideoQuality,
    $core.double? maxBitrateCapMbps,
    VisualMetadata? visualMetadata,
    $core.String? bingeGroup,
    $fixnum.Int64? startPositionMs,
    $core.bool? allowPrivateNetwork,
    $core.Iterable<SubtitleResource>? subtitleResources,
  }) {
    final result = create();
    if (url != null) result.url = url;
    if (title != null) result.title = title;
    if (headers != null) result.headers.addEntries(headers);
    if (contentType != null) result.contentType = contentType;
    if (subtitles != null) result.subtitles.addAll(subtitles);
    if (detectedBy != null) result.detectedBy = detectedBy;
    if (playerMode != null) result.playerMode = playerMode;
    if (preferredAudioLanguage != null) result.preferredAudioLanguage = preferredAudioLanguage;
    if (preferredSubtitleLanguage != null) result.preferredSubtitleLanguage = preferredSubtitleLanguage;
    if (defaultVideoQuality != null) result.defaultVideoQuality = defaultVideoQuality;
    if (maxBitrateCapMbps != null) result.maxBitrateCapMbps = maxBitrateCapMbps;
    if (visualMetadata != null) result.visualMetadata = visualMetadata;
    if (bingeGroup != null) result.bingeGroup = bingeGroup;
    if (startPositionMs != null) result.startPositionMs = startPositionMs;
    if (allowPrivateNetwork != null) result.allowPrivateNetwork = allowPrivateNetwork;
    if (subtitleResources != null) result.subtitleResources.addAll(subtitleResources);
    return result;
  }

  PlayPayload._();

  factory PlayPayload.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory PlayPayload.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'PlayPayload', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'url')
    ..aOS(2, _omitFieldNames ? '' : 'title')
    ..m<$core.String, $core.String>(3, _omitFieldNames ? '' : 'headers', entryClassName: 'PlayPayload.HeadersEntry', keyFieldType: $pb.PbFieldType.OS, valueFieldType: $pb.PbFieldType.OS, packageName: const $pb.PackageName('playbridge'))
    ..aOS(4, _omitFieldNames ? '' : 'contentType')
    ..pPS(5, _omitFieldNames ? '' : 'subtitles')
    ..aOS(6, _omitFieldNames ? '' : 'detectedBy')
    ..aOS(7, _omitFieldNames ? '' : 'playerMode')
    ..aOS(8, _omitFieldNames ? '' : 'preferredAudioLanguage')
    ..aOS(9, _omitFieldNames ? '' : 'preferredSubtitleLanguage')
    ..aOS(10, _omitFieldNames ? '' : 'defaultVideoQuality')
    ..a<$core.double>(11, _omitFieldNames ? '' : 'maxBitrateCapMbps', $pb.PbFieldType.OD)
    ..aOM<VisualMetadata>(12, _omitFieldNames ? '' : 'visualMetadata', subBuilder: VisualMetadata.create)
    ..aOS(13, _omitFieldNames ? '' : 'bingeGroup')
    ..aInt64(14, _omitFieldNames ? '' : 'startPositionMs')
    ..aOB(15, _omitFieldNames ? '' : 'allowPrivateNetwork')
    ..pc<SubtitleResource>(16, _omitFieldNames ? '' : 'subtitleResources', $pb.PbFieldType.PM, subBuilder: SubtitleResource.create)
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PlayPayload clone() => PlayPayload()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PlayPayload copyWith(void Function(PlayPayload) updates) => super.copyWith((message) => updates(message as PlayPayload)) as PlayPayload;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static PlayPayload create() => PlayPayload._();
  @$core.override
  PlayPayload createEmptyInstance() => create();
  static $pb.PbList<PlayPayload> createRepeated() => $pb.PbList<PlayPayload>();
  @$core.pragma('dart2js:noInline')
  static PlayPayload getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<PlayPayload>(create);
  static PlayPayload? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get url => $_getSZ(0);
  @$pb.TagNumber(1)
  set url($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasUrl() => $_has(0);
  @$pb.TagNumber(1)
  void clearUrl() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get title => $_getSZ(1);
  @$pb.TagNumber(2)
  set title($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasTitle() => $_has(1);
  @$pb.TagNumber(2)
  void clearTitle() => $_clearField(2);

  @$pb.TagNumber(3)
  $pb.PbMap<$core.String, $core.String> get headers => $_getMap(2);

  @$pb.TagNumber(4)
  $core.String get contentType => $_getSZ(3);
  @$pb.TagNumber(4)
  set contentType($core.String value) => $_setString(3, value);
  @$pb.TagNumber(4)
  $core.bool hasContentType() => $_has(3);
  @$pb.TagNumber(4)
  void clearContentType() => $_clearField(4);

  @$pb.TagNumber(5)
  $pb.PbList<$core.String> get subtitles => $_getList(4);

  @$pb.TagNumber(6)
  $core.String get detectedBy => $_getSZ(5);
  @$pb.TagNumber(6)
  set detectedBy($core.String value) => $_setString(5, value);
  @$pb.TagNumber(6)
  $core.bool hasDetectedBy() => $_has(5);
  @$pb.TagNumber(6)
  void clearDetectedBy() => $_clearField(6);

  @$pb.TagNumber(7)
  $core.String get playerMode => $_getSZ(6);
  @$pb.TagNumber(7)
  set playerMode($core.String value) => $_setString(6, value);
  @$pb.TagNumber(7)
  $core.bool hasPlayerMode() => $_has(6);
  @$pb.TagNumber(7)
  void clearPlayerMode() => $_clearField(7);

  @$pb.TagNumber(8)
  $core.String get preferredAudioLanguage => $_getSZ(7);
  @$pb.TagNumber(8)
  set preferredAudioLanguage($core.String value) => $_setString(7, value);
  @$pb.TagNumber(8)
  $core.bool hasPreferredAudioLanguage() => $_has(7);
  @$pb.TagNumber(8)
  void clearPreferredAudioLanguage() => $_clearField(8);

  @$pb.TagNumber(9)
  $core.String get preferredSubtitleLanguage => $_getSZ(8);
  @$pb.TagNumber(9)
  set preferredSubtitleLanguage($core.String value) => $_setString(8, value);
  @$pb.TagNumber(9)
  $core.bool hasPreferredSubtitleLanguage() => $_has(8);
  @$pb.TagNumber(9)
  void clearPreferredSubtitleLanguage() => $_clearField(9);

  @$pb.TagNumber(10)
  $core.String get defaultVideoQuality => $_getSZ(9);
  @$pb.TagNumber(10)
  set defaultVideoQuality($core.String value) => $_setString(9, value);
  @$pb.TagNumber(10)
  $core.bool hasDefaultVideoQuality() => $_has(9);
  @$pb.TagNumber(10)
  void clearDefaultVideoQuality() => $_clearField(10);

  @$pb.TagNumber(11)
  $core.double get maxBitrateCapMbps => $_getN(10);
  @$pb.TagNumber(11)
  set maxBitrateCapMbps($core.double value) => $_setDouble(10, value);
  @$pb.TagNumber(11)
  $core.bool hasMaxBitrateCapMbps() => $_has(10);
  @$pb.TagNumber(11)
  void clearMaxBitrateCapMbps() => $_clearField(11);

  @$pb.TagNumber(12)
  VisualMetadata get visualMetadata => $_getN(11);
  @$pb.TagNumber(12)
  set visualMetadata(VisualMetadata value) => $_setField(12, value);
  @$pb.TagNumber(12)
  $core.bool hasVisualMetadata() => $_has(11);
  @$pb.TagNumber(12)
  void clearVisualMetadata() => $_clearField(12);
  @$pb.TagNumber(12)
  VisualMetadata ensureVisualMetadata() => $_ensure(11);

  /// Stremio bingeGroup of the chosen stream. Echoed back by the TV in playlist_status so the
  /// phone can (a) keep the same release across episodes and (b) recognise its own lazy-queued
  /// series to resume queueing after an app restart (no phone-side persistence).
  @$pb.TagNumber(13)
  $core.String get bingeGroup => $_getSZ(12);
  @$pb.TagNumber(13)
  set bingeGroup($core.String value) => $_setString(12, value);
  @$pb.TagNumber(13)
  $core.bool hasBingeGroup() => $_has(12);
  @$pb.TagNumber(13)
  void clearBingeGroup() => $_clearField(13);

  /// Resume point: start playback at this position (ms). The phone seeds it from its
  /// content-keyed resume store; the receiver maps it onto the player's start position.
  @$pb.TagNumber(14)
  $fixnum.Int64 get startPositionMs => $_getI64(13);
  @$pb.TagNumber(14)
  set startPositionMs($fixnum.Int64 value) => $_setInt64(13, value);
  @$pb.TagNumber(14)
  $core.bool hasStartPositionMs() => $_has(13);
  @$pb.TagNumber(14)
  void clearStartPositionMs() => $_clearField(14);

  /// Sender authorization for page-initiated media to reach private/LAN destinations.
  /// Receivers must still validate every resolved address, redirect, and derived resource.
  @$pb.TagNumber(15)
  $core.bool get allowPrivateNetwork => $_getBF(14);
  @$pb.TagNumber(15)
  set allowPrivateNetwork($core.bool value) => $_setBool(14, value);
  @$pb.TagNumber(15)
  $core.bool hasAllowPrivateNetwork() => $_has(14);
  @$pb.TagNumber(15)
  void clearAllowPrivateNetwork() => $_clearField(15);

  /// Additive replacement for credentialed sidecars. Legacy `subtitles` remains
  /// supported for senders that only provide URLs.
  @$pb.TagNumber(16)
  $pb.PbList<SubtitleResource> get subtitleResources => $_getList(15);
}

class PlaylistPayload extends $pb.GeneratedMessage {
  factory PlaylistPayload({
    $core.Iterable<PlayPayload>? items,
    $core.int? startIndex,
    VisualMetadata? visualMetadata,
  }) {
    final result = create();
    if (items != null) result.items.addAll(items);
    if (startIndex != null) result.startIndex = startIndex;
    if (visualMetadata != null) result.visualMetadata = visualMetadata;
    return result;
  }

  PlaylistPayload._();

  factory PlaylistPayload.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory PlaylistPayload.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'PlaylistPayload', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..pc<PlayPayload>(1, _omitFieldNames ? '' : 'items', $pb.PbFieldType.PM, subBuilder: PlayPayload.create)
    ..a<$core.int>(2, _omitFieldNames ? '' : 'startIndex', $pb.PbFieldType.O3)
    ..aOM<VisualMetadata>(3, _omitFieldNames ? '' : 'visualMetadata', subBuilder: VisualMetadata.create)
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PlaylistPayload clone() => PlaylistPayload()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PlaylistPayload copyWith(void Function(PlaylistPayload) updates) => super.copyWith((message) => updates(message as PlaylistPayload)) as PlaylistPayload;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static PlaylistPayload create() => PlaylistPayload._();
  @$core.override
  PlaylistPayload createEmptyInstance() => create();
  static $pb.PbList<PlaylistPayload> createRepeated() => $pb.PbList<PlaylistPayload>();
  @$core.pragma('dart2js:noInline')
  static PlaylistPayload getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<PlaylistPayload>(create);
  static PlaylistPayload? _defaultInstance;

  @$pb.TagNumber(1)
  $pb.PbList<PlayPayload> get items => $_getList(0);

  @$pb.TagNumber(2)
  $core.int get startIndex => $_getIZ(1);
  @$pb.TagNumber(2)
  set startIndex($core.int value) => $_setSignedInt32(1, value);
  @$pb.TagNumber(2)
  $core.bool hasStartIndex() => $_has(1);
  @$pb.TagNumber(2)
  void clearStartIndex() => $_clearField(2);

  @$pb.TagNumber(3)
  VisualMetadata get visualMetadata => $_getN(2);
  @$pb.TagNumber(3)
  set visualMetadata(VisualMetadata value) => $_setField(3, value);
  @$pb.TagNumber(3)
  $core.bool hasVisualMetadata() => $_has(2);
  @$pb.TagNumber(3)
  void clearVisualMetadata() => $_clearField(3);
  @$pb.TagNumber(3)
  VisualMetadata ensureVisualMetadata() => $_ensure(2);
}

class QueueAddPayload extends $pb.GeneratedMessage {
  factory QueueAddPayload({
    PlayPayload? item,
  }) {
    final result = create();
    if (item != null) result.item = item;
    return result;
  }

  QueueAddPayload._();

  factory QueueAddPayload.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory QueueAddPayload.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'QueueAddPayload', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOM<PlayPayload>(1, _omitFieldNames ? '' : 'item', subBuilder: PlayPayload.create)
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  QueueAddPayload clone() => QueueAddPayload()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  QueueAddPayload copyWith(void Function(QueueAddPayload) updates) => super.copyWith((message) => updates(message as QueueAddPayload)) as QueueAddPayload;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static QueueAddPayload create() => QueueAddPayload._();
  @$core.override
  QueueAddPayload createEmptyInstance() => create();
  static $pb.PbList<QueueAddPayload> createRepeated() => $pb.PbList<QueueAddPayload>();
  @$core.pragma('dart2js:noInline')
  static QueueAddPayload getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<QueueAddPayload>(create);
  static QueueAddPayload? _defaultInstance;

  @$pb.TagNumber(1)
  PlayPayload get item => $_getN(0);
  @$pb.TagNumber(1)
  set item(PlayPayload value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasItem() => $_has(0);
  @$pb.TagNumber(1)
  void clearItem() => $_clearField(1);
  @$pb.TagNumber(1)
  PlayPayload ensureItem() => $_ensure(0);
}

class PlaylistJumpPayload extends $pb.GeneratedMessage {
  factory PlaylistJumpPayload({
    $core.int? index,
  }) {
    final result = create();
    if (index != null) result.index = index;
    return result;
  }

  PlaylistJumpPayload._();

  factory PlaylistJumpPayload.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory PlaylistJumpPayload.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'PlaylistJumpPayload', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..a<$core.int>(1, _omitFieldNames ? '' : 'index', $pb.PbFieldType.O3)
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PlaylistJumpPayload clone() => PlaylistJumpPayload()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PlaylistJumpPayload copyWith(void Function(PlaylistJumpPayload) updates) => super.copyWith((message) => updates(message as PlaylistJumpPayload)) as PlaylistJumpPayload;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static PlaylistJumpPayload create() => PlaylistJumpPayload._();
  @$core.override
  PlaylistJumpPayload createEmptyInstance() => create();
  static $pb.PbList<PlaylistJumpPayload> createRepeated() => $pb.PbList<PlaylistJumpPayload>();
  @$core.pragma('dart2js:noInline')
  static PlaylistJumpPayload getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<PlaylistJumpPayload>(create);
  static PlaylistJumpPayload? _defaultInstance;

  @$pb.TagNumber(1)
  $core.int get index => $_getIZ(0);
  @$pb.TagNumber(1)
  set index($core.int value) => $_setSignedInt32(0, value);
  @$pb.TagNumber(1)
  $core.bool hasIndex() => $_has(0);
  @$pb.TagNumber(1)
  void clearIndex() => $_clearField(1);
}

class BrowserPayload extends $pb.GeneratedMessage {
  factory BrowserPayload({
    $core.String? url,
    $core.String? browserMode,
    $core.bool? desktopMode,
  }) {
    final result = create();
    if (url != null) result.url = url;
    if (browserMode != null) result.browserMode = browserMode;
    if (desktopMode != null) result.desktopMode = desktopMode;
    return result;
  }

  BrowserPayload._();

  factory BrowserPayload.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory BrowserPayload.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'BrowserPayload', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'url')
    ..aOS(2, _omitFieldNames ? '' : 'browserMode')
    ..aOB(3, _omitFieldNames ? '' : 'desktopMode')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  BrowserPayload clone() => BrowserPayload()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  BrowserPayload copyWith(void Function(BrowserPayload) updates) => super.copyWith((message) => updates(message as BrowserPayload)) as BrowserPayload;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static BrowserPayload create() => BrowserPayload._();
  @$core.override
  BrowserPayload createEmptyInstance() => create();
  static $pb.PbList<BrowserPayload> createRepeated() => $pb.PbList<BrowserPayload>();
  @$core.pragma('dart2js:noInline')
  static BrowserPayload getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<BrowserPayload>(create);
  static BrowserPayload? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get url => $_getSZ(0);
  @$pb.TagNumber(1)
  set url($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasUrl() => $_has(0);
  @$pb.TagNumber(1)
  void clearUrl() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get browserMode => $_getSZ(1);
  @$pb.TagNumber(2)
  set browserMode($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasBrowserMode() => $_has(1);
  @$pb.TagNumber(2)
  void clearBrowserMode() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.bool get desktopMode => $_getBF(2);
  @$pb.TagNumber(3)
  set desktopMode($core.bool value) => $_setBool(2, value);
  @$pb.TagNumber(3)
  $core.bool hasDesktopMode() => $_has(2);
  @$pb.TagNumber(3)
  void clearDesktopMode() => $_clearField(3);
}

class ControlPayload extends $pb.GeneratedMessage {
  factory ControlPayload({
    $core.String? command,
  }) {
    final result = create();
    if (command != null) result.command = command;
    return result;
  }

  ControlPayload._();

  factory ControlPayload.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory ControlPayload.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'ControlPayload', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'command')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ControlPayload clone() => ControlPayload()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ControlPayload copyWith(void Function(ControlPayload) updates) => super.copyWith((message) => updates(message as ControlPayload)) as ControlPayload;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static ControlPayload create() => ControlPayload._();
  @$core.override
  ControlPayload createEmptyInstance() => create();
  static $pb.PbList<ControlPayload> createRepeated() => $pb.PbList<ControlPayload>();
  @$core.pragma('dart2js:noInline')
  static ControlPayload getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<ControlPayload>(create);
  static ControlPayload? _defaultInstance;

  /// pause | play | seek | stop
  @$pb.TagNumber(1)
  $core.String get command => $_getSZ(0);
  @$pb.TagNumber(1)
  set command($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasCommand() => $_has(0);
  @$pb.TagNumber(1)
  void clearCommand() => $_clearField(1);
}

class RemotePayload extends $pb.GeneratedMessage {
  factory RemotePayload({
    $core.String? key,
  }) {
    final result = create();
    if (key != null) result.key = key;
    return result;
  }

  RemotePayload._();

  factory RemotePayload.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory RemotePayload.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'RemotePayload', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'key')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  RemotePayload clone() => RemotePayload()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  RemotePayload copyWith(void Function(RemotePayload) updates) => super.copyWith((message) => updates(message as RemotePayload)) as RemotePayload;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static RemotePayload create() => RemotePayload._();
  @$core.override
  RemotePayload createEmptyInstance() => create();
  static $pb.PbList<RemotePayload> createRepeated() => $pb.PbList<RemotePayload>();
  @$core.pragma('dart2js:noInline')
  static RemotePayload getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<RemotePayload>(create);
  static RemotePayload? _defaultInstance;

  /// dpad_up | dpad_down | dpad_left | dpad_right | dpad_center | back
  @$pb.TagNumber(1)
  $core.String get key => $_getSZ(0);
  @$pb.TagNumber(1)
  set key($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasKey() => $_has(0);
  @$pb.TagNumber(1)
  void clearKey() => $_clearField(1);
}

class MousePayload extends $pb.GeneratedMessage {
  factory MousePayload({
    $core.String? event,
    $core.double? dx,
    $core.double? dy,
  }) {
    final result = create();
    if (event != null) result.event = event;
    if (dx != null) result.dx = dx;
    if (dy != null) result.dy = dy;
    return result;
  }

  MousePayload._();

  factory MousePayload.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory MousePayload.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'MousePayload', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'event')
    ..a<$core.double>(2, _omitFieldNames ? '' : 'dx', $pb.PbFieldType.OF)
    ..a<$core.double>(3, _omitFieldNames ? '' : 'dy', $pb.PbFieldType.OF)
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  MousePayload clone() => MousePayload()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  MousePayload copyWith(void Function(MousePayload) updates) => super.copyWith((message) => updates(message as MousePayload)) as MousePayload;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static MousePayload create() => MousePayload._();
  @$core.override
  MousePayload createEmptyInstance() => create();
  static $pb.PbList<MousePayload> createRepeated() => $pb.PbList<MousePayload>();
  @$core.pragma('dart2js:noInline')
  static MousePayload getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<MousePayload>(create);
  static MousePayload? _defaultInstance;

  /// move | click | scroll | down | up
  @$pb.TagNumber(1)
  $core.String get event => $_getSZ(0);
  @$pb.TagNumber(1)
  set event($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasEvent() => $_has(0);
  @$pb.TagNumber(1)
  void clearEvent() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.double get dx => $_getN(1);
  @$pb.TagNumber(2)
  set dx($core.double value) => $_setFloat(1, value);
  @$pb.TagNumber(2)
  $core.bool hasDx() => $_has(1);
  @$pb.TagNumber(2)
  void clearDx() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.double get dy => $_getN(2);
  @$pb.TagNumber(3)
  set dy($core.double value) => $_setFloat(2, value);
  @$pb.TagNumber(3)
  $core.bool hasDy() => $_has(2);
  @$pb.TagNumber(3)
  void clearDy() => $_clearField(3);
}

class BrowserControlPayload extends $pb.GeneratedMessage {
  factory BrowserControlPayload({
    $core.String? action,
  }) {
    final result = create();
    if (action != null) result.action = action;
    return result;
  }

  BrowserControlPayload._();

  factory BrowserControlPayload.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory BrowserControlPayload.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'BrowserControlPayload', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'action')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  BrowserControlPayload clone() => BrowserControlPayload()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  BrowserControlPayload copyWith(void Function(BrowserControlPayload) updates) => super.copyWith((message) => updates(message as BrowserControlPayload)) as BrowserControlPayload;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static BrowserControlPayload create() => BrowserControlPayload._();
  @$core.override
  BrowserControlPayload createEmptyInstance() => create();
  static $pb.PbList<BrowserControlPayload> createRepeated() => $pb.PbList<BrowserControlPayload>();
  @$core.pragma('dart2js:noInline')
  static BrowserControlPayload getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<BrowserControlPayload>(create);
  static BrowserControlPayload? _defaultInstance;

  /// refresh | toggle_ublock
  @$pb.TagNumber(1)
  $core.String get action => $_getSZ(0);
  @$pb.TagNumber(1)
  set action($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasAction() => $_has(0);
  @$pb.TagNumber(1)
  void clearAction() => $_clearField(1);
}

class StatusMessage extends $pb.GeneratedMessage {
  factory StatusMessage({
    $core.String? type,
    $core.String? state,
    $fixnum.Int64? position,
    $fixnum.Int64? duration,
    $core.String? title,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (state != null) result.state = state;
    if (position != null) result.position = position;
    if (duration != null) result.duration = duration;
    if (title != null) result.title = title;
    return result;
  }

  StatusMessage._();

  factory StatusMessage.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory StatusMessage.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'StatusMessage', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'type')
    ..aOS(2, _omitFieldNames ? '' : 'state')
    ..aInt64(3, _omitFieldNames ? '' : 'position')
    ..aInt64(4, _omitFieldNames ? '' : 'duration')
    ..aOS(5, _omitFieldNames ? '' : 'title')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  StatusMessage clone() => StatusMessage()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  StatusMessage copyWith(void Function(StatusMessage) updates) => super.copyWith((message) => updates(message as StatusMessage)) as StatusMessage;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static StatusMessage create() => StatusMessage._();
  @$core.override
  StatusMessage createEmptyInstance() => create();
  static $pb.PbList<StatusMessage> createRepeated() => $pb.PbList<StatusMessage>();
  @$core.pragma('dart2js:noInline')
  static StatusMessage getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<StatusMessage>(create);
  static StatusMessage? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get type => $_getSZ(0);
  @$pb.TagNumber(1)
  set type($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasType() => $_has(0);
  @$pb.TagNumber(1)
  void clearType() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get state => $_getSZ(1);
  @$pb.TagNumber(2)
  set state($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasState() => $_has(1);
  @$pb.TagNumber(2)
  void clearState() => $_clearField(2);

  @$pb.TagNumber(3)
  $fixnum.Int64 get position => $_getI64(2);
  @$pb.TagNumber(3)
  set position($fixnum.Int64 value) => $_setInt64(2, value);
  @$pb.TagNumber(3)
  $core.bool hasPosition() => $_has(2);
  @$pb.TagNumber(3)
  void clearPosition() => $_clearField(3);

  @$pb.TagNumber(4)
  $fixnum.Int64 get duration => $_getI64(3);
  @$pb.TagNumber(4)
  set duration($fixnum.Int64 value) => $_setInt64(3, value);
  @$pb.TagNumber(4)
  $core.bool hasDuration() => $_has(3);
  @$pb.TagNumber(4)
  void clearDuration() => $_clearField(4);

  @$pb.TagNumber(5)
  $core.String get title => $_getSZ(4);
  @$pb.TagNumber(5)
  set title($core.String value) => $_setString(4, value);
  @$pb.TagNumber(5)
  $core.bool hasTitle() => $_has(4);
  @$pb.TagNumber(5)
  void clearTitle() => $_clearField(5);
}

class ContextMessage extends $pb.GeneratedMessage {
  factory ContextMessage({
    $core.String? type,
    $core.String? active,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (active != null) result.active = active;
    return result;
  }

  ContextMessage._();

  factory ContextMessage.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory ContextMessage.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'ContextMessage', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'type')
    ..aOS(2, _omitFieldNames ? '' : 'active')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ContextMessage clone() => ContextMessage()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ContextMessage copyWith(void Function(ContextMessage) updates) => super.copyWith((message) => updates(message as ContextMessage)) as ContextMessage;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static ContextMessage create() => ContextMessage._();
  @$core.override
  ContextMessage createEmptyInstance() => create();
  static $pb.PbList<ContextMessage> createRepeated() => $pb.PbList<ContextMessage>();
  @$core.pragma('dart2js:noInline')
  static ContextMessage getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<ContextMessage>(create);
  static ContextMessage? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get type => $_getSZ(0);
  @$pb.TagNumber(1)
  set type($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasType() => $_has(0);
  @$pb.TagNumber(1)
  void clearType() => $_clearField(1);

  /// player | browser | idle
  @$pb.TagNumber(2)
  $core.String get active => $_getSZ(1);
  @$pb.TagNumber(2)
  set active($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasActive() => $_has(1);
  @$pb.TagNumber(2)
  void clearActive() => $_clearField(2);
}

class PlaylistItemInfo extends $pb.GeneratedMessage {
  factory PlaylistItemInfo({
    $core.int? index,
    $core.String? title,
  }) {
    final result = create();
    if (index != null) result.index = index;
    if (title != null) result.title = title;
    return result;
  }

  PlaylistItemInfo._();

  factory PlaylistItemInfo.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory PlaylistItemInfo.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'PlaylistItemInfo', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..a<$core.int>(1, _omitFieldNames ? '' : 'index', $pb.PbFieldType.O3)
    ..aOS(2, _omitFieldNames ? '' : 'title')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PlaylistItemInfo clone() => PlaylistItemInfo()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PlaylistItemInfo copyWith(void Function(PlaylistItemInfo) updates) => super.copyWith((message) => updates(message as PlaylistItemInfo)) as PlaylistItemInfo;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static PlaylistItemInfo create() => PlaylistItemInfo._();
  @$core.override
  PlaylistItemInfo createEmptyInstance() => create();
  static $pb.PbList<PlaylistItemInfo> createRepeated() => $pb.PbList<PlaylistItemInfo>();
  @$core.pragma('dart2js:noInline')
  static PlaylistItemInfo getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<PlaylistItemInfo>(create);
  static PlaylistItemInfo? _defaultInstance;

  @$pb.TagNumber(1)
  $core.int get index => $_getIZ(0);
  @$pb.TagNumber(1)
  set index($core.int value) => $_setSignedInt32(0, value);
  @$pb.TagNumber(1)
  $core.bool hasIndex() => $_has(0);
  @$pb.TagNumber(1)
  void clearIndex() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get title => $_getSZ(1);
  @$pb.TagNumber(2)
  set title($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasTitle() => $_has(1);
  @$pb.TagNumber(2)
  void clearTitle() => $_clearField(2);
}

class PlaylistStatusMessage extends $pb.GeneratedMessage {
  factory PlaylistStatusMessage({
    $core.String? type,
    $core.Iterable<PlaylistItemInfo>? items,
    $core.int? currentIndex,
    $core.int? totalCount,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (items != null) result.items.addAll(items);
    if (currentIndex != null) result.currentIndex = currentIndex;
    if (totalCount != null) result.totalCount = totalCount;
    return result;
  }

  PlaylistStatusMessage._();

  factory PlaylistStatusMessage.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory PlaylistStatusMessage.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'PlaylistStatusMessage', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'type')
    ..pc<PlaylistItemInfo>(2, _omitFieldNames ? '' : 'items', $pb.PbFieldType.PM, subBuilder: PlaylistItemInfo.create)
    ..a<$core.int>(3, _omitFieldNames ? '' : 'currentIndex', $pb.PbFieldType.O3)
    ..a<$core.int>(4, _omitFieldNames ? '' : 'totalCount', $pb.PbFieldType.O3)
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PlaylistStatusMessage clone() => PlaylistStatusMessage()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PlaylistStatusMessage copyWith(void Function(PlaylistStatusMessage) updates) => super.copyWith((message) => updates(message as PlaylistStatusMessage)) as PlaylistStatusMessage;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static PlaylistStatusMessage create() => PlaylistStatusMessage._();
  @$core.override
  PlaylistStatusMessage createEmptyInstance() => create();
  static $pb.PbList<PlaylistStatusMessage> createRepeated() => $pb.PbList<PlaylistStatusMessage>();
  @$core.pragma('dart2js:noInline')
  static PlaylistStatusMessage getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<PlaylistStatusMessage>(create);
  static PlaylistStatusMessage? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get type => $_getSZ(0);
  @$pb.TagNumber(1)
  set type($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasType() => $_has(0);
  @$pb.TagNumber(1)
  void clearType() => $_clearField(1);

  @$pb.TagNumber(2)
  $pb.PbList<PlaylistItemInfo> get items => $_getList(1);

  @$pb.TagNumber(3)
  $core.int get currentIndex => $_getIZ(2);
  @$pb.TagNumber(3)
  set currentIndex($core.int value) => $_setSignedInt32(2, value);
  @$pb.TagNumber(3)
  $core.bool hasCurrentIndex() => $_has(2);
  @$pb.TagNumber(3)
  void clearCurrentIndex() => $_clearField(3);

  @$pb.TagNumber(4)
  $core.int get totalCount => $_getIZ(3);
  @$pb.TagNumber(4)
  set totalCount($core.int value) => $_setSignedInt32(3, value);
  @$pb.TagNumber(4)
  $core.bool hasTotalCount() => $_has(3);
  @$pb.TagNumber(4)
  void clearTotalCount() => $_clearField(4);
}

class AuthMessage extends $pb.GeneratedMessage {
  factory AuthMessage({
    $core.String? type,
    $core.String? token,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (token != null) result.token = token;
    return result;
  }

  AuthMessage._();

  factory AuthMessage.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory AuthMessage.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'AuthMessage', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'type')
    ..aOS(2, _omitFieldNames ? '' : 'token')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  AuthMessage clone() => AuthMessage()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  AuthMessage copyWith(void Function(AuthMessage) updates) => super.copyWith((message) => updates(message as AuthMessage)) as AuthMessage;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static AuthMessage create() => AuthMessage._();
  @$core.override
  AuthMessage createEmptyInstance() => create();
  static $pb.PbList<AuthMessage> createRepeated() => $pb.PbList<AuthMessage>();
  @$core.pragma('dart2js:noInline')
  static AuthMessage getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<AuthMessage>(create);
  static AuthMessage? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get type => $_getSZ(0);
  @$pb.TagNumber(1)
  set type($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasType() => $_has(0);
  @$pb.TagNumber(1)
  void clearType() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get token => $_getSZ(1);
  @$pb.TagNumber(2)
  set token($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasToken() => $_has(1);
  @$pb.TagNumber(2)
  void clearToken() => $_clearField(2);
}

class AuthResponse extends $pb.GeneratedMessage {
  factory AuthResponse({
    $core.String? type,
    $core.bool? success,
    $core.String? token,
    $core.String? certFingerprint,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (success != null) result.success = success;
    if (token != null) result.token = token;
    if (certFingerprint != null) result.certFingerprint = certFingerprint;
    return result;
  }

  AuthResponse._();

  factory AuthResponse.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory AuthResponse.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'AuthResponse', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'type')
    ..aOB(2, _omitFieldNames ? '' : 'success')
    ..aOS(3, _omitFieldNames ? '' : 'token')
    ..aOS(4, _omitFieldNames ? '' : 'certFingerprint')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  AuthResponse clone() => AuthResponse()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  AuthResponse copyWith(void Function(AuthResponse) updates) => super.copyWith((message) => updates(message as AuthResponse)) as AuthResponse;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static AuthResponse create() => AuthResponse._();
  @$core.override
  AuthResponse createEmptyInstance() => create();
  static $pb.PbList<AuthResponse> createRepeated() => $pb.PbList<AuthResponse>();
  @$core.pragma('dart2js:noInline')
  static AuthResponse getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<AuthResponse>(create);
  static AuthResponse? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get type => $_getSZ(0);
  @$pb.TagNumber(1)
  set type($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasType() => $_has(0);
  @$pb.TagNumber(1)
  void clearType() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.bool get success => $_getBF(1);
  @$pb.TagNumber(2)
  set success($core.bool value) => $_setBool(1, value);
  @$pb.TagNumber(2)
  $core.bool hasSuccess() => $_has(1);
  @$pb.TagNumber(2)
  void clearSuccess() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.String get token => $_getSZ(2);
  @$pb.TagNumber(3)
  set token($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasToken() => $_has(2);
  @$pb.TagNumber(3)
  void clearToken() => $_clearField(3);

  /// Lets the receiver re-assert (or rotate) its SPKI pin on reconnect. Same
  /// format as PairingApprovedMessage.cert_fingerprint. Optional during rollout.
  @$pb.TagNumber(4)
  $core.String get certFingerprint => $_getSZ(3);
  @$pb.TagNumber(4)
  set certFingerprint($core.String value) => $_setString(3, value);
  @$pb.TagNumber(4)
  $core.bool hasCertFingerprint() => $_has(3);
  @$pb.TagNumber(4)
  void clearCertFingerprint() => $_clearField(4);
}

/// Phone → TV on first connection (no saved token). TV shows Allow/Deny prompt.
class PairingRequestMessage extends $pb.GeneratedMessage {
  factory PairingRequestMessage({
    $core.String? type,
    $core.String? deviceName,
    $core.String? deviceUuid,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (deviceName != null) result.deviceName = deviceName;
    if (deviceUuid != null) result.deviceUuid = deviceUuid;
    return result;
  }

  PairingRequestMessage._();

  factory PairingRequestMessage.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory PairingRequestMessage.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'PairingRequestMessage', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'type')
    ..aOS(2, _omitFieldNames ? '' : 'deviceName')
    ..aOS(3, _omitFieldNames ? '' : 'deviceUUID', protoName: 'device_uuid')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PairingRequestMessage clone() => PairingRequestMessage()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PairingRequestMessage copyWith(void Function(PairingRequestMessage) updates) => super.copyWith((message) => updates(message as PairingRequestMessage)) as PairingRequestMessage;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static PairingRequestMessage create() => PairingRequestMessage._();
  @$core.override
  PairingRequestMessage createEmptyInstance() => create();
  static $pb.PbList<PairingRequestMessage> createRepeated() => $pb.PbList<PairingRequestMessage>();
  @$core.pragma('dart2js:noInline')
  static PairingRequestMessage getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<PairingRequestMessage>(create);
  static PairingRequestMessage? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get type => $_getSZ(0);
  @$pb.TagNumber(1)
  set type($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasType() => $_has(0);
  @$pb.TagNumber(1)
  void clearType() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get deviceName => $_getSZ(1);
  @$pb.TagNumber(2)
  set deviceName($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasDeviceName() => $_has(1);
  @$pb.TagNumber(2)
  void clearDeviceName() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.String get deviceUuid => $_getSZ(2);
  @$pb.TagNumber(3)
  set deviceUuid($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasDeviceUuid() => $_has(2);
  @$pb.TagNumber(3)
  void clearDeviceUuid() => $_clearField(3);
}

/// SAS Handshake: Step 1 (Sender -> TV Commit)
class PairingCommitMessage extends $pb.GeneratedMessage {
  factory PairingCommitMessage({
    $core.String? type,
    $core.String? commit,
    $core.String? deviceName,
    $core.String? deviceUuid,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (commit != null) result.commit = commit;
    if (deviceName != null) result.deviceName = deviceName;
    if (deviceUuid != null) result.deviceUuid = deviceUuid;
    return result;
  }

  PairingCommitMessage._();

  factory PairingCommitMessage.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory PairingCommitMessage.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'PairingCommitMessage', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'type')
    ..aOS(2, _omitFieldNames ? '' : 'commit')
    ..aOS(3, _omitFieldNames ? '' : 'deviceName')
    ..aOS(4, _omitFieldNames ? '' : 'deviceUUID', protoName: 'device_uuid')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PairingCommitMessage clone() => PairingCommitMessage()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PairingCommitMessage copyWith(void Function(PairingCommitMessage) updates) => super.copyWith((message) => updates(message as PairingCommitMessage)) as PairingCommitMessage;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static PairingCommitMessage create() => PairingCommitMessage._();
  @$core.override
  PairingCommitMessage createEmptyInstance() => create();
  static $pb.PbList<PairingCommitMessage> createRepeated() => $pb.PbList<PairingCommitMessage>();
  @$core.pragma('dart2js:noInline')
  static PairingCommitMessage getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<PairingCommitMessage>(create);
  static PairingCommitMessage? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get type => $_getSZ(0);
  @$pb.TagNumber(1)
  set type($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasType() => $_has(0);
  @$pb.TagNumber(1)
  void clearType() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get commit => $_getSZ(1);
  @$pb.TagNumber(2)
  set commit($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasCommit() => $_has(1);
  @$pb.TagNumber(2)
  void clearCommit() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.String get deviceName => $_getSZ(2);
  @$pb.TagNumber(3)
  set deviceName($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasDeviceName() => $_has(2);
  @$pb.TagNumber(3)
  void clearDeviceName() => $_clearField(3);

  @$pb.TagNumber(4)
  $core.String get deviceUuid => $_getSZ(3);
  @$pb.TagNumber(4)
  set deviceUuid($core.String value) => $_setString(3, value);
  @$pb.TagNumber(4)
  $core.bool hasDeviceUuid() => $_has(3);
  @$pb.TagNumber(4)
  void clearDeviceUuid() => $_clearField(4);
}

/// SAS Handshake: Step 2 (TV -> Sender Challenge)
class PairingChallengeMessage extends $pb.GeneratedMessage {
  factory PairingChallengeMessage({
    $core.String? type,
    $core.String? tvEphPub,
    $core.String? nonceT,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (tvEphPub != null) result.tvEphPub = tvEphPub;
    if (nonceT != null) result.nonceT = nonceT;
    return result;
  }

  PairingChallengeMessage._();

  factory PairingChallengeMessage.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory PairingChallengeMessage.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'PairingChallengeMessage', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'type')
    ..aOS(2, _omitFieldNames ? '' : 'tvEphPub')
    ..aOS(3, _omitFieldNames ? '' : 'nonceT')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PairingChallengeMessage clone() => PairingChallengeMessage()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PairingChallengeMessage copyWith(void Function(PairingChallengeMessage) updates) => super.copyWith((message) => updates(message as PairingChallengeMessage)) as PairingChallengeMessage;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static PairingChallengeMessage create() => PairingChallengeMessage._();
  @$core.override
  PairingChallengeMessage createEmptyInstance() => create();
  static $pb.PbList<PairingChallengeMessage> createRepeated() => $pb.PbList<PairingChallengeMessage>();
  @$core.pragma('dart2js:noInline')
  static PairingChallengeMessage getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<PairingChallengeMessage>(create);
  static PairingChallengeMessage? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get type => $_getSZ(0);
  @$pb.TagNumber(1)
  set type($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasType() => $_has(0);
  @$pb.TagNumber(1)
  void clearType() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get tvEphPub => $_getSZ(1);
  @$pb.TagNumber(2)
  set tvEphPub($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasTvEphPub() => $_has(1);
  @$pb.TagNumber(2)
  void clearTvEphPub() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.String get nonceT => $_getSZ(2);
  @$pb.TagNumber(3)
  set nonceT($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasNonceT() => $_has(2);
  @$pb.TagNumber(3)
  void clearNonceT() => $_clearField(3);
}

/// SAS Handshake: Step 3 (Sender -> TV Reveal)
class PairingRevealMessage extends $pb.GeneratedMessage {
  factory PairingRevealMessage({
    $core.String? type,
    $core.String? senderEphPub,
    $core.String? nonceS,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (senderEphPub != null) result.senderEphPub = senderEphPub;
    if (nonceS != null) result.nonceS = nonceS;
    return result;
  }

  PairingRevealMessage._();

  factory PairingRevealMessage.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory PairingRevealMessage.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'PairingRevealMessage', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'type')
    ..aOS(2, _omitFieldNames ? '' : 'senderEphPub')
    ..aOS(3, _omitFieldNames ? '' : 'nonceS')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PairingRevealMessage clone() => PairingRevealMessage()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PairingRevealMessage copyWith(void Function(PairingRevealMessage) updates) => super.copyWith((message) => updates(message as PairingRevealMessage)) as PairingRevealMessage;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static PairingRevealMessage create() => PairingRevealMessage._();
  @$core.override
  PairingRevealMessage createEmptyInstance() => create();
  static $pb.PbList<PairingRevealMessage> createRepeated() => $pb.PbList<PairingRevealMessage>();
  @$core.pragma('dart2js:noInline')
  static PairingRevealMessage getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<PairingRevealMessage>(create);
  static PairingRevealMessage? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get type => $_getSZ(0);
  @$pb.TagNumber(1)
  set type($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasType() => $_has(0);
  @$pb.TagNumber(1)
  void clearType() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get senderEphPub => $_getSZ(1);
  @$pb.TagNumber(2)
  set senderEphPub($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasSenderEphPub() => $_has(1);
  @$pb.TagNumber(2)
  void clearSenderEphPub() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.String get nonceS => $_getSZ(2);
  @$pb.TagNumber(3)
  set nonceS($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasNonceS() => $_has(2);
  @$pb.TagNumber(3)
  void clearNonceS() => $_clearField(3);
}

/// SAS Handshake: Step 4 (Sender -> TV Confirmation)
class PairingConfirmationMessage extends $pb.GeneratedMessage {
  factory PairingConfirmationMessage({
    $core.String? type,
    $core.String? mac,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (mac != null) result.mac = mac;
    return result;
  }

  PairingConfirmationMessage._();

  factory PairingConfirmationMessage.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory PairingConfirmationMessage.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'PairingConfirmationMessage', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'type')
    ..aOS(2, _omitFieldNames ? '' : 'mac')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PairingConfirmationMessage clone() => PairingConfirmationMessage()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PairingConfirmationMessage copyWith(void Function(PairingConfirmationMessage) updates) => super.copyWith((message) => updates(message as PairingConfirmationMessage)) as PairingConfirmationMessage;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static PairingConfirmationMessage create() => PairingConfirmationMessage._();
  @$core.override
  PairingConfirmationMessage createEmptyInstance() => create();
  static $pb.PbList<PairingConfirmationMessage> createRepeated() => $pb.PbList<PairingConfirmationMessage>();
  @$core.pragma('dart2js:noInline')
  static PairingConfirmationMessage getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<PairingConfirmationMessage>(create);
  static PairingConfirmationMessage? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get type => $_getSZ(0);
  @$pb.TagNumber(1)
  set type($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasType() => $_has(0);
  @$pb.TagNumber(1)
  void clearType() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get mac => $_getSZ(1);
  @$pb.TagNumber(2)
  set mac($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasMac() => $_has(1);
  @$pb.TagNumber(2)
  void clearMac() => $_clearField(2);
}

/// TV → Phone: user tapped Allow.
class PairingApprovedMessage extends $pb.GeneratedMessage {
  factory PairingApprovedMessage({
    $core.String? type,
    $core.String? token,
    $core.String? certFingerprint,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (token != null) result.token = token;
    if (certFingerprint != null) result.certFingerprint = certFingerprint;
    return result;
  }

  PairingApprovedMessage._();

  factory PairingApprovedMessage.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory PairingApprovedMessage.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'PairingApprovedMessage', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'type')
    ..aOS(2, _omitFieldNames ? '' : 'token')
    ..aOS(3, _omitFieldNames ? '' : 'certFingerprint')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PairingApprovedMessage clone() => PairingApprovedMessage()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PairingApprovedMessage copyWith(void Function(PairingApprovedMessage) updates) => super.copyWith((message) => updates(message as PairingApprovedMessage)) as PairingApprovedMessage;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static PairingApprovedMessage create() => PairingApprovedMessage._();
  @$core.override
  PairingApprovedMessage createEmptyInstance() => create();
  static $pb.PbList<PairingApprovedMessage> createRepeated() => $pb.PbList<PairingApprovedMessage>();
  @$core.pragma('dart2js:noInline')
  static PairingApprovedMessage getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<PairingApprovedMessage>(create);
  static PairingApprovedMessage? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get type => $_getSZ(0);
  @$pb.TagNumber(1)
  set type($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasType() => $_has(0);
  @$pb.TagNumber(1)
  void clearType() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get token => $_getSZ(1);
  @$pb.TagNumber(2)
  set token($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasToken() => $_has(1);
  @$pb.TagNumber(2)
  void clearToken() => $_clearField(2);

  /// SPKI pin of the receiver's self-signed TLS cert, format "sha256/<base64>"
  /// (SHA-256 of the DER-encoded SubjectPublicKeyInfo — same scheme as OkHttp's
  /// CertificatePinner). The sender stores this alongside the token at pairing
  /// and validates it on every wss:// connection (TOFU pinning). Optional during
  /// rollout: absent until receivers terminate TLS.
  @$pb.TagNumber(3)
  $core.String get certFingerprint => $_getSZ(2);
  @$pb.TagNumber(3)
  set certFingerprint($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasCertFingerprint() => $_has(2);
  @$pb.TagNumber(3)
  void clearCertFingerprint() => $_clearField(3);
}

/// TV → Phone: user tapped Deny or 30s timeout elapsed.
class PairingDeniedMessage extends $pb.GeneratedMessage {
  factory PairingDeniedMessage({
    $core.String? type,
  }) {
    final result = create();
    if (type != null) result.type = type;
    return result;
  }

  PairingDeniedMessage._();

  factory PairingDeniedMessage.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory PairingDeniedMessage.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'PairingDeniedMessage', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'type')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PairingDeniedMessage clone() => PairingDeniedMessage()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PairingDeniedMessage copyWith(void Function(PairingDeniedMessage) updates) => super.copyWith((message) => updates(message as PairingDeniedMessage)) as PairingDeniedMessage;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static PairingDeniedMessage create() => PairingDeniedMessage._();
  @$core.override
  PairingDeniedMessage createEmptyInstance() => create();
  static $pb.PbList<PairingDeniedMessage> createRepeated() => $pb.PbList<PairingDeniedMessage>();
  @$core.pragma('dart2js:noInline')
  static PairingDeniedMessage getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<PairingDeniedMessage>(create);
  static PairingDeniedMessage? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get type => $_getSZ(0);
  @$pb.TagNumber(1)
  set type($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasType() => $_has(0);
  @$pb.TagNumber(1)
  void clearType() => $_clearField(1);
}

class PingMessage extends $pb.GeneratedMessage {
  factory PingMessage({
    $core.String? type,
  }) {
    final result = create();
    if (type != null) result.type = type;
    return result;
  }

  PingMessage._();

  factory PingMessage.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory PingMessage.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'PingMessage', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'type')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PingMessage clone() => PingMessage()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PingMessage copyWith(void Function(PingMessage) updates) => super.copyWith((message) => updates(message as PingMessage)) as PingMessage;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static PingMessage create() => PingMessage._();
  @$core.override
  PingMessage createEmptyInstance() => create();
  static $pb.PbList<PingMessage> createRepeated() => $pb.PbList<PingMessage>();
  @$core.pragma('dart2js:noInline')
  static PingMessage getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<PingMessage>(create);
  static PingMessage? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get type => $_getSZ(0);
  @$pb.TagNumber(1)
  set type($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasType() => $_has(0);
  @$pb.TagNumber(1)
  void clearType() => $_clearField(1);
}

class PongMessage extends $pb.GeneratedMessage {
  factory PongMessage({
    $core.String? type,
  }) {
    final result = create();
    if (type != null) result.type = type;
    return result;
  }

  PongMessage._();

  factory PongMessage.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory PongMessage.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'PongMessage', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'type')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PongMessage clone() => PongMessage()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PongMessage copyWith(void Function(PongMessage) updates) => super.copyWith((message) => updates(message as PongMessage)) as PongMessage;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static PongMessage create() => PongMessage._();
  @$core.override
  PongMessage createEmptyInstance() => create();
  static $pb.PbList<PongMessage> createRepeated() => $pb.PbList<PongMessage>();
  @$core.pragma('dart2js:noInline')
  static PongMessage getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<PongMessage>(create);
  static PongMessage? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get type => $_getSZ(0);
  @$pb.TagNumber(1)
  set type($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasType() => $_has(0);
  @$pb.TagNumber(1)
  void clearType() => $_clearField(1);
}


const $core.bool _omitFieldNames = $core.bool.fromEnvironment('protobuf.omit_field_names');
const $core.bool _omitMessageNames = $core.bool.fromEnvironment('protobuf.omit_message_names');
