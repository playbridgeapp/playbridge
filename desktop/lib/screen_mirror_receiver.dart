import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';

import 'protocol.dart';

typedef ScreenMirrorSender = void Function(
  int connectionId,
  Map<String, Object?> message,
);

/// Receives an Android screen-mirror WebRTC session over the existing
/// PlayBridge WebSocket signaling channel.
class ScreenMirrorReceiver extends ChangeNotifier {
  ScreenMirrorReceiver({required ScreenMirrorSender send}) : _send = send;

  static const int _maxPendingCandidates = 256;

  final ScreenMirrorSender _send;
  Future<void> _operations = Future.value();
  RTCPeerConnection? _peer;
  RTCVideoRenderer? _renderer;
  final List<RTCIceCandidate> _pendingCandidates = [];
  int _generation = 0;
  int? _connectionId;
  String? _sessionId;
  bool _remoteDescriptionSet = false;
  bool _connected = false;
  String? _error;
  bool _disposed = false;

  bool get isActive => _sessionId != null;
  bool get isConnected => _connected;
  String? get sessionId => _sessionId;
  String? get error => _error;
  RTCVideoRenderer? get renderer => _renderer;

  void start(ScreenMirrorStartCmd command, int connectionId) {
    _enqueue(() => _start(command.sessionId, connectionId));
  }

  void applyOffer(ScreenMirrorOfferCmd command, int connectionId) {
    _enqueue(() async {
      if (!_owns(command.sessionId, connectionId)) return;
      final peer = _peer;
      if (peer == null) return;
      final generation = _generation;
      try {
        await peer.setRemoteDescription(
          RTCSessionDescription(command.sdp, 'offer'),
        );
        if (!_isCurrent(generation, peer)) return;
        _remoteDescriptionSet = true;
        for (final candidate in List<RTCIceCandidate>.of(_pendingCandidates)) {
          await peer.addCandidate(candidate);
          if (!_isCurrent(generation, peer)) return;
        }
        _pendingCandidates.clear();
        final answer = await peer.createAnswer();
        if (!_isCurrent(generation, peer)) return;
        await peer.setLocalDescription(answer);
        if (!_isCurrent(generation, peer)) return;
        final sdp = answer.sdp;
        if (sdp == null || sdp.isEmpty) {
          await _fail('answer_failed');
          return;
        }
        _send(connectionId, {
          'type': 'screen_mirror_answer',
          'sessionId': command.sessionId,
          'sdp': sdp,
        });
      } catch (error, stackTrace) {
        debugPrint('[screen-mirror] offer failed: $error\n$stackTrace');
        if (_isCurrent(generation, peer)) await _fail('offer_rejected');
      }
    });
  }

  void addCandidate(ScreenMirrorCandidateCmd command, int connectionId) {
    _enqueue(() async {
      if (!_owns(command.sessionId, connectionId)) return;
      final peer = _peer;
      if (peer == null) return;
      final candidate = RTCIceCandidate(
        command.candidate,
        command.sdpMid,
        command.sdpMLineIndex,
      );
      if (_remoteDescriptionSet) {
        try {
          await peer.addCandidate(candidate);
        } catch (error) {
          debugPrint('[screen-mirror] candidate rejected: $error');
        }
      } else if (_pendingCandidates.length < _maxPendingCandidates) {
        _pendingCandidates.add(candidate);
      }
    });
  }

  void stop(ScreenMirrorStopCmd command, int connectionId) {
    _enqueue(() async {
      if (!_owns(command.sessionId, connectionId)) return;
      await _stopInternal(
          notifySender: false, reason: command.reason ?? 'stopped');
    });
  }

  void connectionClosed(int connectionId) {
    _enqueue(() async {
      if (_connectionId != connectionId) return;
      await _stopInternal(
        notifySender: false,
        reason: 'sender_disconnected',
      );
    });
  }

  Future<void> stopForReplacement({
    String reason = 'replaced',
    bool notifySender = true,
  }) {
    final completer = Completer<void>();
    _enqueue(() async {
      try {
        await _stopInternal(notifySender: notifySender, reason: reason);
      } finally {
        completer.complete();
      }
    });
    return completer.future;
  }

  Future<void> disposeReceiver() {
    final completer = Completer<void>();
    _enqueue(() async {
      try {
        await _stopInternal(notifySender: false, reason: 'receiver_stopped');
      } finally {
        _disposed = true;
        super.dispose();
        completer.complete();
      }
    });
    return completer.future;
  }

  void _enqueue(Future<void> Function() operation) {
    if (_disposed) return;
    _operations = _operations.then((_) => operation()).catchError(
      (Object error, StackTrace stackTrace) {
        debugPrint('[screen-mirror] operation failed: $error\n$stackTrace');
      },
    );
  }

