package server

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"unicode"

	"github.com/truedem0n/playbridge-stream-resolver/config"
	"github.com/truedem0n/playbridge-stream-resolver/prober"
	"github.com/truedem0n/playbridge-stream-resolver/types"
)

type playCandidate struct {
	idx int
	rs  types.RankedStream
	url string
}

type PlayPrefs struct {
	Quality      config.Bucket
	SourceType   config.Bucket
	Source       config.Bucket
	AudioLang    config.Bucket
	ExcludeWords []string
	MinSize      float64
	MaxSize      float64
	MinBitrate   float64
	MaxBitrate   float64
	NoCache      bool
	ProbeOff     bool
	Debug        bool
	Skip         int
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

	playCacheKey := itemType + "/" + id + prefsKey(prefs)
	if prefs.Skip == 0 && !prefs.NoCache {
		var cachedURL string
		if hit, _ := s.playCache.Get(playCacheKey, &cachedURL); hit && cachedURL != "" {
			log.Printf("[play] cache hit → %s", truncateURL(cachedURL))
			http.Redirect(w, r, cachedURL, http.StatusTemporaryRedirect)
			return
		}
	}

	streams, err := s.getStreamList(itemType, id)
	if err != nil || len(streams) == 0 {
		log.Printf("[play] no streams for %s/%s", itemType, id)
		http.Error(w, "no streams available", http.StatusServiceUnavailable)
		return
	}

	imdbID := imdbIDFromStremioID(id)
	expectedMins := s.runtimeMins(imdbID)
	if expectedMins > 0 {
		log.Printf("[play] expected runtime: %d min", expectedMins)
	}

	// Partition all streams into preferred and fallback, dropping excluded and
	// those failing hard (size/bitrate) filters.
	var preferred, fallback []playCandidate
	for i := prefs.Skip; i < len(streams); i++ {
		rs := streams[i]
		url := rs.Stream.URL
		if url == "" {
			continue
		}
		if !passesHardFilters(rs, prefs, expectedMins) {
			continue
		}
		if isExcluded(rs, prefs) {
			continue
		}
		c := playCandidate{i, rs, url}
		if isPreferred(rs, prefs) {
			preferred = append(preferred, c)
		} else {
			fallback = append(fallback, c)
		}
	}

	log.Printf("[play] partitioned: %d preferred, %d fallback", len(preferred), len(fallback))

	// Debug mode: return candidates as JSON without probing or redirecting.
	if prefs.Debug {
		type debugEntry struct {
			Idx        int    `json:"idx"`
			Name       string `json:"name"`
			URL        string `json:"url"`
			SourceName string `json:"source_name"`
			SourcePos  int    `json:"source_pos"`
			Score      int    `json:"score"`
		}
		toEntries := func(cs []playCandidate) []debugEntry {
			out := make([]debugEntry, len(cs))
			for i, c := range cs {
				out[i] = debugEntry{c.idx, c.rs.Stream.Name, c.url, c.rs.SourceName, c.rs.SourcePos, c.rs.Score}
			}
			return out
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"expected_mins": expectedMins,
			"preferred":     toEntries(preferred),
			"fallback":      toEntries(fallback),
		})
		return
	}

	probingEnabled := s.cfg.Probing.Enabled && prober.Available()
	doProbe := probingEnabled && !prefs.ProbeOff && expectedMins > 10

	if !doProbe {
		// No probing: return first preferred stream, then first fallback.
		for _, c := range preferred {
			log.Printf("[play] no probing, using preferred stream %d from %s: %s", c.idx, c.rs.SourceName, c.rs.Stream.Name)
			cacheAndRedirect(s, w, r, playCacheKey, prefs.Skip, c.url)
			return
		}
		for _, c := range fallback {
			log.Printf("[play] no probing, using fallback stream %d from %s: %s", c.idx, c.rs.SourceName, c.rs.Stream.Name)
			cacheAndRedirect(s, w, r, playCacheKey, prefs.Skip, c.url)
			return
		}
		log.Printf("[play] no working stream found for %s/%s", itemType, id)
		http.Error(w, "no working stream found", http.StatusNotFound)
		return
	}

	// Probe pass 1: preferred candidates.
	if url, ok := probeFirst(preferred, s.cfg.Probing.TimeoutMs, expectedMins, "preferred"); ok {
		cacheAndRedirect(s, w, r, playCacheKey, prefs.Skip, url)
		return
	}

	// Probe pass 2: fallback candidates.
	if len(fallback) > 0 {
		log.Printf("[play] preferred pass exhausted, trying %d fallback streams", len(fallback))
		if url, ok := probeFirst(fallback, s.cfg.Probing.TimeoutMs, expectedMins, "fallback"); ok {
			cacheAndRedirect(s, w, r, playCacheKey, prefs.Skip, url)
			return
		}
	}

	log.Printf("[play] no working stream found for %s/%s", itemType, id)
	http.Error(w, "no working stream found", http.StatusNotFound)
}

