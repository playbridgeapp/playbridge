import 'dart:async';

import 'package:flutter/foundation.dart';

import 'player_controller.dart';

enum StillWatchingPhase { inactive, counting, prompting, stopping }

/// Tracks cumulative active playback and asks the viewer to confirm they are
/// still present before leaving the receiver in its normal idle state.
class StillWatchingController extends ChangeNotifier {
  StillWatchingController({
    required PlayerController player,
    bool enabled = true,
    Duration threshold = const Duration(minutes: 90),
    Duration gracePeriod = const Duration(seconds: 300),
    DateTime Function()? now,
    VoidCallback? onAutoStop,
  })  : _player = player,
        _enabled = enabled,
        _threshold = threshold,
        _gracePeriod = gracePeriod,
        _onAutoStop = onAutoStop,
        _now = now ?? DateTime.now {
    _player.addListener(_onPlayerChanged);
    _onPlayerChanged();
  }

  final PlayerController _player;
  final DateTime Function() _now;
  final VoidCallback? _onAutoStop;
  Duration _gracePeriod;

  bool _enabled;
  Duration _threshold;
  Duration _activeElapsed = Duration.zero;
  DateTime? _playingSince;
  Timer? _thresholdTimer;
  Timer? _countdownTimer;
  Future<void>? _featurePause;
  Future<void>? _staleFeaturePause;
  int _stalePauseRecoveryOperation = 0;
  int _generation = 0;
  int _operation = 0;
  bool _featurePaused = false;
  StillWatchingPhase _phase = StillWatchingPhase.inactive;
  int _remainingSeconds = 0;

  bool get enabled => _enabled;
  Duration get threshold => _threshold;
  Duration get activeElapsed => _elapsedNow();
  StillWatchingPhase get phase => _phase;
  bool get isPrompting => _phase == StillWatchingPhase.prompting;
  int get remainingSeconds => _remainingSeconds;

  /// Treats an explicit viewer action as proof of presence and grants a fresh
  /// interval. Playback position ticks must never call this method.
  void recordUserActivity() {
    if (!_enabled || isPrompting || _phase == StillWatchingPhase.stopping) {
      return;
    }
    // Besides granting a fresh interval, an explicit viewer action owns any
    // playback state that follows it. In particular, a late pause from a
    // replaced reminder must not resume playback after the viewer pauses it.
    _operation++;
    _activeElapsed = Duration.zero;
    _playingSince = null;
    _syncCounting();
  }

  /// Ends the old reminder session before a new cast or playlist replaces it.
  void resetForNewMedia() {
    final pendingPause = _featurePause ?? _staleFeaturePause;
    _featurePause = null;
    _resetSession();
    if (pendingPause != null) {
      _stalePauseRecoveryOperation = _operation;
      if (!identical(_staleFeaturePause, pendingPause)) {
        _staleFeaturePause = pendingPause;
        unawaited(_recoverFromStalePause(pendingPause));
      }
    }
  }

  Future<void> _recoverFromStalePause(Future<void> pause) async {
    try {
      await pause;
    } catch (error) {
      debugPrint('[still-watching] stale pause failed: $error');
      if (identical(_staleFeaturePause, pause)) {
        _staleFeaturePause = null;
      }
      return;
    }
    if (!identical(_staleFeaturePause, pause)) return;
    final recoveryOperation = _stalePauseRecoveryOperation;
    _staleFeaturePause = null;
    if (recoveryOperation == _operation &&
        _player.queue.isNotEmpty &&
        _player.state == 'paused') {
      await _player.resume();
    }
  }

  void updateSettings({
    required bool enabled,
    required Duration threshold,
    Duration? gracePeriod,
  }) {
    final nextGracePeriod = gracePeriod ?? _gracePeriod;
    if (_enabled == enabled &&
        _threshold == threshold &&
        _gracePeriod == nextGracePeriod) {
      return;
    }
    _accrueActiveTime();
    _enabled = enabled;
    _threshold = threshold;
    _gracePeriod = nextGracePeriod;

    if (!enabled) {
      final shouldResume = isPrompting && _featurePaused;
      final operation = ++_operation;
      _cancelTimers();
      _phase = StillWatchingPhase.inactive;
      _featurePaused = false;
      _activeElapsed = Duration.zero;
      _playingSince = null;
      notifyListeners();
      if (shouldResume) unawaited(_resumeAfterFeaturePause(operation));
      return;
    }

    if (isPrompting) {
      notifyListeners();
      return;
    }
    _syncCounting();
  }

  void _onPlayerChanged() {
    if (_player.state == 'idle' || _player.queue.isEmpty) {
      _resetSession();
      return;
    }

    // Play commands from media keys and connected phones go straight to the
    // player. While the prompt is open, treat that transition as Continue.
    if (isPrompting && _player.state == 'playing') {
      unawaited(_continueFromAlreadyPlaying());
      return;
    }
    if (!_enabled || isPrompting || _phase == StillWatchingPhase.stopping) {
      _accrueActiveTime();
      return;
    }
    _syncCounting();
  }

