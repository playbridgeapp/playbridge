package server

import (
	"encoding/json"
	"log"
	"net/http"
	"time"

	"github.com/truedem0n/playbridge-stream-resolver/cache"
	"github.com/truedem0n/playbridge-stream-resolver/config"
)

// handleClearCache serves DELETE /api/cache?type=streams|play|meta|all
// Clears one or all on-disk cache stores.
func (s *Server) handleClearCache(w http.ResponseWriter, r *http.Request) {
	which := r.URL.Query().Get("type")
	if which == "" {
		which = "all"
	}

	var stores map[string]*cache.Store
	s.mu.RLock()
	stores = map[string]*cache.Store{
		"streams": s.streamCache,
		"play":    s.playCache,
		"meta":    s.metaCache,
	}
	s.mu.RUnlock()

	toClear := map[string]*cache.Store{}
	switch which {
	case "all":
		toClear = stores
	case "streams", "play", "meta":
		toClear[which] = stores[which]
	default:
		http.Error(w, "type must be streams, play, meta, or all", http.StatusBadRequest)
		return
	}

	for name, store := range toClear {
		if err := store.Clear(); err != nil {
			log.Printf("[cache] failed to clear %s cache: %v", name, err)
			http.Error(w, "failed to clear "+name+" cache: "+err.Error(), http.StatusInternalServerError)
			return
		}
		log.Printf("[cache] cleared %s cache", name)
	}

	w.WriteHeader(http.StatusNoContent)
}

// handleGetCacheConfig serves GET /api/config/cache
// Returns the current cache TTL settings.
func (s *Server) handleGetCacheConfig(w http.ResponseWriter, r *http.Request) {
	s.mu.RLock()
	cc := s.cfg.Cache
	s.mu.RUnlock()

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(cc)
}

// handleUpdateCacheConfig serves PUT /api/config/cache
// Body: { "stream_list_ttl_seconds": 300, "play_result_ttl_seconds": 3600, "meta_ttl_seconds": 86400 }
// Updates TTLs in memory and persists to config.json. The new TTLs take effect
// on the next cache miss — existing cached entries are not evicted.
func (s *Server) handleUpdateCacheConfig(w http.ResponseWriter, r *http.Request) {
	var cc config.CacheConfig
	if err := json.NewDecoder(r.Body).Decode(&cc); err != nil {
		http.Error(w, "invalid JSON: "+err.Error(), http.StatusBadRequest)
		return
	}
	if cc.StreamListTTLSeconds < 0 || cc.PlayResultTTLSeconds < 0 || cc.MetaTTLSeconds < 0 {
		http.Error(w, "TTL values must be non-negative", http.StatusBadRequest)
		return
	}

	s.mu.Lock()
	if cc.StreamListTTLSeconds > 0 {
		s.cfg.Cache.StreamListTTLSeconds = cc.StreamListTTLSeconds
		s.streamCache.SetTTL(time.Duration(cc.StreamListTTLSeconds) * time.Second)
	}
	if cc.PlayResultTTLSeconds > 0 {
		s.cfg.Cache.PlayResultTTLSeconds = cc.PlayResultTTLSeconds
		s.playCache.SetTTL(time.Duration(cc.PlayResultTTLSeconds) * time.Second)
	}
	if cc.MetaTTLSeconds > 0 {
		s.cfg.Cache.MetaTTLSeconds = cc.MetaTTLSeconds
		s.metaCache.SetTTL(time.Duration(cc.MetaTTLSeconds) * time.Second)
	}
	updated := s.cfg.Cache
	err := s.persistConfig()
	s.mu.Unlock()

	if err != nil {
		log.Printf("[cache] failed to persist config: %v", err)
		http.Error(w, "settings updated but config could not be saved: "+err.Error(), http.StatusInternalServerError)
		return
	}

	log.Printf("[cache] TTLs updated — streams:%ds play:%ds meta:%ds",
		updated.StreamListTTLSeconds, updated.PlayResultTTLSeconds, updated.MetaTTLSeconds)
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(updated)
}
