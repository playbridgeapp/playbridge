package server

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/truedem0n/playbridge-stream-resolver/config"
)

// handleGetAddons serves GET /api/addons
// Returns the current addon list as JSON.
func (s *Server) handleGetAddons(w http.ResponseWriter, r *http.Request) {
	s.mu.RLock()
	addons := s.cfg.Addons
	s.mu.RUnlock()

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(addons)
}

// handleAddAddon serves POST /api/addons
// Body: { "url": "...", "name": "...", "priority": 0, "timeout_ms": 8000 }
// Appends the addon to the live config and persists to disk.
func (s *Server) handleAddAddon(w http.ResponseWriter, r *http.Request) {
	var a config.SourceAddon
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
		a.TimeoutMs = 8000
	}

	// Verify the manifest is reachable and valid before accepting the addon.
	manifestName, err := fetchManifest(a.URL, a.TimeoutMs)
	if err != nil {
		http.Error(w, "manifest check failed: "+err.Error(), http.StatusBadRequest)
		return
	}
	if a.Name == "" {
		a.Name = manifestName
	}

	s.mu.Lock()
	// Reject duplicates by URL.
	for _, existing := range s.cfg.Addons {
		if existing.URL == a.URL {
			s.mu.Unlock()
			http.Error(w, "addon with this URL already exists", http.StatusConflict)
			return
		}
	}
	// Auto-assign priority = last position if not set.
	if a.Priority == 0 && len(s.cfg.Addons) > 0 {
		a.Priority = s.cfg.Addons[len(s.cfg.Addons)-1].Priority + 1
	}
	s.cfg.Addons = append(s.cfg.Addons, a)
	persistErr := s.persistConfig()
	s.mu.Unlock()

	if persistErr != nil {
		log.Printf("[addons] failed to persist config: %v", persistErr)
		http.Error(w, "addon added but config could not be saved: "+persistErr.Error(), http.StatusInternalServerError)
		return
	}

	log.Printf("[addons] added %s (%s)", a.Name, a.URL)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(a)
}

// handleDeleteAddon serves DELETE /api/addons?url=<encoded-url>
// Removes the addon with the matching URL from the live config and persists.
func (s *Server) handleDeleteAddon(w http.ResponseWriter, r *http.Request) {
	targetURL := r.URL.Query().Get("url")
	if targetURL == "" {
		http.Error(w, "url query param is required", http.StatusBadRequest)
		return
	}

	s.mu.Lock()
	before := len(s.cfg.Addons)
	filtered := s.cfg.Addons[:0]
	for _, a := range s.cfg.Addons {
		if a.URL != targetURL {
			filtered = append(filtered, a)
		}
	}
	if len(filtered) == before {
		s.mu.Unlock()
		http.Error(w, "addon not found", http.StatusNotFound)
		return
	}
	s.cfg.Addons = filtered
	err := s.persistConfig()
	s.mu.Unlock()

	if err != nil {
		log.Printf("[addons] failed to persist config: %v", err)
		http.Error(w, "addon removed but config could not be saved: "+err.Error(), http.StatusInternalServerError)
		return
	}

	log.Printf("[addons] removed %s", targetURL)
	w.WriteHeader(http.StatusNoContent)
}

// handleReorderAddons serves PUT /api/addons
// Body: ["url1","url2",...] — full ordered list of addon URLs.
// Resets priorities to match the new position (0 = first).
func (s *Server) handleReorderAddons(w http.ResponseWriter, r *http.Request) {
	var urls []string
	if err := json.NewDecoder(r.Body).Decode(&urls); err != nil {
		http.Error(w, "invalid JSON: "+err.Error(), http.StatusBadRequest)
		return
	}

	s.mu.Lock()
	byURL := make(map[string]config.SourceAddon, len(s.cfg.Addons))
	for _, a := range s.cfg.Addons {
		byURL[a.URL] = a
	}
	reordered := make([]config.SourceAddon, 0, len(urls))
	for i, u := range urls {
		a, ok := byURL[u]
		if !ok {
			s.mu.Unlock()
			http.Error(w, "unknown addon URL: "+u, http.StatusBadRequest)
			return
		}
		a.Priority = i
		reordered = append(reordered, a)
	}
	s.cfg.Addons = reordered
	err := s.persistConfig()
	s.mu.Unlock()

	if err != nil {
		log.Printf("[addons] failed to persist reorder: %v", err)
		http.Error(w, "reordered but config could not be saved: "+err.Error(), http.StatusInternalServerError)
		return
	}

	log.Printf("[addons] reordered — %d addons", len(reordered))
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(reordered)
}

// persistConfig writes the current in-memory config back to disk.
// Must be called with s.mu held (write lock).
func (s *Server) persistConfig() error {
	data, err := json.MarshalIndent(s.cfg, "", "  ")
	if err != nil {
		return fmt.Errorf("marshalling config: %w", err)
	}
	return os.WriteFile(s.cfgPath, data, 0644)
}

// fetchManifest fetches the addon's manifest.json, validates it, and returns
// the addon name. Returns an error if the URL is unreachable, returns a
// non-200 status, or does not decode as a valid Stremio manifest.
func fetchManifest(addonURL string, timeoutMs int) (string, error) {
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
		ID   string `json:"id"`
		Name string `json:"name"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&m); err != nil {
		return "", fmt.Errorf("invalid manifest JSON from %s: %w", manifestURL, err)
	}
	if m.Name == "" && m.ID == "" {
		return "", fmt.Errorf("manifest from %s is missing both id and name fields", manifestURL)
	}

	name := m.Name
	if name == "" {
		name = addonNameFromURL(addonURL)
	}
	return name, nil
}

// addonNameFromURL derives a human-readable name from an addon URL.
// Used as a fallback when the manifest cannot be fetched.
func addonNameFromURL(u string) string {
	u = strings.TrimSuffix(u, "/manifest.json")
	u = strings.TrimSuffix(u, "/")
	parts := strings.Split(u, "/")
	for i := len(parts) - 1; i >= 0; i-- {
		if parts[i] != "" {
			return parts[i]
		}
	}
	return u
}
