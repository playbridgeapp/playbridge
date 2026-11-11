/// Protocol feature a receiver advertises when it can join a watch party.
const watchTogetherFeature = 'watch_together_v1';

/// The answer to `watch_together_join`: the receiver's room member ID and public
/// keys, so the sender can count it as its own device and hand it host duties.
class WatchTogetherJoinReply {
  const WatchTogetherJoinReply.joined({
    required String this.member,
    required String this.boxKey,
    required String this.signKey,
  })  : ok = true,
        error = null;

  const WatchTogetherJoinReply.failed(String this.error)
      : ok = false,
        member = null,
        boxKey = null,
        signKey = null;

  final bool ok;
  final String? member;
  final String? boxKey;
  final String? signKey;

  /// Known: invalid_invite, unavailable.
  final String? error;

  /// Parses a `watch_together_joined` frame; null for anything else.
  static WatchTogetherJoinReply? fromJson(Object? json) {
    if (json is! Map || json['type'] != 'watch_together_joined') return null;
    final member = json['member'];
    final boxKey = json['box_pk'];
    final signKey = json['sign_pk'];
    if (json['ok'] == true &&
        member is String &&
        boxKey is String &&
        signKey is String) {
      return WatchTogetherJoinReply.joined(
          member: member, boxKey: boxKey, signKey: signKey);
    }
    final error = json['error'];
    return WatchTogetherJoinReply.failed(
        error is String && error.isNotEmpty ? error : 'unavailable');
  }

  Map<String, Object?> toJson() => {
        'type': 'watch_together_joined',
        'ok': ok,
        if (member != null) 'member': member,
        if (boxKey != null) 'box_pk': boxKey,
        if (signKey != null) 'sign_pk': signKey,
        if (error != null) 'error': error,
      };
}
