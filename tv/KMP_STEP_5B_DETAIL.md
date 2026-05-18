# KMP Migration — Step 5b Detailed Implementation Plan

This document outlines the specific refactoring steps to complete **Step 5b: Load-bearing VM**. The goal is to move all playback control logic from the Android Activities (Exo, VLC, MPV) into the shared `PlayerViewModel`.

## 1. Unified Intent Handling
Currently, `ExoPlayerActivity` parses intents and calls `playVideo()` or `resolveStreamsAndPreBuffer()`.
- [ ] **Refactor:** Create a `handleIntent(intent)` method that extracts the payload and forwards it immediately to:
    - `viewModel.onPayload(...)` for direct URLs.
    - `viewModel.onContentPayload(...)` for Stremio metadata.
    - `viewModel.onPlaylistPayload(...)` for full M3U/IPTV lists.
- [ ] **Delete:** Remove `playlistItems`, `playlistIndex`, and `seriesNavigator` properties from the Activity; use the VM's versions.

## 2. Navigation Delegation
The Activities currently manage their own "Auto-advance" logic (`playNextInPlaylist`).
- [ ] **Refactor:** Replace `playNextInPlaylist()` calls with `viewModel.next()`.
- [ ] **Refactor:** Replace `playPreviousInPlaylist()` calls with `viewModel.previous()`.
- [ ] **Refactor:** Replace `playItemAtIndex(index)` calls with `viewModel.jumpToPlaylistIndex(index)`.
- [ ] **Cleanup:** Delete the 200+ lines of navigation math in each Activity.

## 3. UI State Bridge
The `PlayerControlsViewModel` currently maintains its own state. We need to sync it with the shared VM.
- [ ] **Refactor:** In the Activity's `handleVmUiState(state)`:
    - Map `PlayerUiState.Playing` -> `controlsViewModel.updateMetadata()` and `setBuffering(false)`.
    - Map `PlayerUiState.Loading` -> `controlsViewModel.setBuffering(true)`.
    - Map `PlayerUiState.PrePlay` -> `controlsViewModel.setPrePlay(payload)`.
- [ ] **Refactor:** Connect `controlsViewModel.onSeek` directly to `viewModel.seek()`.

## 4. Shared Error & Retry Logic
`ExoPlayerActivity` has sophisticated retry logic for audio/video crashes.
- [ ] **Refactor:** Move the "Stuck Buffer" check (currently in `scheduleStuckBufferCheck`) into either the `ExoPlayerEngine` or a shared `PlaybackWatchdog` in `commonMain`.
- [ ] **Refactor:** Move the "Malformed Content" skip-forward logic into the VM's `handlePlaybackEnded` or a dedicated error handler.

## 5. Persistence Synchronization
- [ ] **Refactor:** Ensure `ProgressManager` uses the same `ResumeStore` used by the `PlayerViewModel` to avoid "Last Position" drift.
- [ ] **Verify:** Ensure that when a video ends on Android TV, the position is saved to the shared store so the Apple TV app can resume it correctly.

## 6. Cleanup Checklist
- [ ] Remove `BroadcastReceiver` logic that handles `ACTION_PLAY` (the VM should handle the command lifecycle).
- [ ] Remove all Activity-local `isLooping`, `playlistItems`, and `pendingResumePosition` variables.
- [ ] Ensure `MpvPlayerActivity` and `VlcPlayerActivity` follow the same pattern to achieve absolute logic parity.

---
**Status:** Ready to Execute
**Current Focus:** ExoPlayerActivity.kt
