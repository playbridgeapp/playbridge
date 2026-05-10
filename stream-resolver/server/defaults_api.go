package server

import (
	"encoding/json"
	"log"
	"net/http"

	"github.com/truedem0n/playbridge-stream-resolver/config"
)

// handleGetDefaults serves GET /api/config/defaults
func (s *Server) handleGetDefaults(w http.ResponseWriter, r *http.Request) {
	s.mu.RLock()
	d := s.cfg.Defaults
	s.mu.RUnlock()

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(d)
}

// handleUpdateDefaults serves PUT /api/config/defaults
// Body: any subset of DefaultsConfig fields. Absent fields are left unchanged.
func (s *Server) handleUpdateDefaults(w http.ResponseWriter, r *http.Request) {
	var raw map[string]json.RawMessage
	if err := json.NewDecoder(r.Body).Decode(&raw); err != nil {
		http.Error(w, "invalid JSON: "+err.Error(), http.StatusBadRequest)
		return
	}

	s.mu.Lock()

	if v, ok := raw["quality"]; ok {
		var q string
		if err := json.Unmarshal(v, &q); err != nil {
			s.mu.Unlock()
			http.Error(w, "invalid quality", http.StatusBadRequest)
			return
		}
		s.cfg.Defaults.Quality = q
	}

	if v, ok := raw["source_type"]; ok {
		var st []string
		if err := json.Unmarshal(v, &st); err != nil {
			s.mu.Unlock()
			http.Error(w, "invalid source_type", http.StatusBadRequest)
			return
		}
		s.cfg.Defaults.SourceType = st
	}

	if v, ok := raw["source"]; ok {
		var src string
		if err := json.Unmarshal(v, &src); err != nil {
			s.mu.Unlock()
			http.Error(w, "invalid source", http.StatusBadRequest)
			return
		}
		s.cfg.Defaults.Source = src
	}

	if v, ok := raw["audio_lang"]; ok {
		var al string
		if err := json.Unmarshal(v, &al); err != nil {
			s.mu.Unlock()
			http.Error(w, "invalid audio_lang", http.StatusBadRequest)
			return
		}
		s.cfg.Defaults.AudioLang = al
	}

	for _, pair := range []struct {
		key string
		dst *float64
	}{
		{"min_size", &s.cfg.Defaults.MinSize},
		{"max_size", &s.cfg.Defaults.MaxSize},
		{"min_bitrate", &s.cfg.Defaults.MinBitrate},
		{"max_bitrate", &s.cfg.Defaults.MaxBitrate},
	} {
		if v, ok := raw[pair.key]; ok {
			var f float64
			if err := json.Unmarshal(v, &f); err != nil || f < 0 {
				s.mu.Unlock()
				http.Error(w, "invalid value for "+pair.key, http.StatusBadRequest)
				return
			}
			*pair.dst = f
		}
	}

	d := s.cfg.Defaults
	err := s.persistConfig()
	s.mu.Unlock()

	if err != nil {
		log.Printf("[defaults] failed to persist config: %v", err)
		http.Error(w, "defaults updated but config could not be saved: "+err.Error(), http.StatusInternalServerError)
		return
	}

	log.Printf("[defaults] updated — %+v", d)
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(d)
}

// applyDefaults merges server-side defaults into prefs for any field that was
// not explicitly supplied via query params (i.e. still at its zero value).
func applyStreamingDefaults(p PlayPrefs, d config.DefaultsConfig) PlayPrefs {
	if p.Quality == "" && d.Quality != "" && d.Quality != "Auto" {
		p.Quality = d.Quality
	}
	if len(p.SourceType) == 0 && len(d.SourceType) > 0 {
		p.SourceType = d.SourceType
	}
	if p.Source == "" && d.Source != "" {
		p.Source = d.Source
	}
	if p.AudioLang == "" && d.AudioLang != "" {
		p.AudioLang = d.AudioLang
	}
	if p.MinSize == 0 && d.MinSize > 0 {
		p.MinSize = d.MinSize
	}
	if p.MaxSize == 0 && d.MaxSize > 0 {
		p.MaxSize = d.MaxSize
	}
	if p.MinBitrate == 0 && d.MinBitrate > 0 {
		p.MinBitrate = d.MinBitrate
	}
	if p.MaxBitrate == 0 && d.MaxBitrate > 0 {
		p.MaxBitrate = d.MaxBitrate
	}
	return p
}
