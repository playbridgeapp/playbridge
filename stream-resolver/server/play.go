package server

import (
	"fmt"
	"log"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"unicode"

	"github.com/truedem0n/playbridge-stream-resolver/prober"
	"github.com/truedem0n/playbridge-stream-resolver/types"
)

type PlayPrefs struct {
	Quality     string
	SourceType  []string
	Source      string
	MinSize     float64
	MaxSize     float64
	MinBitrate  float64
	MaxBitrate  float64
	AudioLang   string
	NoCache     bool
	ProbeOff    bool
	Skip        int
}

func (s *Server) handlePlay(w http.ResponseWriter, r *http.Request) {
	itemType := r.PathValue("type")
	id := strings.TrimSuffix(r.PathValue("id"), ".json")

	if itemType == "" || id == "" {
		http.Error(w, "missing type or id", http.StatusBadRequest)
		return
	}

	prefs := parsePlayPrefs(r)
	s.mu.RLock()
	prefs = applyStreamingDefaults(prefs, s.cfg.Defaults)
	s.mu.RUnlock()
	log.Printf("[play] %s/%s skip=%d prefs=%+v", itemType, id, prefs.Skip, prefs)

	// Check play result cache
	playCacheKey := itemType + "/" + id + prefsKey(prefs)
	if prefs.Skip == 0 && !prefs.NoCache {
		var cachedURL string
		if hit, _ := s.playCache.Get(playCacheKey, &cachedURL); hit && cachedURL != "" {
			log.Printf("[play] cache hit → %s", truncateURL(cachedURL))
			http.Redirect(w, r, cachedURL, http.StatusTemporaryRedirect)
			return
		}
	}

	// Get merged ranked stream list (shared with /stream endpoint via cache).
	streams, err := s.getStreamList(itemType, id)
	if err != nil || len(streams) == 0 {
		log.Printf("[play] no streams for %s/%s", itemType, id)
		http.Error(w, "no streams available", http.StatusServiceUnavailable)
		return
	}

	// Fetch expected runtime for duration validation.
	// Tries OMDB first (if key configured), falls back to Cinemeta.
	// Returns 0 only when both sources have no data — probing is skipped in that case.
	imdbID := imdbIDFromStremioID(id)
	expectedMins := s.runtimeMins(imdbID)
	if expectedMins > 0 {
		log.Printf("[play] expected runtime: %d min", expectedMins)
	}

	// Reorder streams by source-type preference. Rather than strict filtering,
	// preferred types bubble to the top (in declared order), followed by
	// unclassified streams, then everything else. matchesPrefs is then called
	// without a SourceType constraint so the reordering acts as the selector.
	prefsForMatch := prefs
	if len(prefs.SourceType) > 0 {
		streams = reorderBySourceTypePref(streams, prefs.SourceType)
		prefsForMatch.SourceType = nil
	}

	probingEnabled := s.cfg.Probing.Enabled && prober.Available()
	maxAttempts := s.cfg.Probing.MaxAttempts

	probeCount := 0
	for i := prefs.Skip; i < len(streams); i++ {
		rs := streams[i]
		url := rs.Stream.URL

		// Skip streams with no direct URL (infoHash-only, etc.)
		if url == "" {
			continue
		}

		if !matchesPrefs(rs, prefsForMatch, expectedMins) {
			continue
		}

		if !probingEnabled || prefs.ProbeOff || expectedMins <= 10 {
			// Probing disabled or runtime unknown/too short to be meaningful —
			// return the first stream with a URL directly.
			log.Printf("[play] no probing, using stream %d from %s: %s",
				i, rs.SourceName, rs.Stream.Name)
			cacheAndRedirect(s, w, r, playCacheKey, prefs.Skip, url)
			return
		}

		if probeCount >= maxAttempts {
			log.Printf("[play] reached max probe attempts (%d), giving up", maxAttempts)
			break
		}
		probeCount++

		log.Printf("[play] probing [%d/%d] %s (pos %d from %s)",
			probeCount, maxAttempts, rs.Stream.Name, rs.SourcePos, rs.SourceName)

		durationMins, err := prober.ProbeDuration(url, s.cfg.Probing.TimeoutMs)
		if err != nil {
			log.Printf("[play] probe failed: %v — skipping", err)
			continue
		}
		if durationMins == 0 {
			log.Printf("[play] probe returned 0 min — skipping")
			continue
		}
		if durationMins < expectedMins/2 {
			log.Printf("[play] probe too short (%d min vs expected %d min) — skipping",
				durationMins, expectedMins)
			continue
		}

		log.Printf("[play] probe passed (%d min), redirecting to stream %d", durationMins, i)
		cacheAndRedirect(s, w, r, playCacheKey, prefs.Skip, url)
		return
	}

	log.Printf("[play] no working stream found for %s/%s", itemType, id)
	http.Error(w, "no working stream found", http.StatusNotFound)
}

