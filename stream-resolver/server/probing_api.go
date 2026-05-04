package server

import (
	"encoding/json"
	"log"
	"net/http"
)

// probingConfigResponse is the shape returned/accepted by the probing config API.
type probingConfigResponse struct {
	Enabled     bool `json:"enabled"`
	MaxAttempts int  `json:"max_attempts"`
	TimeoutMs   int  `json:"timeout_ms"`
}

// handleGetProbingConfig serves GET /api/config/probing
func (s *Server) handleGetProbingConfig(w http.ResponseWriter, r *http.Request) {
	s.mu.RLock()
	resp := probingConfigResponse{
		Enabled:     s.cfg.Probing.Enabled,
		MaxAttempts: s.cfg.Probing.MaxAttempts,
		TimeoutMs:   s.cfg.Probing.TimeoutMs,
	}
	s.mu.RUnlock()

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}

// handleUpdateProbingConfig serves PUT /api/config/probing
// Body: { "enabled": true, "max_attempts": 5, "timeout_ms": 5000 }
// All fields are optional — only fields present in the JSON body are applied.
func (s *Server) handleUpdateProbingConfig(w http.ResponseWriter, r *http.Request) {
	// Decode into a raw map to distinguish absent fields from zero values.
	var raw map[string]json.RawMessage
	if err := json.NewDecoder(r.Body).Decode(&raw); err != nil {
		http.Error(w, "invalid JSON: "+err.Error(), http.StatusBadRequest)
		return
	}

	s.mu.Lock()

	if v, ok := raw["enabled"]; ok {
		var b bool
		if err := json.Unmarshal(v, &b); err != nil {
			s.mu.Unlock()
			http.Error(w, "invalid value for enabled: "+err.Error(), http.StatusBadRequest)
			return
		}
		s.cfg.Probing.Enabled = b
	}

	if v, ok := raw["max_attempts"]; ok {
		var n int
		if err := json.Unmarshal(v, &n); err != nil || n <= 0 {
			s.mu.Unlock()
			http.Error(w, "max_attempts must be a positive integer", http.StatusBadRequest)
			return
		}
		s.cfg.Probing.MaxAttempts = n
	}

	if v, ok := raw["timeout_ms"]; ok {
		var n int
		if err := json.Unmarshal(v, &n); err != nil || n <= 0 {
			s.mu.Unlock()
			http.Error(w, "timeout_ms must be a positive integer", http.StatusBadRequest)
			return
		}
		s.cfg.Probing.TimeoutMs = n
	}

	resp := probingConfigResponse{
		Enabled:     s.cfg.Probing.Enabled,
		MaxAttempts: s.cfg.Probing.MaxAttempts,
		TimeoutMs:   s.cfg.Probing.TimeoutMs,
	}
	err := s.persistConfig()
	s.mu.Unlock()

	if err != nil {
		log.Printf("[probing] failed to persist config: %v", err)
		http.Error(w, "settings updated but config could not be saved: "+err.Error(), http.StatusInternalServerError)
		return
	}

	log.Printf("[probing] config updated — enabled:%v maxAttempts:%d timeoutMs:%d",
		resp.Enabled, resp.MaxAttempts, resp.TimeoutMs)

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}
