package config

import (
	"encoding/json"
	"fmt"
	"os"
)

// SourceAddon is a configured upstream Stremio stream addon.
type SourceAddon struct {
	URL       string `json:"url"`
	Name      string `json:"name"`
	Priority  int    `json:"priority"`   // lower = higher priority in merge
	TimeoutMs int    `json:"timeout_ms"` // per-addon HTTP timeout, default 8000
}

// MetaAddon is a Stremio meta addon used to look up runtime for probing validation.
// Addons are queried in order; the first one that returns a runtime wins.
type MetaAddon struct {
	URL       string `json:"url"`
	Name      string `json:"name"`
	TimeoutMs int    `json:"timeout_ms"` // per-request HTTP timeout, default 5000
}

// ProbingConfig controls ffprobe-based stream validation.
type ProbingConfig struct {
	Enabled     bool `json:"enabled"`
	MaxAttempts int  `json:"max_attempts"` // max streams to probe before giving up, default 5
	TimeoutMs   int  `json:"timeout_ms"`   // per-probe ffprobe timeout, default 5000
}

// CacheConfig controls TTLs for on-disk caches.
type CacheConfig struct {
	StreamListTTLSeconds int `json:"stream_list_ttl_seconds"` // merged stream list, default 300
	PlayResultTTLSeconds int `json:"play_result_ttl_seconds"` // resolved play URL, default 3600
	MetaTTLSeconds       int `json:"meta_ttl_seconds"`        // runtime lookup cache, default 86400
}

// DefaultsConfig holds server-side streaming preference defaults applied when
// a /api/play request omits the corresponding query parameter.
// Explicit query params always take precedence over these defaults.
type DefaultsConfig struct {
	Quality    string   `json:"quality"`     // "Auto", "4K", "1080p", "720p", "480p" — empty = any
	SourceType []string `json:"source_type"` // e.g. ["web-dl","remux"] — nil/empty = any
	Source     string   `json:"source"`      // addon name substring — empty = any
	AudioLang  string   `json:"audio_lang"`  // "en", "multi", etc. — empty = any
	MinSize    float64  `json:"min_size"`    // GB — 0 = no limit
	MaxSize    float64  `json:"max_size"`    // GB — 0 = no limit
	MinBitrate float64  `json:"min_bitrate"` // Mbps — 0 = no limit
	MaxBitrate float64  `json:"max_bitrate"` // Mbps — 0 = no limit
}

// Config is the top-level addon configuration.
type Config struct {
	Port       int            `json:"port"`     // HTTP listen port, default 7000
	BaseURL    string         `json:"base_url"` // public base URL e.g. http://localhost:7000
	Addons     []SourceAddon  `json:"addons"`
	MetaAddons []MetaAddon    `json:"meta_addons"` // used to fetch runtime for probing validation
	Probing    ProbingConfig  `json:"probing"`
	Cache      CacheConfig    `json:"cache"`
	Defaults   DefaultsConfig `json:"defaults"` // server-side streaming preference defaults
}

// Load reads and parses the config file at the given path, applying defaults
// for any missing numeric fields.
func Load(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("reading config: %w", err)
	}

	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil, fmt.Errorf("parsing config: %w", err)
	}

	applyDefaults(&cfg)
	return &cfg, nil
}

func applyDefaults(cfg *Config) {
	if cfg.Port == 0 {
		cfg.Port = 7000
	}
	if cfg.BaseURL == "" {
		cfg.BaseURL = fmt.Sprintf("http://localhost:%d", cfg.Port)
	}

	for i := range cfg.Addons {
		if cfg.Addons[i].TimeoutMs == 0 {
			cfg.Addons[i].TimeoutMs = 8000
		}
		if cfg.Addons[i].Name == "" {
			cfg.Addons[i].Name = fmt.Sprintf("Addon %d", i+1)
		}
	}

	for i := range cfg.MetaAddons {
		if cfg.MetaAddons[i].TimeoutMs == 0 {
			cfg.MetaAddons[i].TimeoutMs = 5000
		}
		if cfg.MetaAddons[i].Name == "" {
			cfg.MetaAddons[i].Name = fmt.Sprintf("Meta Addon %d", i+1)
		}
	}

	if cfg.Probing.MaxAttempts == 0 {
		cfg.Probing.MaxAttempts = 5
	}
	if cfg.Probing.TimeoutMs == 0 {
		cfg.Probing.TimeoutMs = 15000 // debrid URLs involve multi-hop redirects; 5s was too tight
	}

	if cfg.Cache.StreamListTTLSeconds == 0 {
		cfg.Cache.StreamListTTLSeconds = 300
	}
	if cfg.Cache.PlayResultTTLSeconds == 0 {
		cfg.Cache.PlayResultTTLSeconds = 3600
	}
	if cfg.Cache.MetaTTLSeconds == 0 {
		cfg.Cache.MetaTTLSeconds = 86400
	}
}
