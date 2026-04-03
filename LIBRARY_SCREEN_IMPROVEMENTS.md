# Library Screen Improvements
_Inspired by: [Plezy on Google Play](https://play.google.com/store/apps/details?id=com.edde746.plezy)_
_Created: 2026-04-02 | File: `phone/app/src/main/java/com/playbridge/sender/browser/LibraryScreen.kt`_

---

## Overview

The library screen currently has two implicit modes (curated rows vs. filtered discovery) that blend together invisibly. Plezy does this better by separating them structurally. The improvements below are ordered by priority — each is self-contained and can be tackled independently.

---

## 1. ~~Tab Bar — Home | Browse~~ ✅ Done
**Effort:** Medium (~half a day) | **Impact:** High | **Risk:** Low | **Completed:** 2026-04-03

**What:** Add a `TabRow` with two tabs at the top of the library screen:
- **Home** — shows the current curated horizontal rows (Trending Today, Popular Movies, Popular TV Shows)
- **Browse** — shows the discovery/filter grid (currently only visible when filters are active)

**Why:** Right now users have no obvious way to know the discovery mode exists. The transition from rows → filtered view is silent and confusing.

**How:**
- Add a `selectedTab` state (`remember { mutableStateOf(0) }`) in `LibraryScreen`
- Wrap the `TabRow` below the `TopAppBar` inside the `Scaffold`
- Move the `isFiltering` branch logic to be tab-driven instead — tab 0 always shows curated rows, tab 1 always shows the Browse grid (with or without filters active)
- The filter/sort actions in the `TopAppBar` should only appear when on the Browse tab

**No ViewModel changes needed.**

---

## 2. ~~3-Column Vertical Grid in Browse Mode~~ ✅ Done
**Effort:** Low (~2 hours) | **Impact:** High | **Risk:** Low | **Completed:** 2026-04-03

**What:** When in Browse/discovery mode, replace the horizontal `LazyRow` sections with a single `LazyVerticalGrid(GridCells.Fixed(3))`.

**Why:** Horizontal rows are awkward for scanning a large filtered result set. A grid is far better for browsing many titles at once, and matches how Plezy's Browse tab works.

**How:**
- In the `isFiltering` branch (or Browse tab body after #1), replace `MediaRow` composables with a single `LazyVerticalGrid`
- Mix movies and TV shows into one unified list (or show separate sections with sticky headers)
- `PosterCard` already works standalone — it just slots into the grid items
- Add a load-more trigger at the bottom of the grid (same pattern as the existing `isNearEnd` derived state)

**No ViewModel changes needed.**

---

## 3. ~~Filter Count Badge on Filter Button~~ ✅ Done
**Effort:** Low (~1 hour) | **Impact:** Medium | **Risk:** None | **Completed:** 2026-04-02

`activeFilterCount` (counts genres, media type, sort, year) is computed in `LibraryScreen` and the filter `IconButton` now wraps its icon in a `BadgedBox` — a Material3 `Badge` appears when any filter is active. No ViewModel changes.

---

## 4. ~~Inline Sort + Media Type Chips Above Browse Grid~~ ✅ Done
**Effort:** Low (~2 hours) | **Impact:** Medium | **Risk:** Low | **Completed:** 2026-04-02

A permanent chip strip (`All | Movies | TV Shows … [Sort ▾]`) is now the first `item {}` in the main `LazyColumn`, always visible. Tapping type chips or the sort dropdown triggers discovery mode directly. The bottom sheet was slimmed down to Year + Genres only. `activeFilterCount` badge was updated to count only genres + year.

---

## 5. Watched Checkmark Badge on Poster Cards
**Effort:** Medium (~half a day, pending schema check) | **Impact:** Medium | **Risk:** Low

**What:** Show a small checkmark badge in the corner of `PosterCard` for items the user has fully watched, mirroring Plezy's treatment of House MD.

**Why:** At a glance, users can see what they've already seen without having to remember.

**How:**
- First, verify `data/history/` Room DB schema — check if history entries store a TMDB ID and whether there's a `completed` flag or watch-duration field
  - File to check: `phone/app/src/main/java/com/playbridge/sender/data/history/`
- If TMDB IDs are stored: expose a `Set<Int>` of watched TMDB IDs from a new ViewModel query and pass `isWatched: Boolean` down to `PosterCard`
- If not stored: this requires a small schema migration to add a `tmdbId` column to the history table
- In `PosterCard`, add a checkmark `Surface` badge in the top-end corner (similar to the existing rating badge in the top-start corner)

**ViewModel change needed** (new Flow for watched IDs). Possible schema migration.

---

## 6. ~~Hero Featured Banner (Home Tab)~~ ✅ Done
**Effort:** High (~1 day) | **Impact:** Very High | **Risk:** Low | **Completed:** 2026-04-03

**What:** At the top of the Home tab, show a full-bleed hero card using the backdrop image of the #1 trending item, with a gradient overlay, title, rating, year, content rating, and a "Play" button. Optionally auto-cycle through the top 3–5 trending items with dot indicators.

**Why:** This is the biggest single visual upgrade. Plezy's hero is the first thing you see and immediately sets the tone. PlayBridge's home currently starts with a plain section header.

**How:**
- TMDB returns `backdrop_path` alongside `poster_path` — check `TmdbMultiSearchResult` / `TmdbRepository` to confirm `backdropUrl` is mapped (add it if not)
- Build a `HeroBanner` composable:
  ```
  Box (fillMaxWidth, height ~220dp) {
      AsyncImage(backdropUrl, ContentScale.Crop)
      Box(gradient overlay bottom-to-top, black → transparent)
      Column (bottom-aligned) {
          Text(title, titleLarge, bold)
          Row { RatingBadge · YearText · ContentRatingBadge }
          Button("▶ Play") { onItemClick() }
      }
  }
  ```
- For the auto-cycling carousel: use `LaunchedEffect` with a 5-second `delay` loop advancing a `pagerIndex` state, and show `HorizontalPager` (Accompanist or Compose Foundation) with dot indicators below
- Place the `HeroBanner` as the first `item {}` in the Home tab `LazyColumn`, before the Trending row

**No ViewModel changes needed** (uses existing `trending` list). Adding `backdropUrl` to data models may be required.

---

## Summary Table

| # | Feature | Effort | Impact | ViewModel change? |
|---|---------|--------|--------|-------------------|
| 1 | ~~Tab bar (Home \| Browse)~~ ✅ | Medium | High | No |
| 2 | ~~3-col vertical grid in Browse~~ ✅ | Low | High | No |
| 3 | ~~Filter count badge~~ ✅ | Low | Medium | No |
| 4 | ~~Inline Sort + Type chips~~ ✅ | Low | Medium | No |
| 5 | Watched checkmark badge | Medium | Medium | Yes + possible migration |
| 6 | ~~Hero featured banner~~ ✅ | High | Very High | No (maybe data model) |

**Recommended order to tackle:** 3 → 4 → 2 → 1 → 6 → 5

Start with 3 and 4 (quick wins, no risk), then 2 (grid) and 1 (tabs) together since they're structurally related, then the hero (6) as a standalone visual feature, and finally the watched badge (5) once the history schema question is resolved.

---

## Files to Touch

- `LibraryScreen.kt` — all UI changes
- `LibraryViewModel.kt` — only for #5 (watched IDs flow)
- `data/library/TmdbRepository.kt` + model classes — only for #6 if `backdropUrl` is missing
- `data/history/` — only for #5 if schema migration needed
