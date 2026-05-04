package server

import (
	"encoding/json"
	"log"
	"net/http"
	"strings"

	"github.com/truedem0n/playbridge-stream-resolver/resolver"
	"github.com/truedem0n/playbridge-stream-resolver/types"
)

// handleStream serves GET /stream/{type}/{id}
// Returns the full merged and ranked stream list for the phone stream picker.
// No probing — optimised for low latency.
func (s *Server) handleStream(w http.ResponseWriter, r *http.Request) {
	itemType := r.PathValue("type")
	id := strings.TrimSuffix(r.PathValue("id"), ".json")

	if itemType == "" || id == "" {
		http.Error(w, "missing type or id", http.StatusBadRequest)
		return
	}

	log.Printf("[stream] %s/%s", itemType, id)

	streams, err := s.getStreamList(itemType, id)
	if err != nil {
		log.Printf("[stream] error fetching streams for %s/%s: %v", itemType, id, err)
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(types.StreamResponse{Streams: []types.Stream{}})
		return
	}

	// Convert RankedStream slice to plain Stream slice for Stremio response.
	out := make([]types.Stream, 0, len(streams))
	for _, rs := range streams {
		out = append(out, rs.Stream)
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(types.StreamResponse{Streams: out})
}

// getStreamList returns a ranked stream list for itemType/id, using the stream
// cache when warm and the inflight map to coalesce concurrent requests.
func (s *Server) getStreamList(itemType, id string) ([]types.RankedStream, error) {
	cacheKey := itemType + "/" + id

	// Cache hit — return immediately.
	var cached []types.RankedStream
	if hit, _ := s.streamCache.Get(cacheKey, &cached); hit {
		log.Printf("[stream] cache hit for %s", cacheKey)
		return cached, nil
	}

	// In-flight dedup: if another goroutine is already fetching this, wait for
	// it and then re-read from cache.
	first, wait := s.inflight.Start(cacheKey)
	if !first {
		wait()
		var cached2 []types.RankedStream
		if hit, _ := s.streamCache.Get(cacheKey, &cached2); hit {
			return cached2, nil
		}
		// Cache miss after wait (the other goroutine may have gotten no results).
		// Fall through to fetch ourselves.
	} else {
		defer s.inflight.Done(cacheKey)
	}

	// Fetch from all configured addons and rank.
	raw := resolver.FetchAll(s.cfg.Addons, itemType, id)
	ranked := resolver.Rank(raw, s.cfg.Addons)

	log.Printf("[stream] fetched %d streams for %s, ranked to %d", len(raw), cacheKey, len(ranked))

	if len(ranked) > 0 {
		_ = s.streamCache.Set(cacheKey, ranked)
	}

	return ranked, nil
}