func truncateURL(url string) string {
	if len(url) > 80 {
		return url[:80] + "..."
	}
	return url
}

func cacheAndRedirect(s *Server, w http.ResponseWriter, r *http.Request,
	cacheKey string, skip int, url string) {
	// Only cache the result when we resolved from position 0 — a skip>0 result
	// is a manual retry and should not overwrite the canonical cached pick.
	if skip == 0 {
		_ = s.playCache.Set(cacheKey, url)
	}
	http.Redirect(w, r, url, http.StatusTemporaryRedirect)
}

func parsePlayPrefs(r *http.Request) PlayPrefs {
	q := r.URL.Query()
	p := PlayPrefs{
		Quality:    q.Get("quality"),
		Source:     q.Get("source"),
		AudioLang:  q.Get("audioLang"),
		NoCache:    q.Get("noCache") == "1",
		ProbeOff:   q.Get("probe") == "0",
	}

	if v := q.Get("sourceType"); v != "" {
		p.SourceType = strings.Split(v, ",")
	}
	if v := q.Get("minSize"); v != "" {
		p.MinSize, _ = strconv.ParseFloat(v, 64)
	}
	if v := q.Get("maxSize"); v != "" {
		p.MaxSize, _ = strconv.ParseFloat(v, 64)
	}
	if v := q.Get("minBitrate"); v != "" {
		p.MinBitrate, _ = strconv.ParseFloat(v, 64)
	}
	if v := q.Get("maxBitrate"); v != "" {
		p.MaxBitrate, _ = strconv.ParseFloat(v, 64)
	}
	if v := q.Get("skip"); v != "" {
		p.Skip, _ = strconv.Atoi(v)
	}

	return p
}

func prefsKey(p PlayPrefs) string {
	var b strings.Builder
	if p.Quality != "" {
		fmt.Fprintf(&b, "&q=%s", p.Quality)
	}
	if len(p.SourceType) > 0 {
		fmt.Fprintf(&b, "&st=%s", strings.Join(p.SourceType, ","))
	}
	if p.Source != "" {
		fmt.Fprintf(&b, "&src=%s", p.Source)
	}
	if p.MinSize > 0 {
		fmt.Fprintf(&b, "&min=%.2f", p.MinSize)
	}
	if p.MaxSize > 0 {
		fmt.Fprintf(&b, "&max=%.2f", p.MaxSize)
	}
	if p.MinBitrate > 0 {
		fmt.Fprintf(&b, "&minbr=%.2f", p.MinBitrate)
	}
	if p.MaxBitrate > 0 {
		fmt.Fprintf(&b, "&maxbr=%.2f", p.MaxBitrate)
	}
	if p.AudioLang != "" {
		fmt.Fprintf(&b, "&lang=%s", p.AudioLang)
	}
	if b.Len() > 0 {
		return "| " + b.String()[1:] // prefix with | and strip leading &
	}
	return ""
}

