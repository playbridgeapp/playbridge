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

	for _, pair := range []struct {
		key string
		dst *config.Bucket
	}{
		{"quality", &s.cfg.Defaults.Quality},
		{"source_type", &s.cfg.Defaults.SourceType},
		{"source", &s.cfg.Defaults.Source},
		{"audio_lang", &s.cfg.Defaults.AudioLang},
	} {
		if v, ok := raw[pair.key]; ok {
			var b config.Bucket
			if err := json.Unmarshal(v, &b); err != nil {
				s.mu.Unlock()
				http.Error(w, "invalid value for "+pair.key+": "+err.Error(), http.StatusBadRequest)
				return
			}
			*pair.dst = b
		}
	}

	if v, ok := raw["exclude_words"]; ok {
		var ew []string
		if err := json.Unmarshal(v, &ew); err != nil {
			s.mu.Unlock()
			http.Error(w, "invalid exclude_words", http.StatusBadRequest)
			return
		}
		s.cfg.Defaults.ExcludeWords = ew
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

// applyStreamingDefaults merges server-side defaults into prefs for any field
// that was not explicitly supplied via query params.
// Config excluded buckets always apply — URL params cannot override exclusions.
func applyStreamingDefaults(p PlayPrefs, d config.DefaultsConfig) PlayPrefs {
	// Excluded always inherits from config if not set by caller.
	if len(p.Quality.Excluded) == 0 {
		p.Quality.Excluded = d.Quality.Excluded
	}
	if len(p.SourceType.Excluded) == 0 {
		p.SourceType.Excluded = d.SourceType.Excluded
	}
	if len(p.Source.Excluded) == 0 {
		p.Source.Excluded = d.Source.Excluded
	}
	if len(p.AudioLang.Excluded) == 0 {
		p.AudioLang.Excluded = d.AudioLang.Excluded
	}

	// Preferred: only inherit from config when the URL param didn't set a preferred list.
	if len(p.Quality.Preferred) == 0 {
		p.Quality.Preferred = d.Quality.Preferred
	}
	if len(p.SourceType.Preferred) == 0 {
		p.SourceType.Preferred = d.SourceType.Preferred
	}
	if len(p.Source.Preferred) == 0 {
		p.Source.Preferred = d.Source.Preferred
	}
	if len(p.AudioLang.Preferred) == 0 {
		p.AudioLang.Preferred = d.AudioLang.Preferred
	}

	if len(p.ExcludeWords) == 0 && len(d.ExcludeWords) > 0 {
		p.ExcludeWords = d.ExcludeWords
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
