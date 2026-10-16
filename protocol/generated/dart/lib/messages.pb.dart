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
    $core.String? mediaKind,
    $core.String? requestId,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (action != null) result.action = action;
    if (state != null) result.state = state;
    if (position != null) result.position = position;
    if (duration != null) result.duration = duration;
    if (title != null) result.title = title;
    if (mediaKind != null) result.mediaKind = mediaKind;
    if (requestId != null) result.requestId = requestId;
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
    ..aOS(7, _omitFieldNames ? '' : 'mediaKind')
    ..aOS(8, _omitFieldNames ? '' : 'requestId')
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

  @$pb.TagNumber(7)
  $core.String get mediaKind => $_getSZ(6);
  @$pb.TagNumber(7)
  set mediaKind($core.String value) => $_setString(6, value);
  @$pb.TagNumber(7)
  $core.bool hasMediaKind() => $_has(6);
  @$pb.TagNumber(7)
  void clearMediaKind() => $_clearField(7);

  @$pb.TagNumber(8)
  $core.String get requestId => $_getSZ(7);
  @$pb.TagNumber(8)
  set requestId($core.String value) => $_setString(7, value);
  @$pb.TagNumber(8)
  $core.bool hasRequestId() => $_has(7);
  @$pb.TagNumber(8)
  void clearRequestId() => $_clearField(8);
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
    $core.String? artist,
    $core.String? album,
    $core.String? albumArtist,
    $core.String? artworkUrl,
    $core.int? trackNumber,
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
    if (artist != null) result.artist = artist;
    if (album != null) result.album = album;
    if (albumArtist != null) result.albumArtist = albumArtist;
    if (artworkUrl != null) result.artworkUrl = artworkUrl;
    if (trackNumber != null) result.trackNumber = trackNumber;
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
    ..aOS(17, _omitFieldNames ? '' : 'artist')
    ..aOS(18, _omitFieldNames ? '' : 'album')
    ..aOS(19, _omitFieldNames ? '' : 'albumArtist')
    ..aOS(20, _omitFieldNames ? '' : 'artworkUrl')
    ..a<$core.int>(21, _omitFieldNames ? '' : 'trackNumber', $pb.PbFieldType.O3)
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

  @$pb.TagNumber(17)
  $core.String get artist => $_getSZ(16);
  @$pb.TagNumber(17)
  set artist($core.String value) => $_setString(16, value);
  @$pb.TagNumber(17)
  $core.bool hasArtist() => $_has(16);
  @$pb.TagNumber(17)
  void clearArtist() => $_clearField(17);

  @$pb.TagNumber(18)
  $core.String get album => $_getSZ(17);
  @$pb.TagNumber(18)
  set album($core.String value) => $_setString(17, value);
  @$pb.TagNumber(18)
  $core.bool hasAlbum() => $_has(17);
  @$pb.TagNumber(18)
  void clearAlbum() => $_clearField(18);

  @$pb.TagNumber(19)
  $core.String get albumArtist => $_getSZ(18);
  @$pb.TagNumber(19)
  set albumArtist($core.String value) => $_setString(18, value);
  @$pb.TagNumber(19)
  $core.bool hasAlbumArtist() => $_has(18);
  @$pb.TagNumber(19)
  void clearAlbumArtist() => $_clearField(19);

  @$pb.TagNumber(20)
  $core.String get artworkUrl => $_getSZ(19);
  @$pb.TagNumber(20)
  set artworkUrl($core.String value) => $_setString(19, value);
  @$pb.TagNumber(20)
  $core.bool hasArtworkUrl() => $_has(19);
  @$pb.TagNumber(20)
  void clearArtworkUrl() => $_clearField(20);

  @$pb.TagNumber(21)
  $core.int get trackNumber => $_getIZ(20);
  @$pb.TagNumber(21)
  set trackNumber($core.int value) => $_setSignedInt32(20, value);
  @$pb.TagNumber(21)
  $core.bool hasTrackNumber() => $_has(20);
  @$pb.TagNumber(21)
  void clearTrackNumber() => $_clearField(21);
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