func matchesPrefs(rs types.RankedStream, p PlayPrefs, runtimeMins int) bool {
	s := rs.Stream

	// 1. Quality matching
	if p.Quality != "" && p.Quality != "any" {
		tier := qualityTier(s)
		if tier != "" && !strings.EqualFold(tier, p.Quality) {
			return false
		}
	}

	// 2. Source Type matching
	if len(p.SourceType) > 0 && !contains(p.SourceType, "any") {
		matches := false
		st := detectedSourceType(s)
		for _, wanted := range p.SourceType {
			if strings.EqualFold(wanted, st) {
				matches = true
				break
			}
		}
		if !matches {
			return false
		}
	}

	// 3. Source matching
	if p.Source != "" {
		if !strings.Contains(strings.ToLower(rs.SourceName), strings.ToLower(p.Source)) {
			return false
		}
	}

	// 4. Size matching
	size := sizeGB(s)
	if size > 0 {
		if p.MinSize > 0 && size < p.MinSize {
			return false
		}
		if p.MaxSize > 0 && size > p.MaxSize {
			return false
		}
	}

	// 5. Bitrate matching
	br := bitrateOf(rs, runtimeMins)
	if br > 0 {
		if p.MinBitrate > 0 && br < p.MinBitrate {
			return false
		}
		if p.MaxBitrate > 0 && br > p.MaxBitrate {
			return false
		}
	}

	// 6. Audio Language matching
	if p.AudioLang != "" && p.AudioLang != "any" {
		if !audioLangMatches(s, p.AudioLang) {
			return false
		}
	}

	return true
}

func qualityTier(s types.Stream) string {
	name := stripZeroWidth(s.Name)
	nameLower := strings.ToLower(name)
	if strings.Contains(nameLower, "2160") || strings.Contains(nameLower, "4k") || strings.Contains(nameLower, "uhd") {
		return "4K"
	}
	if strings.Contains(nameLower, "1080") {
		return "1080p"
	}
	if strings.Contains(nameLower, "720") {
		return "720p"
	}
	if strings.Contains(nameLower, "480") {
		return "480p"
	}
	return ""
}

func stripZeroWidth(s string) string {
	r := strings.ReplaceAll(s, "\u200d", "") // zero-width joiner
	r = strings.ReplaceAll(r, "\u200c", "")  // zero-width non-joiner
	r = strings.ReplaceAll(r, "\u200b", "")  // zero-width space
	r = strings.ReplaceAll(r, "\ufeff", "")  // BOM / zero-width no-break space
	return r
}

func detectedSourceType(s types.Stream) string {
	content := sanitize(s.Name + " " + s.Title + " " + s.Description)
	content = strings.ToLower(content)

	if strings.Contains(content, "remux") {
		return "remux"
	}
	if strings.Contains(content, "bluray") || strings.Contains(content, "blu-ray") {
		return "bluray"
	}
	if strings.Contains(content, "web-dl") || strings.Contains(content, "webdl") {
		return "web-dl"
	}
	if strings.Contains(content, "webrip") {
		return "webrip"
	}
	if strings.Contains(content, "hdtv") {
		return "hdtv"
	}
	if strings.Contains(content, "dvd") {
		return "dvd"
	}
	if wordBoundaryMatch(content, "cam") || wordBoundaryMatch(content, "ts") {
		return "cam"
	}
	return "other"
}

// reorderBySourceTypePref reorders streams so those matching preferred source
// types come first (in preference order), then unclassified streams ("other"),
// then all remaining classified streams. Within each bucket the original
// ranking is preserved.
func reorderBySourceTypePref(streams []types.RankedStream, preferred []string) []types.RankedStream {
	if len(preferred) == 0 {
		return streams
	}
	buckets := make([][]types.RankedStream, len(preferred))
	var otherBucket, restBucket []types.RankedStream

	for _, rs := range streams {
		st := detectedSourceType(rs.Stream)
		placed := false
		for i, want := range preferred {
			if strings.EqualFold(want, st) {
				buckets[i] = append(buckets[i], rs)
				placed = true
				break
			}
		}
		if !placed {
			if st == "other" {
				otherBucket = append(otherBucket, rs)
			} else {
				restBucket = append(restBucket, rs)
			}
		}
	}

	result := make([]types.RankedStream, 0, len(streams))
	for _, b := range buckets {
		result = append(result, b...)
	}
	result = append(result, otherBucket...)
	result = append(result, restBucket...)
	return result
}