// probeFirst probes all candidates in parallel and returns the URL of the
// highest-ranked candidate whose duration passes validation.
func probeFirst(candidates []playCandidate, timeoutMs, expectedMins int, pass string) (string, bool) {
	if len(candidates) == 0 {
		return "", false
	}

	type result struct {
		durationMins int
		err          error
	}

	results := make([]result, len(candidates))
	var wg sync.WaitGroup
	for ci, c := range candidates {
		wg.Add(1)
		go func(ci int, c playCandidate) {
			defer wg.Done()
			log.Printf("[play] probing [%s %d/%d] %s (pos %d from %s)",
				pass, ci+1, len(candidates), c.rs.Stream.Name, c.rs.SourcePos, c.rs.SourceName)
			d, err := prober.ProbeDuration(c.url, timeoutMs)
			results[ci] = result{d, err}
		}(ci, c)
	}
	wg.Wait()

	for ci, res := range results {
		c := candidates[ci]
		if res.err != nil {
			log.Printf("[play] probe failed: %v — skipping", res.err)
			continue
		}
		if res.durationMins == 0 {
			log.Printf("[play] probe returned 0 min — skipping")
			continue
		}
		if res.durationMins < expectedMins/2 {
			log.Printf("[play] probe too short (%d min vs expected %d min) — skipping",
				res.durationMins, expectedMins)
			continue
		}
		log.Printf("[play] probe passed (%d min), using stream %d", res.durationMins, c.idx)
		return c.url, true
	}
	return "", false
}

// isExcluded returns true if the stream matches any excluded bucket in any dimension,
// or contains an excluded word.
func isExcluded(rs types.RankedStream, p PlayPrefs) bool {
	s := rs.Stream

	if len(p.ExcludeWords) > 0 {
		content := strings.ToLower(s.Name + " " + s.Title + " " + s.Description)
		for _, w := range p.ExcludeWords {
			if w != "" && strings.Contains(content, strings.ToLower(w)) {
				return true
			}
		}
	}

	if len(p.Quality.Excluded) > 0 {
		if tier := qualityTier(s); tier != "" && containsCI(p.Quality.Excluded, tier) {
			return true
		}
	}

	if len(p.SourceType.Excluded) > 0 {
		// Skip "other" — unclassified streams should not be excludable by a
		// blanket source-type rule. Users excluding everything unknown can do
		// so explicitly via the exclude_words list.
		st := detectedSourceType(s)
		if st != "other" && containsCI(p.SourceType.Excluded, st) {
			return true
		}
	}

	if len(p.Source.Excluded) > 0 {
		for _, excl := range p.Source.Excluded {
			if strings.EqualFold(rs.SourceName, excl) {
				return true
			}
		}
	}

	if len(p.AudioLang.Excluded) > 0 {
		for _, lang := range p.AudioLang.Excluded {
			if audioLangPositive(s, lang) {
				return true
			}
		}
	}

	return false
}

// isPreferred returns true if the stream matches all non-empty preferred buckets.
// A dimension with no preferred values does not constrain the result, so when
// every dimension is empty all non-excluded streams are treated as preferred
// (pass 1 holds everything, pass 2 is empty) — equivalent to legacy behavior.
func isPreferred(rs types.RankedStream, p PlayPrefs) bool {
	s := rs.Stream

	if len(p.Quality.Preferred) > 0 {
		tier := qualityTier(s)
		if !containsCI(p.Quality.Preferred, tier) {
			return false
		}
	}

	if len(p.SourceType.Preferred) > 0 {
		if !containsCI(p.SourceType.Preferred, detectedSourceType(s)) {
			return false
		}
	}

	if len(p.Source.Preferred) > 0 {
		matched := false
		for _, src := range p.Source.Preferred {
			if strings.EqualFold(rs.SourceName, src) {
				matched = true
				break
			}
		}
		if !matched {
			return false
		}
	}

	if len(p.AudioLang.Preferred) > 0 {
		matched := false
		for _, lang := range p.AudioLang.Preferred {
			if audioLangMatches(s, lang) {
				matched = true
				break
			}
		}
		if !matched {
			return false
		}
	}

	return true
}

// passesHardFilters checks size and bitrate bounds — these apply to both passes.
func passesHardFilters(rs types.RankedStream, p PlayPrefs, runtimeMins int) bool {
	size := sizeGB(rs.Stream)
	if size > 0 {
		if p.MinSize > 0 && size < p.MinSize {
			return false
		}
		if p.MaxSize > 0 && size > p.MaxSize {
			return false
		}
	}
	br := bitrateOf(rs, runtimeMins)
	if br > 0 {
		if p.MinBitrate > 0 && br < p.MinBitrate {
			return false
		}
		if p.MaxBitrate > 0 && br > p.MaxBitrate {
			return false
		}
	}
	return true
}

