package server

import (
	"log"
	"net/http"
	"strconv"
	"strings"

	"github.com/truedem0n/playbridge-stream-resolver/prober"
)

// handlePlay serves GET /api/play/{type}/{id}
//
// Query params:
//
//	skip=N  start probing from stream index N (default 0). Lets the TV app
//	        retry with the next candidate without re-fetching the stream list.
//
// Responses:
//
//	307  → redirect to the first stream that passes duration validation
//	404  → no working stream found within the probe limit
//	503  → all source addons failed (empty stream list)
func (s *Server) handlePlay(w http.ResponseWriter, r *http.Request) {
	itemType := r.PathValue("type")
	id := strings.TrimSuffix(r.PathValue("id"), ".json")

	if itemType == "" || id == "" {
		http.Error(w, "missing type or id", http.StatusBadRequest)
		return
	}

	skip := 0
	if v := r.URL.Query().Get("skip"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n >= 0 {
			skip = n
		}
	}

	log.Printf("[play] %s/%s skip=%d", itemType, id, skip)

	// Check play result cache (only valid when skip==0; a skip>0 request is
	// explicitly asking for a different stream so we must not return a cached
	// pick from position 0).
	playCacheKey := itemType + "/" + id
	if skip == 0 {
		var cachedURL string
		if hit, _ := s.playCache.Get(playCacheKey, &cachedURL); hit && cachedURL != "" {
			log.Printf("[play] cache hit → %s", truncateURL(cachedURL))
			http.Redirect(w, r, cachedURL, http.StatusTemporaryRedirect)
			return
		}
	}

	// Get merged ranked stream list (shared with /stream endpoint via cache).
	streams, err := s.getStreamList(itemType, id)
	if err != nil || len(streams) == 0 {
		log.Printf("[play] no streams for %s/%s", itemType, id)
		http.Error(w, "no streams available", http.StatusServiceUnavailable)
		return
	}

	// Fetch expected runtime for duration validation.
	// Tries OMDB first (if key configured), falls back to Cinemeta.
	// Returns 0 only when both sources have no data — probing is skipped in that case.
	imdbID := imdbIDFromStremioID(id)
	expectedMins := s.runtimeMins(imdbID)
	if expectedMins > 0 {
		log.Printf("[play] expected runtime: %d min", expectedMins)
	}

	probingEnabled := s.cfg.Probing.Enabled && prober.Available()
	maxAttempts := s.cfg.Probing.MaxAttempts

	probeCount := 0
	for i := skip; i < len(streams); i++ {
		rs := streams[i]
		url := rs.Stream.URL

		// Skip streams with no direct URL (infoHash-only, etc.)
		if url == "" {
			continue
		}

		if !probingEnabled || expectedMins <= 10 {
			// Probing disabled or runtime unknown/too short to be meaningful —
			// return the first stream with a URL directly.
			log.Printf("[play] no probing, using stream %d from %s: %s",
				i, rs.SourceName, rs.Stream.Name)
			cacheAndRedirect(s, w, r, playCacheKey, skip, url)
			return
		}

		if probeCount >= maxAttempts {
			log.Printf("[play] reached max probe attempts (%d), giving up", maxAttempts)
			break
		}
		probeCount++

		log.Printf("[play] probing [%d/%d] %s (pos %d from %s)",
			probeCount, maxAttempts, rs.Stream.Name, rs.SourcePos, rs.SourceName)

		durationMins, err := prober.ProbeDuration(url, s.cfg.Probing.TimeoutMs)
		if err != nil {
			log.Printf("[play] probe failed: %v — skipping", err)
			continue
		}
		if durationMins == 0 {
			log.Printf("[play] probe returned 0 min — skipping")
			continue
		}
		if durationMins < expectedMins/2 {
			log.Printf("[play] probe too short (%d min vs expected %d min) — skipping",
				durationMins, expectedMins)
			continue
		}

		log.Printf("[play] probe passed (%d min), redirecting to stream %d", durationMins, i)
		cacheAndRedirect(s, w, r, playCacheKey, skip, url)
		return
	}

	log.Printf("[play] no working stream found for %s/%s", itemType, id)
	http.Error(w, "no working stream found", http.StatusNotFound)
}

func truncateURL(url string) string {
	if len(url) > 80 {
		return url[:80] + "..."
	}
	return url
}

func cacheAndRedirect(s *Server, w http.ResponseWriter, r *http.Request,
	cacheKey string, skip int, url string) {
	// Only cache the result when we resolved from position 0 — a skip>0 result
	// is a manual retry and should not overwrite the canonical cached pick.
	if skip == 0 {
		_ = s.playCache.Set(cacheKey, url)
	}
	http.Redirect(w, r, url, http.StatusTemporaryRedirect)
}