  void _syncCounting() {
    _thresholdTimer?.cancel();
    _thresholdTimer = null;
    _accrueActiveTime();
    if (!_enabled || _player.queue.isEmpty) return;

    if (_player.state != 'playing') {
      _phase = StillWatchingPhase.inactive;
      notifyListeners();
      return;
    }
    _playingSince = _now();
    final remaining = _threshold - _activeElapsed;
    if (remaining <= Duration.zero) {
      scheduleMicrotask(_showPrompt);
      return;
    }
    _phase = StillWatchingPhase.counting;
    final generation = _generation;
    _thresholdTimer = Timer(remaining, () {
      if (generation == _generation) _showPrompt();
    });
    notifyListeners();
  }

  void _accrueActiveTime() {
    final since = _playingSince;
    if (since == null) return;
    final elapsed = _now().difference(since);
    if (!elapsed.isNegative) _activeElapsed += elapsed;
    _playingSince = null;
  }

  Duration _elapsedNow() {
    final since = _playingSince;
    if (since == null) return _activeElapsed;
    final elapsed = _now().difference(since);
    return elapsed.isNegative ? _activeElapsed : _activeElapsed + elapsed;
  }

  void _showPrompt() {
    if (!_enabled || _player.state != 'playing' || _player.queue.isEmpty) {
      _syncCounting();
      return;
    }
    _accrueActiveTime();
    _thresholdTimer?.cancel();
    _phase = StillWatchingPhase.prompting;
    _featurePaused = true;
    _remainingSeconds = _gracePeriod.inSeconds;
    notifyListeners();
    _featurePause = _player.pause();

    final generation = _generation;
    _countdownTimer?.cancel();
    _countdownTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (generation != _generation || !isPrompting) {
        timer.cancel();
        return;
      }
      _remainingSeconds--;
      if (_remainingSeconds <= 0) {
        timer.cancel();
        unawaited(stopWatching());
      } else {
        notifyListeners();
      }
    });
  }

  Future<void> continueWatching() async {
    if (!isPrompting) return;
    final operation = ++_operation;
    _countdownTimer?.cancel();
    _featurePaused = false;
    _activeElapsed = Duration.zero;
    notifyListeners();
    await _awaitFeaturePause();
    if (operation != _operation || !isPrompting) {
      return;
    }
    _phase = StillWatchingPhase.counting;
    await _player.resume();
    _syncCounting();
  }

  Future<void> _continueFromAlreadyPlaying() async {
    final operation = ++_operation;
    _countdownTimer?.cancel();
    _featurePaused = false;
    _activeElapsed = Duration.zero;
    await _awaitFeaturePause();
    if (operation != _operation || !isPrompting) {
      return;
    }
    _phase = StillWatchingPhase.counting;
    await _player.resume();
    _syncCounting();
  }

  Future<void> stopWatching() async {
    if (!isPrompting) return;
    final operation = ++_operation;
    _countdownTimer?.cancel();
    _featurePaused = false;
    _phase = StillWatchingPhase.stopping;
    notifyListeners();
    await _awaitFeaturePause();
    if (operation != _operation || _phase != StillWatchingPhase.stopping) {
      return;
    }
    await _player.stop();
    _onAutoStop?.call();
  }

  Future<void> _resumeAfterFeaturePause(int operation) async {
    await _awaitFeaturePause();
    if (operation == _operation && !_enabled && _player.queue.isNotEmpty) {
      await _player.resume();
    }
  }

  Future<void> _awaitFeaturePause() async {
    final pause = _featurePause;
    if (pause == null) return;
    try {
      await pause;
    } catch (error) {
      debugPrint('[still-watching] pause failed: $error');
    } finally {
      if (identical(_featurePause, pause)) _featurePause = null;
    }
  }

  void _resetSession() {
    if (_phase == StillWatchingPhase.inactive &&
        _activeElapsed == Duration.zero &&
        _playingSince == null) {
      return;
    }
    _generation++;
    _operation++;
    _cancelTimers();
    _activeElapsed = Duration.zero;
    _playingSince = null;
    _featurePaused = false;
    _remainingSeconds = 0;
    _phase = StillWatchingPhase.inactive;
    notifyListeners();
  }

  void _cancelTimers() {
    _thresholdTimer?.cancel();
    _thresholdTimer = null;
    _countdownTimer?.cancel();
    _countdownTimer = null;
  }

  @override
  void dispose() {
    _generation++;
    _operation++;
    _cancelTimers();
    _featurePause = null;
    _staleFeaturePause = null;
    _player.removeListener(_onPlayerChanged);
    super.dispose();
  }
}