  Future<void> _start(String sessionId, int connectionId) async {
    if (_sessionId != null) {
      await _stopInternal(notifySender: true, reason: 'replaced');
    }
    final generation = ++_generation;
    _sessionId = sessionId;
    _connectionId = connectionId;
    _connected = false;
    _error = null;
    _remoteDescriptionSet = false;
    _pendingCandidates.clear();
    notifyListeners();

    RTCVideoRenderer? renderer;
    RTCPeerConnection? peer;
    try {
      renderer = RTCVideoRenderer();
      await renderer.initialize();
      if (!_isGenerationCurrent(generation, sessionId, connectionId)) {
        await renderer.dispose();
        return;
      }
      peer = await createPeerConnection({
        'iceServers': const <Map<String, Object?>>[],
        'sdpSemantics': 'unified-plan',
        'bundlePolicy': 'max-bundle',
        'rtcpMuxPolicy': 'require',
      });
      if (!_isGenerationCurrent(generation, sessionId, connectionId)) {
        await peer.close();
        await peer.dispose();
        await renderer.dispose();
        return;
      }
      _renderer = renderer;
      _peer = peer;
      _configurePeer(peer, renderer, generation, sessionId, connectionId);
      notifyListeners();
      _send(connectionId, {
        'type': 'screen_mirror_ready',
        'sessionId': sessionId,
      });
      debugPrint('[screen-mirror] ready session=$sessionId');
    } catch (error, stackTrace) {
      debugPrint('[screen-mirror] initialization failed: $error\n$stackTrace');
      if (peer != null) {
        await peer.close();
        await peer.dispose();
      }
      if (renderer != null && !identical(renderer, _renderer)) {
        await renderer.dispose();
      }
      if (_isGenerationCurrent(generation, sessionId, connectionId)) {
        await _fail('receiver_initialization_failed');
      }
    }
  }

  void _configurePeer(
    RTCPeerConnection peer,
    RTCVideoRenderer renderer,
    int generation,
    String sessionId,
    int connectionId,
  ) {
    peer.onIceCandidate = (candidate) {
      if (!_isCurrent(generation, peer)) return;
      final value = candidate.candidate;
      final index = candidate.sdpMLineIndex;
      if (value == null || value.isEmpty || index == null) return;
      _send(connectionId, {
        'type': 'screen_mirror_candidate',
        'sessionId': sessionId,
        if (candidate.sdpMid != null) 'sdpMid': candidate.sdpMid,
        'sdpMLineIndex': index,
        'candidate': value,
      });
    };
    peer.onTrack = (event) {
      if (!_isCurrent(generation, peer)) return;
      event.track.enabled = true;
      if (event.streams.isNotEmpty) {
        renderer.srcObject = event.streams.first;
        notifyListeners();
      }
      debugPrint('[screen-mirror] remote ${event.track.kind} track received');
    };
    peer.onConnectionState = (state) {
      if (!_isCurrent(generation, peer)) return;
      switch (state) {
        case RTCPeerConnectionState.RTCPeerConnectionStateConnected:
          if (_connected) return;
          _connected = true;
          notifyListeners();
          _send(connectionId, {
            'type': 'screen_mirror_event',
            'sessionId': sessionId,
            'state': 'connected',
          });
          debugPrint('[screen-mirror] connected session=$sessionId');
        case RTCPeerConnectionState.RTCPeerConnectionStateFailed:
        case RTCPeerConnectionState.RTCPeerConnectionStateClosed:
          _enqueue(() => _fail('connection_failed'));
        case RTCPeerConnectionState.RTCPeerConnectionStateDisconnected:
        case RTCPeerConnectionState.RTCPeerConnectionStateNew:
        case RTCPeerConnectionState.RTCPeerConnectionStateConnecting:
          break;
      }
    };
  }

  bool _owns(String sessionId, int connectionId) =>
      _sessionId == sessionId && _connectionId == connectionId;

  bool _isCurrent(int generation, RTCPeerConnection peer) =>
      generation == _generation && identical(peer, _peer);

  bool _isGenerationCurrent(
    int generation,
    String sessionId,
    int connectionId,
  ) =>
      generation == _generation &&
      _sessionId == sessionId &&
      _connectionId == connectionId;

  Future<void> _fail(String reason) async {
    final connectionId = _connectionId;
    final sessionId = _sessionId;
    if (connectionId != null && sessionId != null) {
      _send(connectionId, {
        'type': 'screen_mirror_event',
        'sessionId': sessionId,
        'state': 'failed',
        'reason': reason,
      });
    }
    _error = reason;
    await _stopInternal(notifySender: false, reason: reason, keepError: true);
  }

  Future<void> _stopInternal({
    required bool notifySender,
    required String reason,
    bool keepError = false,
  }) async {
    final connectionId = _connectionId;
    final sessionId = _sessionId;
    if (notifySender && connectionId != null && sessionId != null) {
      _send(connectionId, {
        'type': 'screen_mirror_event',
        'sessionId': sessionId,
        'state': 'stopped',
        'reason': reason,
      });
    }
    _generation++;
    _sessionId = null;
    _connectionId = null;
    _connected = false;
    _remoteDescriptionSet = false;
    _pendingCandidates.clear();
    if (!keepError) _error = null;
    final peer = _peer;
    final renderer = _renderer;
    _peer = null;
    _renderer = null;
    notifyListeners();
    if (renderer != null) renderer.srcObject = null;
    if (peer != null) {
      await peer.close();
      await peer.dispose();
    }
    if (renderer != null) await renderer.dispose();
    debugPrint('[screen-mirror] stopped reason=$reason');
  }
}
