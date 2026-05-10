package server

import (
	"fmt"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/truedem0n/playbridge-stream-resolver/cache"
	"github.com/truedem0n/playbridge-stream-resolver/config"
	"github.com/truedem0n/playbridge-stream-resolver/prober"
)

// Server holds all shared dependencies and registers HTTP routes.
type Server struct {
	mu          sync.RWMutex   // guards cfg and cfgPath
	cfg         *config.Config
	cfgPath     string         // path to config.json, for persisting changes

	streamCache *cache.Store   // merged ranked stream list, keyed by "type/id"
	playCache   *cache.Store   // resolved play URL, keyed by "type/id"
	metaCache   *cache.Store   // OMDB runtime, keyed by "meta:imdbID"
	inflight    *cache.InflightMap
}

// New creates a Server, warns if probing is enabled but ffprobe is missing.
func New(cfg *config.Config, cfgPath string) *Server {
	const cacheRoot = "cache"

	s := &Server{
		cfg:     cfg,
		cfgPath: cfgPath,
		streamCache: cache.NewStore(cacheRoot, "streams",
			time.Duration(cfg.Cache.StreamListTTLSeconds)*time.Second),
		playCache: cache.NewStore(cacheRoot, "play",
			time.Duration(cfg.Cache.PlayResultTTLSeconds)*time.Second),
		metaCache: cache.NewStore(cacheRoot, "meta",
			time.Duration(cfg.Cache.MetaTTLSeconds)*time.Second),
		inflight: cache.NewInflightMap(),
	}

	if cfg.Probing.Enabled && !prober.Available() {
		log.Println("[server] WARNING: probing is enabled but ffprobe was not found. " +
			"Probing will be skipped and stream position 0 will be used directly.")
	}

	return s
}

// Handler returns the HTTP mux with all routes registered.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()

	// Stremio addon protocol
	mux.HandleFunc("GET /manifest.json", s.handleManifest)
	mux.HandleFunc("GET /stream/{type}/{id}", s.handleStream)
	mux.HandleFunc("GET /configure", s.handleConfigure)

	// Headless play endpoint for TV / thin clients
	mux.HandleFunc("GET /api/play/{type}/{id}", s.handlePlay)

	// Addon management REST API
	mux.HandleFunc("GET /api/addons", s.handleGetAddons)
	mux.HandleFunc("POST /api/addons", s.handleAddAddon)
	mux.HandleFunc("PUT /api/addons", s.handleReorderAddons)
	mux.HandleFunc("DELETE /api/addons", s.handleDeleteAddon)

	// Cache management REST API
	mux.HandleFunc("DELETE /api/cache", s.handleClearCache)
	mux.HandleFunc("GET /api/config/cache", s.handleGetCacheConfig)
	mux.HandleFunc("PUT /api/config/cache", s.handleUpdateCacheConfig)

	// Probing config REST API
	mux.HandleFunc("GET /api/config/probing", s.handleGetProbingConfig)
	mux.HandleFunc("PUT /api/config/probing", s.handleUpdateProbingConfig)

	// Streaming defaults REST API
	mux.HandleFunc("GET /api/config/defaults", s.handleGetDefaults)
	mux.HandleFunc("PUT /api/config/defaults", s.handleUpdateDefaults)

	// Meta addon management REST API
	mux.HandleFunc("GET /api/meta-addons", s.handleGetMetaAddons)
	mux.HandleFunc("POST /api/meta-addons", s.handleAddMetaAddon)
	mux.HandleFunc("PUT /api/meta-addons", s.handleReorderMetaAddons)
	mux.HandleFunc("DELETE /api/meta-addons", s.handleDeleteMetaAddon)

	return corsMiddleware(mux)
}

// ListenAndServe starts the HTTP server on the configured port.
func (s *Server) ListenAndServe() error {
	s.mu.RLock()
	addr := fmt.Sprintf(":%d", s.cfg.Port)
	baseURL := s.cfg.BaseURL
	addons := s.cfg.Addons
	s.mu.RUnlock()

	log.Printf("[server] Listening on %s", addr)
	log.Printf("[server] Manifest:   %s/manifest.json", baseURL)
	log.Printf("[server] Configure:  %s/configure", baseURL)
	log.Printf("[server] Configured addons: %d", len(addons))
	for _, a := range addons {
		log.Printf("[server]   [priority %d] %s (%s)", a.Priority, a.Name, a.URL)
	}
	return http.ListenAndServe(addr, s.Handler())
}

// corsMiddleware adds the CORS headers required by Stremio.
func corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}
