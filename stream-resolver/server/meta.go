package server

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/truedem0n/playbridge-stream-resolver/config"
)

// runtimeMins returns the expected runtime in minutes for the given IMDB ID
// by querying the configured meta addons in order. The first addon that returns
// a non-zero runtime wins. Returns 0 when no addon has data — callers treat
// 0 as "unknown, skip probing validation".
func (s *Server) runtimeMins(imdbID string) int {
	if imdbID == "" {
		return 0
	}

	// Check cache first.
	cacheKey := "meta:" + imdbID
	var cached int
	if hit, _ := s.metaCache.Get(cacheKey, &cached); hit {
		return cached
	}

	s.mu.RLock()
	metaAddons := make([]config.MetaAddon, len(s.cfg.MetaAddons))
	copy(metaAddons, s.cfg.MetaAddons)
	s.mu.RUnlock()

	if len(metaAddons) == 0 {
		log.Printf("[meta] no meta addons configured, skipping runtime lookup for %s", imdbID)
		return 0
	}

	for _, addon := range metaAddons {
		if mins := fetchMetaAddonRuntime(addon, imdbID); mins > 0 {
			_ = s.metaCache.Set(cacheKey, mins)
			return mins
		}
	}

	log.Printf("[meta] no runtime found for %s across %d meta addon(s)", imdbID, len(metaAddons))
	return 0
}

// fetchMetaAddonRuntime queries a single meta addon for the runtime of the given
// IMDB ID. It tries "movie" first, then "series", and returns the first non-zero
// result. The Stremio meta endpoint is: {base}/meta/{type}/{id}.json
func fetchMetaAddonRuntime(a config.MetaAddon, imdbID string) int {
	base := strings.TrimSuffix(strings.TrimSuffix(a.URL, "/"), "/manifest.json")
	client := &http.Client{Timeout: time.Duration(a.TimeoutMs) * time.Millisecond}

	for _, itemType := range []string{"movie", "series"} {
		url := fmt.Sprintf("%s/meta/%s/%s.json", base, itemType, imdbID)

		resp, err := client.Get(url)
		if err != nil {
			log.Printf("[meta] %s: request failed for %s (%s): %v", a.Name, imdbID, itemType, err)
			continue
		}

		if resp.StatusCode != http.StatusOK {
			resp.Body.Close()
			continue
		}

		var body struct {
			Meta struct {
				Runtime string `json:"runtime"`
			} `json:"meta"`
		}
		err = json.NewDecoder(resp.Body).Decode(&body)
		resp.Body.Close()

		if err != nil {
			log.Printf("[meta] %s: decode error for %s: %v", a.Name, imdbID, err)
			continue
		}

		if mins := parseRuntimeMins(body.Meta.Runtime); mins > 0 {
			log.Printf("[meta] %s: runtime for %s = %d min (raw: %q, type: %s)",
				a.Name, imdbID, mins, body.Meta.Runtime, itemType)
			return mins
		}
	}

	return 0
}

// parseRuntimeMins converts strings like "142 min", "2h 22m", "1 h 30 min"
// into total minutes. Returns 0 on parse failure or "N/A".
func parseRuntimeMins(rt string) int {
	rt = strings.ToLower(strings.TrimSpace(rt))
	if rt == "" || rt == "n/a" {
		return 0
	}

	hRe := regexp.MustCompile(`(\d+)\s*h`)
	mRe := regexp.MustCompile(`(\d+)\s*m`)

	hours, mins := 0, 0
	if m := hRe.FindStringSubmatch(rt); len(m) > 1 {
		hours, _ = strconv.Atoi(m[1])
	}
	if m := mRe.FindStringSubmatch(rt); len(m) > 1 {
		mins, _ = strconv.Atoi(m[1])
	}
	if hours == 0 && mins == 0 {
		// Fallback: bare number treated as minutes ("142 min")
		if m := regexp.MustCompile(`(\d+)`).FindStringSubmatch(rt); len(m) > 1 {
			mins, _ = strconv.Atoi(m[1])
		}
	}
	return hours*60 + mins
}

// imdbIDFromStremioID extracts the IMDB ID from a Stremio content ID.
// Stremio IDs for movies are bare IMDB IDs ("tt1234567").
// For series they are "tt1234567:season:episode" — we only need the first part.
func imdbIDFromStremioID(id string) string {
	parts := strings.SplitN(id, ":", 2)
	if strings.HasPrefix(parts[0], "tt") {
		return parts[0]
	}
	return ""
}
