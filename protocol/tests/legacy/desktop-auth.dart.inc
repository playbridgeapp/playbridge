  void _handleAuthResponse(Map<dynamic, dynamic> obj) {
    if (obj['success'] != true) {
      _setState(SenderConnectionState.authFailed);
      _close();
      return;
    }
    // SEC-005: legacy echoed tokens in auth_response are ignored.
    _setState(SenderConnectionState.connected);
  }