/// Nonsecret item identity for generic progress callbacks.
class ProgressIdentity extends $pb.GeneratedMessage {
  factory ProgressIdentity({
    $core.String? type,
    $core.String? contentId,
    $core.String? videoId,
    $core.int? season,
    $core.int? episode,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (contentId != null) result.contentId = contentId;
    if (videoId != null) result.videoId = videoId;
    if (season != null) result.season = season;
    if (episode != null) result.episode = episode;
    return result;
  }

  ProgressIdentity._();

  factory ProgressIdentity.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory ProgressIdentity.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'ProgressIdentity', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'type')
    ..aOS(2, _omitFieldNames ? '' : 'contentId')
    ..aOS(3, _omitFieldNames ? '' : 'videoId')
    ..a<$core.int>(4, _omitFieldNames ? '' : 'season', $pb.PbFieldType.O3)
    ..a<$core.int>(5, _omitFieldNames ? '' : 'episode', $pb.PbFieldType.O3)
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ProgressIdentity clone() => ProgressIdentity()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ProgressIdentity copyWith(void Function(ProgressIdentity) updates) => super.copyWith((message) => updates(message as ProgressIdentity)) as ProgressIdentity;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static ProgressIdentity create() => ProgressIdentity._();
  @$core.override
  ProgressIdentity createEmptyInstance() => create();
  static $pb.PbList<ProgressIdentity> createRepeated() => $pb.PbList<ProgressIdentity>();
  @$core.pragma('dart2js:noInline')
  static ProgressIdentity getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<ProgressIdentity>(create);
  static ProgressIdentity? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get type => $_getSZ(0);
  @$pb.TagNumber(1)
  set type($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasType() => $_has(0);
  @$pb.TagNumber(1)
  void clearType() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get contentId => $_getSZ(1);
  @$pb.TagNumber(2)
  set contentId($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasContentId() => $_has(1);
  @$pb.TagNumber(2)
  void clearContentId() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.String get videoId => $_getSZ(2);
  @$pb.TagNumber(3)
  set videoId($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasVideoId() => $_has(2);
  @$pb.TagNumber(3)
  void clearVideoId() => $_clearField(3);

  @$pb.TagNumber(4)
  $core.int get season => $_getIZ(3);
  @$pb.TagNumber(4)
  set season($core.int value) => $_setSignedInt32(3, value);
  @$pb.TagNumber(4)
  $core.bool hasSeason() => $_has(3);
  @$pb.TagNumber(4)
  void clearSeason() => $_clearField(4);

  @$pb.TagNumber(5)
  $core.int get episode => $_getIZ(4);
  @$pb.TagNumber(5)
  set episode($core.int value) => $_setSignedInt32(4, value);
  @$pb.TagNumber(5)
  $core.bool hasEpisode() => $_has(4);
  @$pb.TagNumber(5)
  void clearEpisode() => $_clearField(5);
}

/// Session-only credentials: NEVER persist, log, or echo in status/history.
class ProgressWebhook extends $pb.GeneratedMessage {
  @$core.override
  $core.String toString() => 'ProgressWebhook([redacted])';
  factory ProgressWebhook({
    $core.String? url,
    $core.String? bearerToken,
  }) {
    final result = create();
    if (url != null) result.url = url;
    if (bearerToken != null) result.bearerToken = bearerToken;
    return result;
  }

  ProgressWebhook._();

  factory ProgressWebhook.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory ProgressWebhook.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'ProgressWebhook', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'url')
    ..aOS(2, _omitFieldNames ? '' : 'bearerToken')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ProgressWebhook clone() => ProgressWebhook()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ProgressWebhook copyWith(void Function(ProgressWebhook) updates) => super.copyWith((message) => updates(message as ProgressWebhook)) as ProgressWebhook;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static ProgressWebhook create() => ProgressWebhook._();
  @$core.override
  ProgressWebhook createEmptyInstance() => create();
  static $pb.PbList<ProgressWebhook> createRepeated() => $pb.PbList<ProgressWebhook>();
  @$core.pragma('dart2js:noInline')
  static ProgressWebhook getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<ProgressWebhook>(create);
  static ProgressWebhook? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get url => $_getSZ(0);
  @$pb.TagNumber(1)
  set url($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasUrl() => $_has(0);
  @$pb.TagNumber(1)
  void clearUrl() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get bearerToken => $_getSZ(1);
  @$pb.TagNumber(2)
  set bearerToken($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasBearerToken() => $_has(1);
  @$pb.TagNumber(2)
  void clearBearerToken() => $_clearField(2);
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
    $core.Iterable<$core.String>? allowedPrivateOrigins,
    $core.Iterable<SubtitleResource>? subtitleResources,
    $core.String? mediaKind,
    $fixnum.Int64? displayDurationMs,
    $core.bool? skipHistory,
    $core.String? itemId,
    ProgressIdentity? progressIdentity,
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
    if (allowedPrivateOrigins != null) result.allowedPrivateOrigins.addAll(allowedPrivateOrigins);
    if (subtitleResources != null) result.subtitleResources.addAll(subtitleResources);
    if (mediaKind != null) result.mediaKind = mediaKind;
    if (displayDurationMs != null) result.displayDurationMs = displayDurationMs;
    if (skipHistory != null) result.skipHistory = skipHistory;
    if (itemId != null) result.itemId = itemId;
    if (progressIdentity != null) result.progressIdentity = progressIdentity;
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
    ..pPS(15, _omitFieldNames ? '' : 'allowedPrivateOrigins')
    ..pc<SubtitleResource>(16, _omitFieldNames ? '' : 'subtitleResources', $pb.PbFieldType.PM, subBuilder: SubtitleResource.create)
    ..aOS(17, _omitFieldNames ? '' : 'mediaKind')
    ..aInt64(18, _omitFieldNames ? '' : 'displayDurationMs')
    ..aOB(19, _omitFieldNames ? '' : 'skipHistory')
    ..aOS(20, _omitFieldNames ? '' : 'itemId')
    ..aOM<ProgressIdentity>(21, _omitFieldNames ? '' : 'progressIdentity', subBuilder: ProgressIdentity.create)
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

  /// Exact private/LAN origins approved by the sender for page-initiated media.
  /// Receivers must still validate every resolved address, redirect, and derived resource.
  @$pb.TagNumber(15)
  $pb.PbList<$core.String> get allowedPrivateOrigins => $_getList(14);

  /// Additive replacement for credentialed sidecars. Legacy `subtitles` remains
  /// supported for senders that only provide URLs.
  @$pb.TagNumber(16)
  $pb.PbList<SubtitleResource> get subtitleResources => $_getList(15);

  /// Per-item presentation kind. Omitted means the receiver should infer it.
  @$pb.TagNumber(17)
  $core.String get mediaKind => $_getSZ(16);
  @$pb.TagNumber(17)
  set mediaKind($core.String value) => $_setString(16, value);
  @$pb.TagNumber(17)
  $core.bool hasMediaKind() => $_has(16);
  @$pb.TagNumber(17)
  void clearMediaKind() => $_clearField(17);

  /// For image items only. Positive values auto-advance after this display time;
  /// omitted or zero keeps the image visible until explicit navigation.
  @$pb.TagNumber(18)
  $fixnum.Int64 get displayDurationMs => $_getI64(17);
  @$pb.TagNumber(18)
  set displayDurationMs($fixnum.Int64 value) => $_setInt64(17, value);
  @$pb.TagNumber(18)
  $core.bool hasDisplayDurationMs() => $_has(17);
  @$pb.TagNumber(18)
  void clearDisplayDurationMs() => $_clearField(18);

  /// Do not persist receiver history, resume progress, or captured history artwork.
  @$pb.TagNumber(19)
  $core.bool get skipHistory => $_getBF(18);
  @$pb.TagNumber(19)
  set skipHistory($core.bool value) => $_setBool(18, value);
  @$pb.TagNumber(19)
  $core.bool hasSkipHistory() => $_has(18);
  @$pb.TagNumber(19)
  void clearSkipHistory() => $_clearField(19);

  /// Stable receiver queue identity. Receivers assign one when omitted.
  @$pb.TagNumber(20)
  $core.String get itemId => $_getSZ(19);
  @$pb.TagNumber(20)
  set itemId($core.String value) => $_setString(19, value);
  @$pb.TagNumber(20)
  $core.bool hasItemId() => $_has(19);
  @$pb.TagNumber(20)
  void clearItemId() => $_clearField(20);

  @$pb.TagNumber(21)
  ProgressIdentity get progressIdentity => $_getN(20);
  @$pb.TagNumber(21)
  set progressIdentity(ProgressIdentity value) => $_setField(21, value);
  @$pb.TagNumber(21)
  $core.bool hasProgressIdentity() => $_has(20);
  @$pb.TagNumber(21)
  void clearProgressIdentity() => $_clearField(21);
  @$pb.TagNumber(21)
  ProgressIdentity ensureProgressIdentity() => $_ensure(20);
}

class PlaylistPayload extends $pb.GeneratedMessage {
  factory PlaylistPayload({
    $core.Iterable<PlayPayload>? items,
    $core.int? startIndex,
    VisualMetadata? visualMetadata,
    $core.bool? skipPreplay,
    ProgressWebhook? progressWebhook,
  }) {
    final result = create();
    if (items != null) result.items.addAll(items);
    if (startIndex != null) result.startIndex = startIndex;
    if (visualMetadata != null) result.visualMetadata = visualMetadata;
    if (skipPreplay != null) result.skipPreplay = skipPreplay;
    if (progressWebhook != null) result.progressWebhook = progressWebhook;
    return result;
  }

  PlaylistPayload._();

  factory PlaylistPayload.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory PlaylistPayload.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'PlaylistPayload', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..pc<PlayPayload>(1, _omitFieldNames ? '' : 'items', $pb.PbFieldType.PM, subBuilder: PlayPayload.create)
    ..a<$core.int>(2, _omitFieldNames ? '' : 'startIndex', $pb.PbFieldType.O3)
    ..aOM<VisualMetadata>(3, _omitFieldNames ? '' : 'visualMetadata', subBuilder: VisualMetadata.create)
    ..aOB(4, _omitFieldNames ? '' : 'skipPreplay')
    ..aOM<ProgressWebhook>(5, _omitFieldNames ? '' : 'progressWebhook', subBuilder: ProgressWebhook.create)
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

  /// Presentation hint for receivers that normally show a metadata-rich pre-play screen.
  /// Metadata remains available for playback controls and history when this is true.
  @$pb.TagNumber(4)
  $core.bool get skipPreplay => $_getBF(3);
  @$pb.TagNumber(4)
  set skipPreplay($core.bool value) => $_setBool(3, value);
  @$pb.TagNumber(4)
  $core.bool hasSkipPreplay() => $_has(3);
  @$pb.TagNumber(4)
  void clearSkipPreplay() => $_clearField(4);

  @$pb.TagNumber(5)
  ProgressWebhook get progressWebhook => $_getN(4);
  @$pb.TagNumber(5)
  set progressWebhook(ProgressWebhook value) => $_setField(5, value);
  @$pb.TagNumber(5)
  $core.bool hasProgressWebhook() => $_has(4);
  @$pb.TagNumber(5)
  void clearProgressWebhook() => $_clearField(5);
  @$pb.TagNumber(5)
  ProgressWebhook ensureProgressWebhook() => $_ensure(4);
}

class QueueAddPayload extends $pb.GeneratedMessage {
  factory QueueAddPayload({
    PlayPayload? item,
    $core.Iterable<PlayPayload>? items,
    $core.String? ifPlaybackId,
  }) {
    final result = create();
    if (item != null) result.item = item;
    if (items != null) result.items.addAll(items);
    if (ifPlaybackId != null) result.ifPlaybackId = ifPlaybackId;
    return result;
  }

  QueueAddPayload._();

  factory QueueAddPayload.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory QueueAddPayload.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'QueueAddPayload', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOM<PlayPayload>(1, _omitFieldNames ? '' : 'item', subBuilder: PlayPayload.create)
    ..pc<PlayPayload>(2, _omitFieldNames ? '' : 'items', $pb.PbFieldType.PM, subBuilder: PlayPayload.create)
    ..aOS(3, _omitFieldNames ? '' : 'ifPlaybackId')
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

  @$pb.TagNumber(2)
  $pb.PbList<PlayPayload> get items => $_getList(1);

  @$pb.TagNumber(3)
  $core.String get ifPlaybackId => $_getSZ(2);
  @$pb.TagNumber(3)
  set ifPlaybackId($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasIfPlaybackId() => $_has(2);
  @$pb.TagNumber(3)
  void clearIfPlaybackId() => $_clearField(3);
}

class PlaylistJumpPayload extends $pb.GeneratedMessage {
  factory PlaylistJumpPayload({
    $core.int? index,
    $core.String? itemId,
    $core.String? ifPlaybackId,
  }) {
    final result = create();
    if (index != null) result.index = index;
    if (itemId != null) result.itemId = itemId;
    if (ifPlaybackId != null) result.ifPlaybackId = ifPlaybackId;
    return result;
  }

  PlaylistJumpPayload._();

  factory PlaylistJumpPayload.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory PlaylistJumpPayload.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'PlaylistJumpPayload', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..a<$core.int>(1, _omitFieldNames ? '' : 'index', $pb.PbFieldType.O3)
    ..aOS(2, _omitFieldNames ? '' : 'itemId')
    ..aOS(3, _omitFieldNames ? '' : 'ifPlaybackId')
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

  @$pb.TagNumber(2)
  $core.String get itemId => $_getSZ(1);
  @$pb.TagNumber(2)
  set itemId($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasItemId() => $_has(1);
  @$pb.TagNumber(2)
  void clearItemId() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.String get ifPlaybackId => $_getSZ(2);
  @$pb.TagNumber(3)
  set ifPlaybackId($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasIfPlaybackId() => $_has(2);
  @$pb.TagNumber(3)
  void clearIfPlaybackId() => $_clearField(3);
}

class QueueRemovePayload extends $pb.GeneratedMessage {
  factory QueueRemovePayload({
    $core.Iterable<$core.String>? itemIds,
    $core.String? ifPlaybackId,
  }) {
    final result = create();
    if (itemIds != null) result.itemIds.addAll(itemIds);
    if (ifPlaybackId != null) result.ifPlaybackId = ifPlaybackId;
    return result;
  }

  QueueRemovePayload._();

  factory QueueRemovePayload.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory QueueRemovePayload.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'QueueRemovePayload', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..pPS(1, _omitFieldNames ? '' : 'itemIds')
    ..aOS(2, _omitFieldNames ? '' : 'ifPlaybackId')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  QueueRemovePayload clone() => QueueRemovePayload()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  QueueRemovePayload copyWith(void Function(QueueRemovePayload) updates) => super.copyWith((message) => updates(message as QueueRemovePayload)) as QueueRemovePayload;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static QueueRemovePayload create() => QueueRemovePayload._();
  @$core.override
  QueueRemovePayload createEmptyInstance() => create();
  static $pb.PbList<QueueRemovePayload> createRepeated() => $pb.PbList<QueueRemovePayload>();
  @$core.pragma('dart2js:noInline')
  static QueueRemovePayload getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<QueueRemovePayload>(create);
  static QueueRemovePayload? _defaultInstance;

  @$pb.TagNumber(1)
  $pb.PbList<$core.String> get itemIds => $_getList(0);

  @$pb.TagNumber(2)
  $core.String get ifPlaybackId => $_getSZ(1);
  @$pb.TagNumber(2)
  set ifPlaybackId($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasIfPlaybackId() => $_has(1);
  @$pb.TagNumber(2)
  void clearIfPlaybackId() => $_clearField(2);
}

class QueueMovePayload extends $pb.GeneratedMessage {
  factory QueueMovePayload({
    $core.String? itemId,
    $core.String? beforeItemId,
    $core.String? ifPlaybackId,
  }) {
    final result = create();
    if (itemId != null) result.itemId = itemId;
    if (beforeItemId != null) result.beforeItemId = beforeItemId;
    if (ifPlaybackId != null) result.ifPlaybackId = ifPlaybackId;
    return result;
  }

  QueueMovePayload._();

  factory QueueMovePayload.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory QueueMovePayload.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'QueueMovePayload', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'itemId')
    ..aOS(2, _omitFieldNames ? '' : 'beforeItemId')
    ..aOS(3, _omitFieldNames ? '' : 'ifPlaybackId')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  QueueMovePayload clone() => QueueMovePayload()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  QueueMovePayload copyWith(void Function(QueueMovePayload) updates) => super.copyWith((message) => updates(message as QueueMovePayload)) as QueueMovePayload;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static QueueMovePayload create() => QueueMovePayload._();
  @$core.override
  QueueMovePayload createEmptyInstance() => create();
  static $pb.PbList<QueueMovePayload> createRepeated() => $pb.PbList<QueueMovePayload>();
  @$core.pragma('dart2js:noInline')
  static QueueMovePayload getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<QueueMovePayload>(create);
  static QueueMovePayload? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get itemId => $_getSZ(0);
  @$pb.TagNumber(1)
  set itemId($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasItemId() => $_has(0);
  @$pb.TagNumber(1)
  void clearItemId() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get beforeItemId => $_getSZ(1);
  @$pb.TagNumber(2)
  set beforeItemId($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasBeforeItemId() => $_has(1);
  @$pb.TagNumber(2)
  void clearBeforeItemId() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.String get ifPlaybackId => $_getSZ(2);
  @$pb.TagNumber(3)
  set ifPlaybackId($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasIfPlaybackId() => $_has(2);
  @$pb.TagNumber(3)
  void clearIfPlaybackId() => $_clearField(3);
}

class QueueClearPayload extends $pb.GeneratedMessage {
  factory QueueClearPayload({
    $core.String? ifPlaybackId,
  }) {
    final result = create();
    if (ifPlaybackId != null) result.ifPlaybackId = ifPlaybackId;
    return result;
  }

  QueueClearPayload._();

  factory QueueClearPayload.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory QueueClearPayload.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'QueueClearPayload', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'ifPlaybackId')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  QueueClearPayload clone() => QueueClearPayload()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  QueueClearPayload copyWith(void Function(QueueClearPayload) updates) => super.copyWith((message) => updates(message as QueueClearPayload)) as QueueClearPayload;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static QueueClearPayload create() => QueueClearPayload._();
  @$core.override
  QueueClearPayload createEmptyInstance() => create();
  static $pb.PbList<QueueClearPayload> createRepeated() => $pb.PbList<QueueClearPayload>();
  @$core.pragma('dart2js:noInline')
  static QueueClearPayload getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<QueueClearPayload>(create);
  static QueueClearPayload? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get ifPlaybackId => $_getSZ(0);
  @$pb.TagNumber(1)
  set ifPlaybackId($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasIfPlaybackId() => $_has(0);
  @$pb.TagNumber(1)
  void clearIfPlaybackId() => $_clearField(1);
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
    SubtitleResource? subtitleResource,
  }) {
    final result = create();
    if (command != null) result.command = command;
    if (subtitleResource != null) result.subtitleResource = subtitleResource;
    return result;
  }

  ControlPayload._();

  factory ControlPayload.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory ControlPayload.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'ControlPayload', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'command')
    ..aOM<SubtitleResource>(2, _omitFieldNames ? '' : 'subtitleResource', subBuilder: SubtitleResource.create)
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

  /// Used by add_subtitle for late sidecar attachment without reloading playback.
  @$pb.TagNumber(2)
  SubtitleResource get subtitleResource => $_getN(1);
  @$pb.TagNumber(2)
  set subtitleResource(SubtitleResource value) => $_setField(2, value);
  @$pb.TagNumber(2)
  $core.bool hasSubtitleResource() => $_has(1);
  @$pb.TagNumber(2)
  void clearSubtitleResource() => $_clearField(2);
  @$pb.TagNumber(2)
  SubtitleResource ensureSubtitleResource() => $_ensure(1);
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
    $core.String? mediaKind,
    $core.String? playbackId,
    $core.String? currentItemId,
    $core.bool? fullscreen,
    $core.String? error,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (state != null) result.state = state;
    if (position != null) result.position = position;
    if (duration != null) result.duration = duration;
    if (title != null) result.title = title;
    if (mediaKind != null) result.mediaKind = mediaKind;
    if (playbackId != null) result.playbackId = playbackId;
    if (currentItemId != null) result.currentItemId = currentItemId;
    if (fullscreen != null) result.fullscreen = fullscreen;
    if (error != null) result.error = error;
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
    ..aOS(6, _omitFieldNames ? '' : 'mediaKind')
    ..aOS(7, _omitFieldNames ? '' : 'playbackId')
    ..aOS(8, _omitFieldNames ? '' : 'currentItemId')
    ..aOB(9, _omitFieldNames ? '' : 'fullscreen')
    ..aOS(10, _omitFieldNames ? '' : 'error')
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

  @$pb.TagNumber(6)
  $core.String get mediaKind => $_getSZ(5);
  @$pb.TagNumber(6)
  set mediaKind($core.String value) => $_setString(5, value);
  @$pb.TagNumber(6)
  $core.bool hasMediaKind() => $_has(5);
  @$pb.TagNumber(6)
  void clearMediaKind() => $_clearField(6);

  @$pb.TagNumber(7)
  $core.String get playbackId => $_getSZ(6);
  @$pb.TagNumber(7)
  set playbackId($core.String value) => $_setString(6, value);
  @$pb.TagNumber(7)
  $core.bool hasPlaybackId() => $_has(6);
  @$pb.TagNumber(7)
  void clearPlaybackId() => $_clearField(7);

  @$pb.TagNumber(8)
  $core.String get currentItemId => $_getSZ(7);
  @$pb.TagNumber(8)
  set currentItemId($core.String value) => $_setString(7, value);
  @$pb.TagNumber(8)
  $core.bool hasCurrentItemId() => $_has(7);
  @$pb.TagNumber(8)
  void clearCurrentItemId() => $_clearField(8);

  /// Native browser fullscreen. Omitted by the player and by older receivers.
  @$pb.TagNumber(9)
  $core.bool get fullscreen => $_getBF(8);
  @$pb.TagNumber(9)
  set fullscreen($core.bool value) => $_setBool(8, value);
  @$pb.TagNumber(9)
  $core.bool hasFullscreen() => $_has(8);
  @$pb.TagNumber(9)
  void clearFullscreen() => $_clearField(9);

  /// Short user-safe failure reason for state=error; never raw exception/URL text.
  @$pb.TagNumber(10)
  $core.String get error => $_getSZ(9);
  @$pb.TagNumber(10)
  set error($core.String value) => $_setString(9, value);
  @$pb.TagNumber(10)
  $core.bool hasError() => $_has(9);
  @$pb.TagNumber(10)
  void clearError() => $_clearField(10);
}

/// Complete player_settings helper. On WSS, type/speed/scaling/audioBoost/
/// subtitleOffsetMs/engine remain required. Unsupported controls use neutral
/// values with honest availability flags; active loop/engine stay in state.
class PlayerSettingsMessage extends $pb.GeneratedMessage {
  factory PlayerSettingsMessage({
    $core.String? type,
    $core.bool? loop,
    $core.double? speed,
    $core.String? scaling,
    $core.bool? audioBoost,
    $fixnum.Int64? subtitleOffsetMs,
    $core.String? engine,
    $core.int? qualityMaxHeight,
    $core.int? currentVideoHeight,
    $core.bool? isLive,
    $core.bool? isSeekable,
    $core.bool? speedAvailable,
    $core.bool? scalingAvailable,
    $core.bool? audioBoostAvailable,
    $core.bool? qualityAvailable,
    $core.bool? subtitleOffsetAvailable,
    $core.bool? engineAvailable,
    $core.bool? loopAvailable,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (loop != null) result.loop = loop;
    if (speed != null) result.speed = speed;
    if (scaling != null) result.scaling = scaling;
    if (audioBoost != null) result.audioBoost = audioBoost;
    if (subtitleOffsetMs != null) result.subtitleOffsetMs = subtitleOffsetMs;
    if (engine != null) result.engine = engine;
    if (qualityMaxHeight != null) result.qualityMaxHeight = qualityMaxHeight;
    if (currentVideoHeight != null) result.currentVideoHeight = currentVideoHeight;
    if (isLive != null) result.isLive = isLive;
    if (isSeekable != null) result.isSeekable = isSeekable;
    if (speedAvailable != null) result.speedAvailable = speedAvailable;
    if (scalingAvailable != null) result.scalingAvailable = scalingAvailable;
    if (audioBoostAvailable != null) result.audioBoostAvailable = audioBoostAvailable;
    if (qualityAvailable != null) result.qualityAvailable = qualityAvailable;
    if (subtitleOffsetAvailable != null) result.subtitleOffsetAvailable = subtitleOffsetAvailable;
    if (engineAvailable != null) result.engineAvailable = engineAvailable;
    if (loopAvailable != null) result.loopAvailable = loopAvailable;
    return result;
  }

  PlayerSettingsMessage._();

  factory PlayerSettingsMessage.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory PlayerSettingsMessage.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'PlayerSettingsMessage', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'type')
    ..aOB(2, _omitFieldNames ? '' : 'loop')
    ..a<$core.double>(3, _omitFieldNames ? '' : 'speed', $pb.PbFieldType.OD)
    ..aOS(4, _omitFieldNames ? '' : 'scaling')
    ..aOB(5, _omitFieldNames ? '' : 'audioBoost')
    ..aInt64(6, _omitFieldNames ? '' : 'subtitleOffsetMs')
    ..aOS(7, _omitFieldNames ? '' : 'engine')
    ..a<$core.int>(8, _omitFieldNames ? '' : 'qualityMaxHeight', $pb.PbFieldType.O3)
    ..a<$core.int>(9, _omitFieldNames ? '' : 'currentVideoHeight', $pb.PbFieldType.O3)
    ..aOB(10, _omitFieldNames ? '' : 'isLive')
    ..aOB(11, _omitFieldNames ? '' : 'isSeekable')
    ..aOB(12, _omitFieldNames ? '' : 'speedAvailable')
    ..aOB(13, _omitFieldNames ? '' : 'scalingAvailable')
    ..aOB(14, _omitFieldNames ? '' : 'audioBoostAvailable')
    ..aOB(15, _omitFieldNames ? '' : 'qualityAvailable')
    ..aOB(16, _omitFieldNames ? '' : 'subtitleOffsetAvailable')
    ..aOB(17, _omitFieldNames ? '' : 'engineAvailable')
    ..aOB(18, _omitFieldNames ? '' : 'loopAvailable')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PlayerSettingsMessage clone() => PlayerSettingsMessage()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PlayerSettingsMessage copyWith(void Function(PlayerSettingsMessage) updates) => super.copyWith((message) => updates(message as PlayerSettingsMessage)) as PlayerSettingsMessage;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static PlayerSettingsMessage create() => PlayerSettingsMessage._();
  @$core.override
  PlayerSettingsMessage createEmptyInstance() => create();
  static $pb.PbList<PlayerSettingsMessage> createRepeated() => $pb.PbList<PlayerSettingsMessage>();
  @$core.pragma('dart2js:noInline')
  static PlayerSettingsMessage getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<PlayerSettingsMessage>(create);
  static PlayerSettingsMessage? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get type => $_getSZ(0);
  @$pb.TagNumber(1)
  set type($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasType() => $_has(0);
  @$pb.TagNumber(1)
  void clearType() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.bool get loop => $_getBF(1);
  @$pb.TagNumber(2)
  set loop($core.bool value) => $_setBool(1, value);
  @$pb.TagNumber(2)
  $core.bool hasLoop() => $_has(1);
  @$pb.TagNumber(2)
  void clearLoop() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.double get speed => $_getN(2);
  @$pb.TagNumber(3)
  set speed($core.double value) => $_setDouble(2, value);
  @$pb.TagNumber(3)
  $core.bool hasSpeed() => $_has(2);
  @$pb.TagNumber(3)
  void clearSpeed() => $_clearField(3);

  @$pb.TagNumber(4)
  $core.String get scaling => $_getSZ(3);
  @$pb.TagNumber(4)
  set scaling($core.String value) => $_setString(3, value);
  @$pb.TagNumber(4)
  $core.bool hasScaling() => $_has(3);
  @$pb.TagNumber(4)
  void clearScaling() => $_clearField(4);

  @$pb.TagNumber(5)
  $core.bool get audioBoost => $_getBF(4);
  @$pb.TagNumber(5)
  set audioBoost($core.bool value) => $_setBool(4, value);
  @$pb.TagNumber(5)
  $core.bool hasAudioBoost() => $_has(4);
  @$pb.TagNumber(5)
  void clearAudioBoost() => $_clearField(5);

  @$pb.TagNumber(6)
  $fixnum.Int64 get subtitleOffsetMs => $_getI64(5);
  @$pb.TagNumber(6)
  set subtitleOffsetMs($fixnum.Int64 value) => $_setInt64(5, value);
  @$pb.TagNumber(6)
  $core.bool hasSubtitleOffsetMs() => $_has(5);
  @$pb.TagNumber(6)
  void clearSubtitleOffsetMs() => $_clearField(6);

  @$pb.TagNumber(7)
  $core.String get engine => $_getSZ(6);
  @$pb.TagNumber(7)
  set engine($core.String value) => $_setString(6, value);
  @$pb.TagNumber(7)
  $core.bool hasEngine() => $_has(6);
  @$pb.TagNumber(7)
  void clearEngine() => $_clearField(7);

  @$pb.TagNumber(8)
  $core.int get qualityMaxHeight => $_getIZ(7);
  @$pb.TagNumber(8)
  set qualityMaxHeight($core.int value) => $_setSignedInt32(7, value);
  @$pb.TagNumber(8)
  $core.bool hasQualityMaxHeight() => $_has(7);
  @$pb.TagNumber(8)
  void clearQualityMaxHeight() => $_clearField(8);

  @$pb.TagNumber(9)
  $core.int get currentVideoHeight => $_getIZ(8);
  @$pb.TagNumber(9)
  set currentVideoHeight($core.int value) => $_setSignedInt32(8, value);
  @$pb.TagNumber(9)
  $core.bool hasCurrentVideoHeight() => $_has(8);
  @$pb.TagNumber(9)
  void clearCurrentVideoHeight() => $_clearField(9);

  @$pb.TagNumber(10)
  $core.bool get isLive => $_getBF(9);
  @$pb.TagNumber(10)
  set isLive($core.bool value) => $_setBool(9, value);
  @$pb.TagNumber(10)
  $core.bool hasIsLive() => $_has(9);
  @$pb.TagNumber(10)
  void clearIsLive() => $_clearField(10);

  @$pb.TagNumber(11)
  $core.bool get isSeekable => $_getBF(10);
  @$pb.TagNumber(11)
  set isSeekable($core.bool value) => $_setBool(10, value);
  @$pb.TagNumber(11)
  $core.bool hasIsSeekable() => $_has(10);
  @$pb.TagNumber(11)
  void clearIsSeekable() => $_clearField(11);

  @$pb.TagNumber(12)
  $core.bool get speedAvailable => $_getBF(11);
  @$pb.TagNumber(12)
  set speedAvailable($core.bool value) => $_setBool(11, value);
  @$pb.TagNumber(12)
  $core.bool hasSpeedAvailable() => $_has(11);
  @$pb.TagNumber(12)
  void clearSpeedAvailable() => $_clearField(12);

  @$pb.TagNumber(13)
  $core.bool get scalingAvailable => $_getBF(12);
  @$pb.TagNumber(13)
  set scalingAvailable($core.bool value) => $_setBool(12, value);
  @$pb.TagNumber(13)
  $core.bool hasScalingAvailable() => $_has(12);
  @$pb.TagNumber(13)
  void clearScalingAvailable() => $_clearField(13);

  @$pb.TagNumber(14)
  $core.bool get audioBoostAvailable => $_getBF(13);
  @$pb.TagNumber(14)
  set audioBoostAvailable($core.bool value) => $_setBool(13, value);
  @$pb.TagNumber(14)
  $core.bool hasAudioBoostAvailable() => $_has(13);
  @$pb.TagNumber(14)
  void clearAudioBoostAvailable() => $_clearField(14);

  @$pb.TagNumber(15)
  $core.bool get qualityAvailable => $_getBF(14);
  @$pb.TagNumber(15)
  set qualityAvailable($core.bool value) => $_setBool(14, value);
  @$pb.TagNumber(15)
  $core.bool hasQualityAvailable() => $_has(14);
  @$pb.TagNumber(15)
  void clearQualityAvailable() => $_clearField(15);

  @$pb.TagNumber(16)
  $core.bool get subtitleOffsetAvailable => $_getBF(15);
  @$pb.TagNumber(16)
  set subtitleOffsetAvailable($core.bool value) => $_setBool(15, value);
  @$pb.TagNumber(16)
  $core.bool hasSubtitleOffsetAvailable() => $_has(15);
  @$pb.TagNumber(16)
  void clearSubtitleOffsetAvailable() => $_clearField(16);

  @$pb.TagNumber(17)
  $core.bool get engineAvailable => $_getBF(16);
  @$pb.TagNumber(17)
  set engineAvailable($core.bool value) => $_setBool(16, value);
  @$pb.TagNumber(17)
  $core.bool hasEngineAvailable() => $_has(16);
  @$pb.TagNumber(17)
  void clearEngineAvailable() => $_clearField(17);

  @$pb.TagNumber(18)
  $core.bool get loopAvailable => $_getBF(17);
  @$pb.TagNumber(18)
  set loopAvailable($core.bool value) => $_setBool(17, value);
  @$pb.TagNumber(18)
  $core.bool hasLoopAvailable() => $_has(17);
  @$pb.TagNumber(18)
  void clearLoopAvailable() => $_clearField(18);
}

class ContextMessage extends $pb.GeneratedMessage {
  factory ContextMessage({
    $core.String? type,
    $core.String? active,
    $core.String? engine,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (active != null) result.active = active;
    if (engine != null) result.engine = engine;
    return result;
  }

  ContextMessage._();

  factory ContextMessage.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory ContextMessage.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'ContextMessage', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'type')
    ..aOS(2, _omitFieldNames ? '' : 'active')
    ..aOS(3, _omitFieldNames ? '' : 'engine')
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

  @$pb.TagNumber(3)
  $core.String get engine => $_getSZ(2);
  @$pb.TagNumber(3)
  set engine($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasEngine() => $_has(2);
  @$pb.TagNumber(3)
  void clearEngine() => $_clearField(3);
}

class PlaylistItemInfo extends $pb.GeneratedMessage {
  factory PlaylistItemInfo({
    $core.int? index,
    $core.String? title,
    $core.String? mediaKind,
    $core.String? itemId,
    $core.int? season,
    $core.int? episode,
    $core.String? imdbId,
    $core.String? tmdbId,
    $core.String? bingeGroup,
  }) {
    final result = create();
    if (index != null) result.index = index;
    if (title != null) result.title = title;
    if (mediaKind != null) result.mediaKind = mediaKind;
    if (itemId != null) result.itemId = itemId;
    if (season != null) result.season = season;
    if (episode != null) result.episode = episode;
    if (imdbId != null) result.imdbId = imdbId;
    if (tmdbId != null) result.tmdbId = tmdbId;
    if (bingeGroup != null) result.bingeGroup = bingeGroup;
    return result;
  }

  PlaylistItemInfo._();

  factory PlaylistItemInfo.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory PlaylistItemInfo.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'PlaylistItemInfo', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..a<$core.int>(1, _omitFieldNames ? '' : 'index', $pb.PbFieldType.O3)
    ..aOS(2, _omitFieldNames ? '' : 'title')
    ..aOS(3, _omitFieldNames ? '' : 'mediaKind')
    ..aOS(4, _omitFieldNames ? '' : 'itemId')
    ..a<$core.int>(5, _omitFieldNames ? '' : 'season', $pb.PbFieldType.O3)
    ..a<$core.int>(6, _omitFieldNames ? '' : 'episode', $pb.PbFieldType.O3)
    ..aOS(7, _omitFieldNames ? '' : 'imdbId')
    ..aOS(8, _omitFieldNames ? '' : 'tmdbId')
    ..aOS(9, _omitFieldNames ? '' : 'bingeGroup')
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

  @$pb.TagNumber(3)
  $core.String get mediaKind => $_getSZ(2);
  @$pb.TagNumber(3)
  set mediaKind($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasMediaKind() => $_has(2);
  @$pb.TagNumber(3)
  void clearMediaKind() => $_clearField(3);

  @$pb.TagNumber(4)
  $core.String get itemId => $_getSZ(3);
  @$pb.TagNumber(4)
  set itemId($core.String value) => $_setString(3, value);
  @$pb.TagNumber(4)
  $core.bool hasItemId() => $_has(3);
  @$pb.TagNumber(4)
  void clearItemId() => $_clearField(4);

  @$pb.TagNumber(5)
  $core.int get season => $_getIZ(4);
  @$pb.TagNumber(5)
  set season($core.int value) => $_setSignedInt32(4, value);
  @$pb.TagNumber(5)
  $core.bool hasSeason() => $_has(4);
  @$pb.TagNumber(5)
  void clearSeason() => $_clearField(5);

  @$pb.TagNumber(6)
  $core.int get episode => $_getIZ(5);
  @$pb.TagNumber(6)
  set episode($core.int value) => $_setSignedInt32(5, value);
  @$pb.TagNumber(6)
  $core.bool hasEpisode() => $_has(5);
  @$pb.TagNumber(6)
  void clearEpisode() => $_clearField(6);

  @$pb.TagNumber(7)
  $core.String get imdbId => $_getSZ(6);
  @$pb.TagNumber(7)
  set imdbId($core.String value) => $_setString(6, value);
  @$pb.TagNumber(7)
  $core.bool hasImdbId() => $_has(6);
  @$pb.TagNumber(7)
  void clearImdbId() => $_clearField(7);

  @$pb.TagNumber(8)
  $core.String get tmdbId => $_getSZ(7);
  @$pb.TagNumber(8)
  set tmdbId($core.String value) => $_setString(7, value);
  @$pb.TagNumber(8)
  $core.bool hasTmdbId() => $_has(7);
  @$pb.TagNumber(8)
  void clearTmdbId() => $_clearField(8);

  @$pb.TagNumber(9)
  $core.String get bingeGroup => $_getSZ(8);
  @$pb.TagNumber(9)
  set bingeGroup($core.String value) => $_setString(8, value);
  @$pb.TagNumber(9)
  $core.bool hasBingeGroup() => $_has(8);
  @$pb.TagNumber(9)
  void clearBingeGroup() => $_clearField(9);
}

class PlaylistStatusMessage extends $pb.GeneratedMessage {
  factory PlaylistStatusMessage({
    $core.String? type,
    $core.Iterable<PlaylistItemInfo>? items,
    $core.int? currentIndex,
    $core.int? totalCount,
    $core.String? playbackId,
    $fixnum.Int64? queueRevision,
    $core.String? currentItemId,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (items != null) result.items.addAll(items);
    if (currentIndex != null) result.currentIndex = currentIndex;
    if (totalCount != null) result.totalCount = totalCount;
    if (playbackId != null) result.playbackId = playbackId;
    if (queueRevision != null) result.queueRevision = queueRevision;
    if (currentItemId != null) result.currentItemId = currentItemId;
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
    ..aOS(5, _omitFieldNames ? '' : 'playbackId')
    ..aInt64(6, _omitFieldNames ? '' : 'queueRevision')
    ..aOS(7, _omitFieldNames ? '' : 'currentItemId')
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

  @$pb.TagNumber(5)
  $core.String get playbackId => $_getSZ(4);
  @$pb.TagNumber(5)
  set playbackId($core.String value) => $_setString(4, value);
  @$pb.TagNumber(5)
  $core.bool hasPlaybackId() => $_has(4);
  @$pb.TagNumber(5)
  void clearPlaybackId() => $_clearField(5);

  @$pb.TagNumber(6)
  $fixnum.Int64 get queueRevision => $_getI64(5);
  @$pb.TagNumber(6)
  set queueRevision($fixnum.Int64 value) => $_setInt64(5, value);
  @$pb.TagNumber(6)
  $core.bool hasQueueRevision() => $_has(5);
  @$pb.TagNumber(6)
  void clearQueueRevision() => $_clearField(6);

  @$pb.TagNumber(7)
  $core.String get currentItemId => $_getSZ(6);
  @$pb.TagNumber(7)
  set currentItemId($core.String value) => $_setString(6, value);
  @$pb.TagNumber(7)
  $core.bool hasCurrentItemId() => $_has(6);
  @$pb.TagNumber(7)
  void clearCurrentItemId() => $_clearField(7);
}

class CommandResultMessage extends $pb.GeneratedMessage {
  factory CommandResultMessage({
    $core.String? type,
    $core.String? requestId,
    $core.bool? ok,
    $core.String? error,
    $core.String? message,
    $core.String? playbackId,
    $fixnum.Int64? queueRevision,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (requestId != null) result.requestId = requestId;
    if (ok != null) result.ok = ok;
    if (error != null) result.error = error;
    if (message != null) result.message = message;
    if (playbackId != null) result.playbackId = playbackId;
    if (queueRevision != null) result.queueRevision = queueRevision;
    return result;
  }

  CommandResultMessage._();

  factory CommandResultMessage.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory CommandResultMessage.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'CommandResultMessage', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'type')
    ..aOS(2, _omitFieldNames ? '' : 'requestId')
    ..aOB(3, _omitFieldNames ? '' : 'ok')
    ..aOS(4, _omitFieldNames ? '' : 'error')
    ..aOS(5, _omitFieldNames ? '' : 'message')
    ..aOS(6, _omitFieldNames ? '' : 'playbackId')
    ..aInt64(7, _omitFieldNames ? '' : 'queueRevision')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  CommandResultMessage clone() => CommandResultMessage()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  CommandResultMessage copyWith(void Function(CommandResultMessage) updates) => super.copyWith((message) => updates(message as CommandResultMessage)) as CommandResultMessage;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static CommandResultMessage create() => CommandResultMessage._();
  @$core.override
  CommandResultMessage createEmptyInstance() => create();
  static $pb.PbList<CommandResultMessage> createRepeated() => $pb.PbList<CommandResultMessage>();
  @$core.pragma('dart2js:noInline')
  static CommandResultMessage getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<CommandResultMessage>(create);
  static CommandResultMessage? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get type => $_getSZ(0);
  @$pb.TagNumber(1)
  set type($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasType() => $_has(0);
  @$pb.TagNumber(1)
  void clearType() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get requestId => $_getSZ(1);
  @$pb.TagNumber(2)
  set requestId($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasRequestId() => $_has(1);
  @$pb.TagNumber(2)
  void clearRequestId() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.bool get ok => $_getBF(2);
  @$pb.TagNumber(3)
  set ok($core.bool value) => $_setBool(2, value);
  @$pb.TagNumber(3)
  $core.bool hasOk() => $_has(2);
  @$pb.TagNumber(3)
  void clearOk() => $_clearField(3);

  @$pb.TagNumber(4)
  $core.String get error => $_getSZ(3);
  @$pb.TagNumber(4)
  set error($core.String value) => $_setString(3, value);
  @$pb.TagNumber(4)
  $core.bool hasError() => $_has(3);
  @$pb.TagNumber(4)
  void clearError() => $_clearField(4);

  @$pb.TagNumber(5)
  $core.String get message => $_getSZ(4);
  @$pb.TagNumber(5)
  set message($core.String value) => $_setString(4, value);
  @$pb.TagNumber(5)
  $core.bool hasMessage() => $_has(4);
  @$pb.TagNumber(5)
  void clearMessage() => $_clearField(5);

  @$pb.TagNumber(6)
  $core.String get playbackId => $_getSZ(5);
  @$pb.TagNumber(6)
  set playbackId($core.String value) => $_setString(5, value);
  @$pb.TagNumber(6)
  $core.bool hasPlaybackId() => $_has(5);
  @$pb.TagNumber(6)
  void clearPlaybackId() => $_clearField(6);

  @$pb.TagNumber(7)
  $fixnum.Int64 get queueRevision => $_getI64(6);
  @$pb.TagNumber(7)
  set queueRevision($fixnum.Int64 value) => $_setInt64(6, value);
  @$pb.TagNumber(7)
  $core.bool hasQueueRevision() => $_has(6);
  @$pb.TagNumber(7)
  void clearQueueRevision() => $_clearField(7);
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
    $core.Iterable<$core.String>? players,
    $core.Iterable<$core.String>? browsers,
    $core.bool? screenMirrorWebRtc,
    $core.Iterable<$core.String>? mediaKinds,
    $core.Iterable<$core.String>? features,
    ReceiverCapabilities? capabilities,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (success != null) result.success = success;
    if (token != null) result.token = token;
    if (certFingerprint != null) result.certFingerprint = certFingerprint;
    if (players != null) result.players.addAll(players);
    if (browsers != null) result.browsers.addAll(browsers);
    if (screenMirrorWebRtc != null) result.screenMirrorWebRtc = screenMirrorWebRtc;
    if (mediaKinds != null) result.mediaKinds.addAll(mediaKinds);
    if (features != null) result.features.addAll(features);
    if (capabilities != null) result.capabilities = capabilities;
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
    ..pPS(5, _omitFieldNames ? '' : 'players')
    ..pPS(6, _omitFieldNames ? '' : 'browsers')
    ..aOB(7, _omitFieldNames ? '' : 'screenMirrorWebRtc')
    ..pPS(8, _omitFieldNames ? '' : 'mediaKinds')
    ..pPS(9, _omitFieldNames ? '' : 'features')
    ..aOM<ReceiverCapabilities>(10, _omitFieldNames ? '' : 'capabilities', subBuilder: ReceiverCapabilities.create)
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

  @$pb.TagNumber(5)
  $pb.PbList<$core.String> get players => $_getList(4);

  @$pb.TagNumber(6)
  $pb.PbList<$core.String> get browsers => $_getList(5);

  @$pb.TagNumber(7)
  $core.bool get screenMirrorWebRtc => $_getBF(6);
  @$pb.TagNumber(7)
  set screenMirrorWebRtc($core.bool value) => $_setBool(6, value);
  @$pb.TagNumber(7)
  $core.bool hasScreenMirrorWebRtc() => $_has(6);
  @$pb.TagNumber(7)
  void clearScreenMirrorWebRtc() => $_clearField(7);

  @$pb.TagNumber(8)
  $pb.PbList<$core.String> get mediaKinds => $_getList(7);

  @$pb.TagNumber(9)
  $pb.PbList<$core.String> get features => $_getList(8);

  /// Optional authenticated descriptor; existing fields and tags stay unchanged.
  @$pb.TagNumber(10)
  ReceiverCapabilities get capabilities => $_getN(9);
  @$pb.TagNumber(10)
  set capabilities(ReceiverCapabilities value) => $_setField(10, value);
  @$pb.TagNumber(10)
  $core.bool hasCapabilities() => $_has(9);
  @$pb.TagNumber(10)
  void clearCapabilities() => $_clearField(10);
  @$pb.TagNumber(10)
  ReceiverCapabilities ensureCapabilities() => $_ensure(9);
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

/// ==================== Receiver capabilities v1 ====================
/// These are JSON model helpers, not a protobuf WSS transport. Read descriptor
/// version from raw JSON first: unknown versions use frozen legacy behavior.
/// Never add descriptor plaintext to PairingApprovedMessage or SAS transcript/AAD.
class CredentialBundle extends $pb.GeneratedMessage {
  @$core.override
  $core.String toString() => 'CredentialBundle([redacted])';
  factory CredentialBundle({
    $core.String? token,
    $core.String? certFingerprint,
    $core.Iterable<$core.String>? players,
    $core.Iterable<$core.String>? browsers,
    $core.bool? screenMirrorWebRtc,
    $core.Iterable<$core.String>? mediaKinds,
    $core.Iterable<$core.String>? features,
    ReceiverCapabilities? capabilities,
  }) {
    final result = create();
    if (token != null) result.token = token;
    if (certFingerprint != null) result.certFingerprint = certFingerprint;
    if (players != null) result.players.addAll(players);
    if (browsers != null) result.browsers.addAll(browsers);
    if (screenMirrorWebRtc != null) result.screenMirrorWebRtc = screenMirrorWebRtc;
    if (mediaKinds != null) result.mediaKinds.addAll(mediaKinds);
    if (features != null) result.features.addAll(features);
    if (capabilities != null) result.capabilities = capabilities;
    return result;
  }

  CredentialBundle._();

  factory CredentialBundle.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory CredentialBundle.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'CredentialBundle', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'token')
    ..aOS(2, _omitFieldNames ? '' : 'certFingerprint')
    ..pPS(3, _omitFieldNames ? '' : 'players')
    ..pPS(4, _omitFieldNames ? '' : 'browsers')
    ..aOB(5, _omitFieldNames ? '' : 'screenMirrorWebRtc')
    ..pPS(6, _omitFieldNames ? '' : 'mediaKinds')
    ..pPS(7, _omitFieldNames ? '' : 'features')
    ..aOM<ReceiverCapabilities>(8, _omitFieldNames ? '' : 'capabilities', subBuilder: ReceiverCapabilities.create)
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  CredentialBundle clone() => CredentialBundle()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  CredentialBundle copyWith(void Function(CredentialBundle) updates) => super.copyWith((message) => updates(message as CredentialBundle)) as CredentialBundle;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static CredentialBundle create() => CredentialBundle._();
  @$core.override
  CredentialBundle createEmptyInstance() => create();
  static $pb.PbList<CredentialBundle> createRepeated() => $pb.PbList<CredentialBundle>();
  @$core.pragma('dart2js:noInline')
  static CredentialBundle getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<CredentialBundle>(create);
  static CredentialBundle? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get token => $_getSZ(0);
  @$pb.TagNumber(1)
  set token($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasToken() => $_has(0);
  @$pb.TagNumber(1)
  void clearToken() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get certFingerprint => $_getSZ(1);
  @$pb.TagNumber(2)
  set certFingerprint($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasCertFingerprint() => $_has(1);
  @$pb.TagNumber(2)
  void clearCertFingerprint() => $_clearField(2);

  @$pb.TagNumber(3)
  $pb.PbList<$core.String> get players => $_getList(2);

  @$pb.TagNumber(4)
  $pb.PbList<$core.String> get browsers => $_getList(3);

  @$pb.TagNumber(5)
  $core.bool get screenMirrorWebRtc => $_getBF(4);
  @$pb.TagNumber(5)
  set screenMirrorWebRtc($core.bool value) => $_setBool(4, value);
  @$pb.TagNumber(5)
  $core.bool hasScreenMirrorWebRtc() => $_has(4);
  @$pb.TagNumber(5)
  void clearScreenMirrorWebRtc() => $_clearField(5);

  @$pb.TagNumber(6)
  $pb.PbList<$core.String> get mediaKinds => $_getList(5);

  @$pb.TagNumber(7)
  $pb.PbList<$core.String> get features => $_getList(6);

  @$pb.TagNumber(8)
  ReceiverCapabilities get capabilities => $_getN(7);
  @$pb.TagNumber(8)
  set capabilities(ReceiverCapabilities value) => $_setField(8, value);
  @$pb.TagNumber(8)
  $core.bool hasCapabilities() => $_has(7);
  @$pb.TagNumber(8)
  void clearCapabilities() => $_clearField(8);
  @$pb.TagNumber(8)
  ReceiverCapabilities ensureCapabilities() => $_ensure(7);
}

class ReceiverCapabilities extends $pb.GeneratedMessage {
  factory ReceiverCapabilities({
    $core.int? version,
    $core.Iterable<$core.MapEntry<$core.String, ReceiverSurfaceCapabilities>>? surfaces,
    ReceiverVolumeCapabilities? volume,
    ReceiverHostInfo? host,
    $core.Iterable<$core.String>? companionHints,
  }) {
    final result = create();
    if (version != null) result.version = version;
    if (surfaces != null) result.surfaces.addEntries(surfaces);
    if (volume != null) result.volume = volume;
    if (host != null) result.host = host;
    if (companionHints != null) result.companionHints.addAll(companionHints);
    return result;
  }

  ReceiverCapabilities._();

  factory ReceiverCapabilities.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory ReceiverCapabilities.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'ReceiverCapabilities', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..a<$core.int>(1, _omitFieldNames ? '' : 'version', $pb.PbFieldType.OU3)
    ..m<$core.String, ReceiverSurfaceCapabilities>(2, _omitFieldNames ? '' : 'surfaces', entryClassName: 'ReceiverCapabilities.SurfacesEntry', keyFieldType: $pb.PbFieldType.OS, valueFieldType: $pb.PbFieldType.OM, valueCreator: ReceiverSurfaceCapabilities.create, valueDefaultOrMaker: ReceiverSurfaceCapabilities.getDefault, packageName: const $pb.PackageName('playbridge'))
    ..aOM<ReceiverVolumeCapabilities>(3, _omitFieldNames ? '' : 'volume', subBuilder: ReceiverVolumeCapabilities.create)
    ..aOM<ReceiverHostInfo>(4, _omitFieldNames ? '' : 'host', subBuilder: ReceiverHostInfo.create)
    ..pPS(5, _omitFieldNames ? '' : 'companionHints')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ReceiverCapabilities clone() => ReceiverCapabilities()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ReceiverCapabilities copyWith(void Function(ReceiverCapabilities) updates) => super.copyWith((message) => updates(message as ReceiverCapabilities)) as ReceiverCapabilities;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static ReceiverCapabilities create() => ReceiverCapabilities._();
  @$core.override
  ReceiverCapabilities createEmptyInstance() => create();
  static $pb.PbList<ReceiverCapabilities> createRepeated() => $pb.PbList<ReceiverCapabilities>();
  @$core.pragma('dart2js:noInline')
  static ReceiverCapabilities getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<ReceiverCapabilities>(create);
  static ReceiverCapabilities? _defaultInstance;

  @$pb.TagNumber(1)
  $core.int get version => $_getIZ(0);
  @$pb.TagNumber(1)
  set version($core.int value) => $_setUnsignedInt32(0, value);
  @$pb.TagNumber(1)
  $core.bool hasVersion() => $_has(0);
  @$pb.TagNumber(1)
  void clearVersion() => $_clearField(1);

  /// Single authority; empty/absent engine operation sets are unsupported.
  @$pb.TagNumber(2)
  $pb.PbMap<$core.String, ReceiverSurfaceCapabilities> get surfaces => $_getMap(1);

  @$pb.TagNumber(3)
  ReceiverVolumeCapabilities get volume => $_getN(2);
  @$pb.TagNumber(3)
  set volume(ReceiverVolumeCapabilities value) => $_setField(3, value);
  @$pb.TagNumber(3)
  $core.bool hasVolume() => $_has(2);
  @$pb.TagNumber(3)
  void clearVolume() => $_clearField(3);
  @$pb.TagNumber(3)
  ReceiverVolumeCapabilities ensureVolume() => $_ensure(2);

  @$pb.TagNumber(4)
  ReceiverHostInfo get host => $_getN(3);
  @$pb.TagNumber(4)
  set host(ReceiverHostInfo value) => $_setField(4, value);
  @$pb.TagNumber(4)
  $core.bool hasHost() => $_has(3);
  @$pb.TagNumber(4)
  void clearHost() => $_clearField(4);
  @$pb.TagNumber(4)
  ReceiverHostInfo ensureHost() => $_ensure(3);

  @$pb.TagNumber(5)
  $pb.PbList<$core.String> get companionHints => $_getList(4);
}

class ReceiverSurfaceCapabilities extends $pb.GeneratedMessage {
  factory ReceiverSurfaceCapabilities({
    $core.Iterable<$core.MapEntry<$core.String, ReceiverEngineCapabilities>>? engines,
  }) {
    final result = create();
    if (engines != null) result.engines.addEntries(engines);
    return result;
  }

  ReceiverSurfaceCapabilities._();

  factory ReceiverSurfaceCapabilities.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory ReceiverSurfaceCapabilities.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'ReceiverSurfaceCapabilities', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..m<$core.String, ReceiverEngineCapabilities>(1, _omitFieldNames ? '' : 'engines', entryClassName: 'ReceiverSurfaceCapabilities.EnginesEntry', keyFieldType: $pb.PbFieldType.OS, valueFieldType: $pb.PbFieldType.OM, valueCreator: ReceiverEngineCapabilities.create, valueDefaultOrMaker: ReceiverEngineCapabilities.getDefault, packageName: const $pb.PackageName('playbridge'))
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ReceiverSurfaceCapabilities clone() => ReceiverSurfaceCapabilities()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ReceiverSurfaceCapabilities copyWith(void Function(ReceiverSurfaceCapabilities) updates) => super.copyWith((message) => updates(message as ReceiverSurfaceCapabilities)) as ReceiverSurfaceCapabilities;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static ReceiverSurfaceCapabilities create() => ReceiverSurfaceCapabilities._();
  @$core.override
  ReceiverSurfaceCapabilities createEmptyInstance() => create();
  static $pb.PbList<ReceiverSurfaceCapabilities> createRepeated() => $pb.PbList<ReceiverSurfaceCapabilities>();
  @$core.pragma('dart2js:noInline')
  static ReceiverSurfaceCapabilities getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<ReceiverSurfaceCapabilities>(create);
  static ReceiverSurfaceCapabilities? _defaultInstance;

  /// Presence means available. Active engine belongs to context/player_settings.
  @$pb.TagNumber(1)
  $pb.PbMap<$core.String, ReceiverEngineCapabilities> get engines => $_getMap(0);
}

class ReceiverEngineCapabilities extends $pb.GeneratedMessage {
  factory ReceiverEngineCapabilities({
    $core.Iterable<$core.String>? transport,
    $core.Iterable<$core.String>? keys,
    $core.Iterable<$core.String>? pointer,
    $core.Iterable<$core.String>? imageTransforms,
    $core.Iterable<$core.String>? text,
    $core.Iterable<$core.String>? settings,
    $core.Iterable<$core.String>? tracks,
    $core.Iterable<$core.String>? queue,
    $core.Iterable<$core.String>? browser,
    $core.Iterable<$core.String>? reporting,
  }) {
    final result = create();
    if (transport != null) result.transport.addAll(transport);
    if (keys != null) result.keys.addAll(keys);
    if (pointer != null) result.pointer.addAll(pointer);
    if (imageTransforms != null) result.imageTransforms.addAll(imageTransforms);
    if (text != null) result.text.addAll(text);
    if (settings != null) result.settings.addAll(settings);
    if (tracks != null) result.tracks.addAll(tracks);
    if (queue != null) result.queue.addAll(queue);
    if (browser != null) result.browser.addAll(browser);
    if (reporting != null) result.reporting.addAll(reporting);
    return result;
  }

  ReceiverEngineCapabilities._();

  factory ReceiverEngineCapabilities.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory ReceiverEngineCapabilities.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'ReceiverEngineCapabilities', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..pPS(1, _omitFieldNames ? '' : 'transport')
    ..pPS(2, _omitFieldNames ? '' : 'keys')
    ..pPS(3, _omitFieldNames ? '' : 'pointer')
    ..pPS(4, _omitFieldNames ? '' : 'imageTransforms')
    ..pPS(5, _omitFieldNames ? '' : 'text')
    ..pPS(6, _omitFieldNames ? '' : 'settings')
    ..pPS(7, _omitFieldNames ? '' : 'tracks')
    ..pPS(8, _omitFieldNames ? '' : 'queue')
    ..pPS(9, _omitFieldNames ? '' : 'browser')
    ..pPS(10, _omitFieldNames ? '' : 'reporting')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ReceiverEngineCapabilities clone() => ReceiverEngineCapabilities()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ReceiverEngineCapabilities copyWith(void Function(ReceiverEngineCapabilities) updates) => super.copyWith((message) => updates(message as ReceiverEngineCapabilities)) as ReceiverEngineCapabilities;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static ReceiverEngineCapabilities create() => ReceiverEngineCapabilities._();
  @$core.override
  ReceiverEngineCapabilities createEmptyInstance() => create();
  static $pb.PbList<ReceiverEngineCapabilities> createRepeated() => $pb.PbList<ReceiverEngineCapabilities>();
  @$core.pragma('dart2js:noInline')
  static ReceiverEngineCapabilities getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<ReceiverEngineCapabilities>(create);
  static ReceiverEngineCapabilities? _defaultInstance;

  @$pb.TagNumber(1)
  $pb.PbList<$core.String> get transport => $_getList(0);

  @$pb.TagNumber(2)
  $pb.PbList<$core.String> get keys => $_getList(1);

  @$pb.TagNumber(3)
  $pb.PbList<$core.String> get pointer => $_getList(2);

  @$pb.TagNumber(4)
  $pb.PbList<$core.String> get imageTransforms => $_getList(3);

  @$pb.TagNumber(5)
  $pb.PbList<$core.String> get text => $_getList(4);

  @$pb.TagNumber(6)
  $pb.PbList<$core.String> get settings => $_getList(5);

  @$pb.TagNumber(7)
  $pb.PbList<$core.String> get tracks => $_getList(6);

  @$pb.TagNumber(8)
  $pb.PbList<$core.String> get queue => $_getList(7);

  @$pb.TagNumber(9)
  $pb.PbList<$core.String> get browser => $_getList(8);

  @$pb.TagNumber(10)
  $pb.PbList<$core.String> get reporting => $_getList(9);
}

class ReceiverVolumeCapabilities extends $pb.GeneratedMessage {
  factory ReceiverVolumeCapabilities({
    $core.String? scope,
    $core.Iterable<$core.String>? operations,
    $core.double? step,
    $core.String? fallbackScope,
  }) {
    final result = create();
    if (scope != null) result.scope = scope;
    if (operations != null) result.operations.addAll(operations);
    if (step != null) result.step = step;
    if (fallbackScope != null) result.fallbackScope = fallbackScope;
    return result;
  }

  ReceiverVolumeCapabilities._();

  factory ReceiverVolumeCapabilities.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory ReceiverVolumeCapabilities.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'ReceiverVolumeCapabilities', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'scope')
    ..pPS(2, _omitFieldNames ? '' : 'operations')
    ..a<$core.double>(3, _omitFieldNames ? '' : 'step', $pb.PbFieldType.OD)
    ..aOS(4, _omitFieldNames ? '' : 'fallbackScope')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ReceiverVolumeCapabilities clone() => ReceiverVolumeCapabilities()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ReceiverVolumeCapabilities copyWith(void Function(ReceiverVolumeCapabilities) updates) => super.copyWith((message) => updates(message as ReceiverVolumeCapabilities)) as ReceiverVolumeCapabilities;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static ReceiverVolumeCapabilities create() => ReceiverVolumeCapabilities._();
  @$core.override
  ReceiverVolumeCapabilities createEmptyInstance() => create();
  static $pb.PbList<ReceiverVolumeCapabilities> createRepeated() => $pb.PbList<ReceiverVolumeCapabilities>();
  @$core.pragma('dart2js:noInline')
  static ReceiverVolumeCapabilities getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<ReceiverVolumeCapabilities>(create);
  static ReceiverVolumeCapabilities? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get scope => $_getSZ(0);
  @$pb.TagNumber(1)
  set scope($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasScope() => $_has(0);
  @$pb.TagNumber(1)
  void clearScope() => $_clearField(1);

  @$pb.TagNumber(2)
  $pb.PbList<$core.String> get operations => $_getList(1);

  @$pb.TagNumber(3)
  $core.double get step => $_getN(2);
  @$pb.TagNumber(3)
  set step($core.double value) => $_setDouble(2, value);
  @$pb.TagNumber(3)
  $core.bool hasStep() => $_has(2);
  @$pb.TagNumber(3)
  void clearStep() => $_clearField(3);

  @$pb.TagNumber(4)
  $core.String get fallbackScope => $_getSZ(3);
  @$pb.TagNumber(4)
  set fallbackScope($core.String value) => $_setString(3, value);
  @$pb.TagNumber(4)
  $core.bool hasFallbackScope() => $_has(3);
  @$pb.TagNumber(4)
  void clearFallbackScope() => $_clearField(4);
}

class ReceiverHostInfo extends $pb.GeneratedMessage {
  factory ReceiverHostInfo({
    $core.String? osFamily,
    $core.String? deviceClass,
  }) {
    final result = create();
    if (osFamily != null) result.osFamily = osFamily;
    if (deviceClass != null) result.deviceClass = deviceClass;
    return result;
  }

  ReceiverHostInfo._();

  factory ReceiverHostInfo.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory ReceiverHostInfo.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'ReceiverHostInfo', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'osFamily')
    ..aOS(2, _omitFieldNames ? '' : 'deviceClass')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ReceiverHostInfo clone() => ReceiverHostInfo()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ReceiverHostInfo copyWith(void Function(ReceiverHostInfo) updates) => super.copyWith((message) => updates(message as ReceiverHostInfo)) as ReceiverHostInfo;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static ReceiverHostInfo create() => ReceiverHostInfo._();
  @$core.override
  ReceiverHostInfo createEmptyInstance() => create();
  static $pb.PbList<ReceiverHostInfo> createRepeated() => $pb.PbList<ReceiverHostInfo>();
  @$core.pragma('dart2js:noInline')
  static ReceiverHostInfo getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<ReceiverHostInfo>(create);
  static ReceiverHostInfo? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get osFamily => $_getSZ(0);
  @$pb.TagNumber(1)
  set osFamily($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasOsFamily() => $_has(0);
  @$pb.TagNumber(1)
  void clearOsFamily() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get deviceClass => $_getSZ(1);
  @$pb.TagNumber(2)
  set deviceClass($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasDeviceClass() => $_has(1);
  @$pb.TagNumber(2)
  void clearDeviceClass() => $_clearField(2);
}

/// Native command/volume payload. Scoped absolute level is normalized 0..1.
class VolumePayload extends $pb.GeneratedMessage {
  factory VolumePayload({
    $core.String? scope,
    $core.double? level,
  }) {
    final result = create();
    if (scope != null) result.scope = scope;
    if (level != null) result.level = level;
    return result;
  }

  VolumePayload._();

  factory VolumePayload.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory VolumePayload.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'VolumePayload', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'scope')
    ..a<$core.double>(2, _omitFieldNames ? '' : 'level', $pb.PbFieldType.OD)
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  VolumePayload clone() => VolumePayload()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  VolumePayload copyWith(void Function(VolumePayload) updates) => super.copyWith((message) => updates(message as VolumePayload)) as VolumePayload;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static VolumePayload create() => VolumePayload._();
  @$core.override
  VolumePayload createEmptyInstance() => create();
  static $pb.PbList<VolumePayload> createRepeated() => $pb.PbList<VolumePayload>();
  @$core.pragma('dart2js:noInline')
  static VolumePayload getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<VolumePayload>(create);
  static VolumePayload? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get scope => $_getSZ(0);
  @$pb.TagNumber(1)
  set scope($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasScope() => $_has(0);
  @$pb.TagNumber(1)
  void clearScope() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.double get level => $_getN(1);
  @$pb.TagNumber(2)
  set level($core.double value) => $_setDouble(1, value);
  @$pb.TagNumber(2)
  $core.bool hasLevel() => $_has(1);
  @$pb.TagNumber(2)
  void clearLevel() => $_clearField(2);
}

class VolumeStateMessage extends $pb.GeneratedMessage {
  factory VolumeStateMessage({
    $core.String? type,
    $core.String? scope,
    $core.double? level,
    $core.bool? muted,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (scope != null) result.scope = scope;
    if (level != null) result.level = level;
    if (muted != null) result.muted = muted;
    return result;
  }

  VolumeStateMessage._();

  factory VolumeStateMessage.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory VolumeStateMessage.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'VolumeStateMessage', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'type')
    ..aOS(2, _omitFieldNames ? '' : 'scope')
    ..a<$core.double>(3, _omitFieldNames ? '' : 'level', $pb.PbFieldType.OD)
    ..aOB(4, _omitFieldNames ? '' : 'muted')
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  VolumeStateMessage clone() => VolumeStateMessage()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  VolumeStateMessage copyWith(void Function(VolumeStateMessage) updates) => super.copyWith((message) => updates(message as VolumeStateMessage)) as VolumeStateMessage;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static VolumeStateMessage create() => VolumeStateMessage._();
  @$core.override
  VolumeStateMessage createEmptyInstance() => create();
  static $pb.PbList<VolumeStateMessage> createRepeated() => $pb.PbList<VolumeStateMessage>();
  @$core.pragma('dart2js:noInline')
  static VolumeStateMessage getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<VolumeStateMessage>(create);
  static VolumeStateMessage? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get type => $_getSZ(0);
  @$pb.TagNumber(1)
  set type($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasType() => $_has(0);
  @$pb.TagNumber(1)
  void clearType() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get scope => $_getSZ(1);
  @$pb.TagNumber(2)
  set scope($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasScope() => $_has(1);
  @$pb.TagNumber(2)
  void clearScope() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.double get level => $_getN(2);
  @$pb.TagNumber(3)
  set level($core.double value) => $_setDouble(2, value);
  @$pb.TagNumber(3)
  $core.bool hasLevel() => $_has(2);
  @$pb.TagNumber(3)
  void clearLevel() => $_clearField(3);

  @$pb.TagNumber(4)
  $core.bool get muted => $_getBF(3);
  @$pb.TagNumber(4)
  set muted($core.bool value) => $_setBool(3, value);
  @$pb.TagNumber(4)
  $core.bool hasMuted() => $_has(3);
  @$pb.TagNumber(4)
  void clearMuted() => $_clearField(4);
}

/// Native opt-in push, independent of hosted-browser capabilities. JSON adapters
/// MUST emit revision as a number (1..2^53-1), not protobuf JSON's uint64 string.
/// Select capabilities.version from raw JSON before decoding a future shape.
/// Opt in with command/receiver_capabilities_subscribe, payload {}, only after
/// receiver_capabilities_v1 appears in authenticated receiver features.
class ReceiverCapabilitiesFrame extends $pb.GeneratedMessage {
  factory ReceiverCapabilitiesFrame({
    $core.String? type,
    $fixnum.Int64? revision,
    ReceiverCapabilities? capabilities,
  }) {
    final result = create();
    if (type != null) result.type = type;
    if (revision != null) result.revision = revision;
    if (capabilities != null) result.capabilities = capabilities;
    return result;
  }

  ReceiverCapabilitiesFrame._();

  factory ReceiverCapabilitiesFrame.fromBuffer($core.List<$core.int> data, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromBuffer(data, registry);
  factory ReceiverCapabilitiesFrame.fromJson($core.String json, [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) => create()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(_omitMessageNames ? '' : 'ReceiverCapabilitiesFrame', package: const $pb.PackageName(_omitMessageNames ? '' : 'playbridge'), createEmptyInstance: create)
    ..aOS(1, _omitFieldNames ? '' : 'type')
    ..a<$fixnum.Int64>(2, _omitFieldNames ? '' : 'revision', $pb.PbFieldType.OU6, defaultOrMaker: $fixnum.Int64.ZERO)
    ..aOM<ReceiverCapabilities>(3, _omitFieldNames ? '' : 'capabilities', subBuilder: ReceiverCapabilities.create)
    ..hasRequiredFields = false
  ;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ReceiverCapabilitiesFrame clone() => ReceiverCapabilitiesFrame()..mergeFromMessage(this);
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ReceiverCapabilitiesFrame copyWith(void Function(ReceiverCapabilitiesFrame) updates) => super.copyWith((message) => updates(message as ReceiverCapabilitiesFrame)) as ReceiverCapabilitiesFrame;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  static ReceiverCapabilitiesFrame create() => ReceiverCapabilitiesFrame._();
  @$core.override
  ReceiverCapabilitiesFrame createEmptyInstance() => create();
  static $pb.PbList<ReceiverCapabilitiesFrame> createRepeated() => $pb.PbList<ReceiverCapabilitiesFrame>();
  @$core.pragma('dart2js:noInline')
  static ReceiverCapabilitiesFrame getDefault() => _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<ReceiverCapabilitiesFrame>(create);
  static ReceiverCapabilitiesFrame? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get type => $_getSZ(0);
  @$pb.TagNumber(1)
  set type($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasType() => $_has(0);
  @$pb.TagNumber(1)
  void clearType() => $_clearField(1);

  @$pb.TagNumber(2)
  $fixnum.Int64 get revision => $_getI64(1);
  @$pb.TagNumber(2)
  set revision($fixnum.Int64 value) => $_setInt64(1, value);
  @$pb.TagNumber(2)
  $core.bool hasRevision() => $_has(1);
  @$pb.TagNumber(2)
  void clearRevision() => $_clearField(2);

  @$pb.TagNumber(3)
  ReceiverCapabilities get capabilities => $_getN(2);
  @$pb.TagNumber(3)
  set capabilities(ReceiverCapabilities value) => $_setField(3, value);
  @$pb.TagNumber(3)
  $core.bool hasCapabilities() => $_has(2);
  @$pb.TagNumber(3)
  void clearCapabilities() => $_clearField(3);
  @$pb.TagNumber(3)
  ReceiverCapabilities ensureCapabilities() => $_ensure(2);
}


const $core.bool _omitFieldNames = $core.bool.fromEnvironment('protobuf.omit_field_names');
const $core.bool _omitMessageNames = $core.bool.fromEnvironment('protobuf.omit_message_names');