var (
	sizeRegex    = regexp.MustCompile(`(\d+\.?\d*)\s*(TB|GB|MB)`)
	bitrateRegex = regexp.MustCompile(`(\d+\.?\d*)\s*(?i:Mbps|ᴹᵇᵖˢ)`)
)

func sizeGB(s types.Stream) float64 {
	texts := []string{s.Description, s.Name, s.Title}
	for _, t := range texts {
		m := sizeRegex.FindStringSubmatch(t)
		if len(m) == 3 {
			val, _ := strconv.ParseFloat(m[1], 64)
			unit := strings.ToUpper(m[2])
			switch unit {
			case "TB":
				return val * 1024
			case "GB":
				return val
			case "MB":
				return val / 1024
			}
		}
	}
	return 0
}

func bitrateOf(rs types.RankedStream, runtimeMins int) float64 {
	s := rs.Stream
	texts := []string{s.Description, s.Name, s.Title}
	for _, t := range texts {
		m := bitrateRegex.FindStringSubmatch(t)
		if len(m) == 2 {
			val, _ := strconv.ParseFloat(m[1], 64)
			return val
		}
	}

	// Fallback to size / runtime
	size := sizeGB(s)
	if size > 0 && runtimeMins > 0 {
		// (sizeGB * 1024 * 1024 * 1024 * 8) / (runtimeMins * 60 * 1,000,000)
		return (size * 1073741824 * 8) / (float64(runtimeMins) * 60 * 1000000)
	}

	return 0
}

func audioLangMatches(s types.Stream, lang string) bool {
	content := strings.ToLower(s.Name + " " + s.Description)
	content = normalizeSmallCaps(content)

	tokens := map[string][]string{
		"en":    {"en", "english", "eng"},
		"multi": {"multi", "multilang", "dual", "multi audio"},
		"fr":    {"fr", "french", "français"},
		"es":    {"es", "spanish", "español"},
		"de":    {"de", "german", "deutsch"},
	}

	targetTokens, ok := tokens[lang]
	if !ok {
		// Custom language match
		return strings.Contains(content, strings.ToLower(lang))
	}

	// Check if ANY token for the target language matches
	langMatched := false
	for _, t := range targetTokens {
		if strings.Contains(content, t) {
			langMatched = true
			break
		}
	}
	if langMatched {
		return true
	}

	// If no match for target, check if it matches ANY other known language.
	// If it doesn't match ANY known language, it's "undetectable" and we let it through.
	hasAnyKnownLang := false
	for _, otherTokens := range tokens {
		for _, t := range otherTokens {
			if strings.Contains(content, t) {
				hasAnyKnownLang = true
				break
			}
		}
		if hasAnyKnownLang {
			break
		}
	}

	return !hasAnyKnownLang
}

func normalizeSmallCaps(s string) string {
	replacements := map[string]string{
		"ᴇɴ":    "en",
		"ᴍᴜʟᴛɪ": "multi",
		"ғʀ":    "fr",
		"ᴇs":    "es",
		"ᴅᴇ":    "de",
	}
	for k, v := range replacements {
		s = strings.ReplaceAll(s, k, v)
	}
	return s
}

func sanitize(s string) string {
	var b strings.Builder
	for _, r := range s {
		if unicode.IsLetter(r) || unicode.IsDigit(r) || r == '-' || r == '.' || r == ' ' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func wordBoundaryMatch(content, token string) bool {
	re := regexp.MustCompile(`(?i)\b` + regexp.QuoteMeta(token) + `\b`)
	return re.MatchString(content)
}

func contains(slice []string, val string) bool {
	for _, s := range slice {
		if strings.EqualFold(s, val) {
			return true
		}
	}
	return false
}
