# PlayBridge TV — Player Evolution Plan

This document outlines the gap analysis and roadmap for the PlayBridge TV player, drawing inspiration from modern "gold standard" implementations: **mpvEx** (Compose/MPV) and **Just Player** (Media3/ExoPlayer).

## 1. Competitive Analysis

| Feature | Just Player | mpvEx | PlayBridge TV |
| :--- | :--- | :--- | :--- |
| **Philosophy** | Hardware Perfection | modern UI + libmpv | Multi-engine Resilience |
| **UI Framework** | Legacy XML | **Jetpack Compose** | **Leanback (Legacy)** |
| **Engine Capability** | Media3/Exo tuned to max | libmpv with Shaders | **Triple Engine (Exo/VLC/MPV)** |
| **Resilience** | Fails on error | Fails on error | **Auto-Engine Handover** |
| **Judder Fix** | **Auto Frame Rate** | No | **Auto (All Engines)** |
| **Audio** | **Loudness Boost** | Advanced Filters | Standard |

---

## 2. Our "Superpowers" (Current Strengths)
Before evolving, we must preserve what PlayBridge does better than anyone else:
- **Resilient Switching:** Automatically transitioning from ExoPlayer to VLC when a codec (like WMV or AC3) fails.
- **Content Sniffer:** Pre-flight resolution and SSL bypass for local-network streams.
- **Universal Remote:** Robust integration with the Phone sender app.

---

## 3. The Blueprint for Evolution

### Phase 1: Cinematic Hardware Tuning (Inspiration: Just Player)
Modern TV users expect a "cinema-grade" experience.
- [x] **Adaptive Frame Rate Matching:** Implemented `Surface.setFrameRate` (API 30+) across ExoPlayer, MPV, and VLC engines with automatic handshake debouncing.
- [x] **Loudness Enhancer (Night Mode):** Implemented via hardware-backed `LoudnessEnhancer` (Exo), `af=volume` filter (MPV), and software volume scaling (VLC).
- [ ] **HDR Format Detection:** Better surfacing of HDR10/Dolby Vision metadata to the user.

### Phase 2: Modernization & Aesthetics (Inspiration: mpvEx)
Replace the dated "Leanback" look with a responsive, modern UI.
- [ ] **Transition to Compose for TV:** Switch from XML/Leanback to `androidx.tv.material3`.
- [ ] **Glassmorphism & Micro-animations:** Implement transparent, blurred control panels that feel "Apple TV" premium.
- [ ] **Integrated PiP Helper:** Allow the user to "minimize" a stream to browse the library without stopping playback.

### Phase 3: Deep Engine Exposure (Inspiration: mpvEx)
Leverage our multi-engine architecture by exposing engine-specific "Pro" features.
- [ ] **MPV Advanced Subtitles:** Allow native SSA/ASS rendering instead of forcing standard styles (Shadow/White).
- [ ] **MPV Custom Shaders:** Expose `fsrcnnx` or `krimes` shaders for high-quality upscaling of 720p/1080p content.
- [ ] **VLC Network Pass-through:** Better support for complex SMB/FTP sources.

---

## 4. Immediate Next Steps (The "Quick Wins")

1. **Loudness Boost:** Add a +10dB / +20dB boost toggle using the `LoudnessEnhancer` API in `ExoPlayerEngine`.
2. **Dynamic UI Shadows:** Update `UnifiedControlsManager` to use modern Compose shadows and blurs.
3. **Engine-Level Logging:** Surface *why* an engine switch happened (e.g., "Codec unsupported -> Retrying with VLC") to the user via a small toast or UI hint.