func truncateURL(url string) string {
	if len(url) > 80 {
		return url[:80] + "..."
	}
	return url
}

func cacheAndRedirect(s *Server, w http.ResponseWriter, r *http.Request,
	cacheKey string, skip int, url string) {
	if skip == 0 {
		_ = s.playCache.Set(cacheKey, url)
	}
	http.Redirect(w, r, url, http.StatusTemporaryRedirect)
}

func parsePlayPrefs(r *http.Request) PlayPrefs {
	q := r.URL.Query()
	p := PlayPrefs{
		NoCache:  q.Get("noCache") == "1",
		ProbeOff: q.Get("probe") == "0",
		Debug:    q.Get("debug") == "1",
	}
	if v := q.Get("quality"); v != "" {
		p.Quality.Preferred = []string{v}
	}
	if v := q.Get("sourceType"); v != "" {
		p.SourceType.Preferred = strings.Split(v, ",")
	}
	if v := q.Get("source"); v != "" {
		p.Source.Preferred = []string{v}
	}
	if v := q.Get("audioLang"); v != "" {
		p.AudioLang.Preferred = []string{v}
	}
	if v := q.Get("exclude"); v != "" {
		p.ExcludeWords = strings.Split(v, ",")
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
	writeBucket := func(prefix string, bkt config.Bucket) {
		if len(bkt.Preferred) > 0 {
			fmt.Fprintf(&b, "&%sp=%s", prefix, strings.Join(bkt.Preferred, ","))
		}
		if len(bkt.Excluded) > 0 {
			fmt.Fprintf(&b, "&%se=%s", prefix, strings.Join(bkt.Excluded, ","))
		}
	}
	writeBucket("q", p.Quality)
	writeBucket("st", p.SourceType)
	writeBucket("src", p.Source)
	writeBucket("al", p.AudioLang)
	if len(p.ExcludeWords) > 0 {
		fmt.Fprintf(&b, "&excl=%s", strings.Join(p.ExcludeWords, ","))
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
	if b.Len() > 0 {
		return "| " + b.String()[1:]
	}
	return ""
}

func qualityTier(s types.Stream) string {
	name := stripZeroWidth(s.Name)
	nameLower := strings.ToLower(name)
	if strings.Contains(nameLower, "2160") || wordBoundaryMatch(nameLower, "4k") || wordBoundaryMatch(nameLower, "uhd") {
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
	r := strings.ReplaceAll(s, "‍", "")
	r = strings.ReplaceAll(r, "‌", "")
	r = strings.ReplaceAll(r, "​", "")
	r = strings.ReplaceAll(r, "\ufeff", "")
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
	size := sizeGB(s)
	if size > 0 && runtimeMins > 0 {
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
		"pt":    {"pt", "portuguese", "português"},
		"it":    {"it", "italian", "italiano"},
		"ja":    {"ja", "japanese"},
		"ko":    {"ko", "korean"},
		"zh":    {"zh", "chinese", "mandarin"},
		"ru":    {"ru", "russian"},
		"ar":    {"ar", "arabic"},
		"hi":    {"hi", "hindi"},
	}

	targetTokens, ok := tokens[lang]
	if !ok {
		return strings.Contains(content, strings.ToLower(lang))
	}

	for _, t := range targetTokens {
		if strings.Contains(content, t) {
			return true
		}
	}

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

// audioLangPositive returns true only when the language is positively detected —
// used for exclusion so undetectable streams are not accidentally excluded.
func audioLangPositive(s types.Stream, lang string) bool {
	content := strings.ToLower(s.Name + " " + s.Description)
	content = normalizeSmallCaps(content)

	tokens := map[string][]string{
		"en":    {"en", "english", "eng"},
		"multi": {"multi", "multilang", "dual", "multi audio"},
		"fr":    {"fr", "french", "français"},
		"es":    {"es", "spanish", "español"},
		"de":    {"de", "german", "deutsch"},
		"pt":    {"pt", "portuguese", "português"},
		"it":    {"it", "italian", "italiano"},
		"ja":    {"ja", "japanese"},
		"ko":    {"ko", "korean"},
		"zh":    {"zh", "chinese", "mandarin"},
		"ru":    {"ru", "russian"},
		"ar":    {"ar", "arabic"},
		"hi":    {"hi", "hindi"},
	}

	targetTokens, ok := tokens[lang]
	if !ok {
		return strings.Contains(content, strings.ToLower(lang))
	}
	for _, t := range targetTokens {
		if strings.Contains(content, t) {
			return true
		}
	}
	return false
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

func containsCI(slice []string, val string) bool {
	for _, s := range slice {
		if strings.EqualFold(s, val) {
			return true
		}
	}
	return false
}
