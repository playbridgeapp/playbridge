package server

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/truedem0n/playbridge-stream-resolver/config"
)

// handleGetMetaAddons serves GET /api/meta-addons
func (s *Server) handleGetMetaAddons(w http.ResponseWriter, r *http.Request) {
	s.mu.RLock()
	addons := s.cfg.MetaAddons
	s.mu.RUnlock()

	if addons == nil {
		addons = []config.MetaAddon{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(addons)
}

// handleAddMetaAddon serves POST /api/meta-addons
// Body: { "url": "...", "name": "...", "timeout_ms": 5000 }
// Validates that the manifest is reachable and has a meta resource before accepting.
func (s *Server) handleAddMetaAddon(w http.ResponseWriter, r *http.Request) {
	var a config.MetaAddon
	if err := json.NewDecoder(r.Body).Decode(&a); err != nil {
		http.Error(w, "invalid JSON: "+err.Error(), http.StatusBadRequest)
		return
	}

	a.URL = strings.TrimSpace(a.URL)
	if a.URL == "" {
		http.Error(w, "url is required", http.StatusBadRequest)
		return
	}
	if a.TimeoutMs == 0 {
		a.TimeoutMs = 5000
	}

	// Verify manifest is reachable and supports the meta resource.
	name, err := fetchMetaManifest(a.URL, a.TimeoutMs)
	if err != nil {
		http.Error(w, "manifest check failed: "+err.Error(), http.StatusBadRequest)
		return
	}
	if a.Name == "" {
		a.Name = name
	}

	s.mu.Lock()
	for _, existing := range s.cfg.MetaAddons {
		if existing.URL == a.URL {
			s.mu.Unlock()
			http.Error(w, "meta addon with this URL already exists", http.StatusConflict)
			return
		}
	}
	s.cfg.MetaAddons = append(s.cfg.MetaAddons, a)
	persistErr := s.persistConfig()
	s.mu.Unlock()

	if persistErr != nil {
		log.Printf("[meta-addons] failed to persist config: %v", persistErr)
		http.Error(w, "addon added but config could not be saved: "+persistErr.Error(), http.StatusInternalServerError)
		return
	}

	log.Printf("[meta-addons] added %s (%s)", a.Name, a.URL)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(a)
}

// handleDeleteMetaAddon serves DELETE /api/meta-addons?url=<encoded-url>
func (s *Server) handleDeleteMetaAddon(w http.ResponseWriter, r *http.Request) {
	targetURL := r.URL.Query().Get("url")
	if targetURL == "" {
		http.Error(w, "url query param is required", http.StatusBadRequest)
		return
	}

	s.mu.Lock()
	before := len(s.cfg.MetaAddons)
	filtered := s.cfg.MetaAddons[:0]
	for _, a := range s.cfg.MetaAddons {
		if a.URL != targetURL {
			filtered = append(filtered, a)
		}
	}
	if len(filtered) == before {
		s.mu.Unlock()
		http.Error(w, "meta addon not found", http.StatusNotFound)
		return
	}
	s.cfg.MetaAddons = filtered
	err := s.persistConfig()
	s.mu.Unlock()

	if err != nil {
		log.Printf("[meta-addons] failed to persist config: %v", err)
		http.Error(w, "addon removed but config could not be saved: "+err.Error(), http.StatusInternalServerError)
		return
	}

	log.Printf("[meta-addons] removed %s", targetURL)
	w.WriteHeader(http.StatusNoContent)
}

// fetchMetaManifest fetches and validates an addon manifest, confirming it
// supports the "meta" resource. Returns the addon name on success.
func fetchMetaManifest(addonURL string, timeoutMs int) (string, error) {
	base := strings.TrimSuffix(strings.TrimSuffix(addonURL, "/"), "/manifest.json")
	manifestURL := base + "/manifest.json"

	client := &http.Client{Timeout: time.Duration(timeoutMs) * time.Millisecond}
	resp, err := client.Get(manifestURL)
	if err != nil {
		return "", fmt.Errorf("could not reach %s: %w", manifestURL, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("%s returned HTTP %d", manifestURL, resp.StatusCode)
	}

	var m struct {
		ID        string `json:"id"`
		Name      string `json:"name"`
		Resources []any  `json:"resources"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&m); err != nil {
		return "", fmt.Errorf("invalid manifest JSON from %s: %w", manifestURL, err)
	}
	if m.Name == "" && m.ID == "" {
		return "", fmt.Errorf("manifest from %s is missing both id and name fields", manifestURL)
	}

	// Confirm the addon has a meta resource (string or object form).
	hasMeta := false
	for _, r := range m.Resources {
		switch v := r.(type) {
		case string:
			if v == "meta" {
				hasMeta = true
			}
		case map[string]any:
			if name, ok := v["name"].(string); ok && name == "meta" {
				hasMeta = true
			}
		}
	}
	if !hasMeta {
		return "", fmt.Errorf("addon at %s does not advertise a meta resource", manifestURL)
	}

	name := m.Name
	if name == "" {
		name = m.ID
	}
	return name, nil
}
