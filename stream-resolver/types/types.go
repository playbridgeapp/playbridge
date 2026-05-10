package types

// Stream represents a single Stremio stream entry.
type Stream struct {
	Name        string        `json:"name,omitempty"`
	Title       string        `json:"title,omitempty"`
	Description string        `json:"description,omitempty"`
	URL         string        `json:"url,omitempty"`
	InfoHash    string        `json:"infoHash,omitempty"`
	FileIdx     int           `json:"fileIdx,omitempty"`
	BehaviorHints *BehaviorHints `json:"behaviorHints,omitempty"`
}

// BehaviorHints carries Stremio stream hints.
type BehaviorHints struct {
	NotWebReady    bool   `json:"notWebReady,omitempty"`
	BingeGroup     string `json:"bingeGroup,omitempty"`
	ProxyHeaders   *ProxyHeaders `json:"proxyHeaders,omitempty"`
}

// ProxyHeaders carries optional proxy request headers.
type ProxyHeaders struct {
	Request map[string]string `json:"request,omitempty"`
}

// StreamResponse is the Stremio stream endpoint response.
type StreamResponse struct {
	Streams []Stream `json:"streams"`
}

// Manifest is the Stremio addon manifest.
type Manifest struct {
	ID            string        `json:"id"`
	Name          string        `json:"name"`
	Description   string        `json:"description"`
	Version       string        `json:"version"`
	Resources     []string      `json:"resources"`
	Types         []string      `json:"types"`
	Catalogs      []any         `json:"catalogs"`
	BehaviorHints ManifestHints `json:"behaviorHints,omitempty"`
}

// ManifestHints carries addon-level behavior hints.
type ManifestHints struct {
	Configurable bool   `json:"configurable,omitempty"`
	PlayEndpoint string `json:"playEndpoint,omitempty"`
}

// RankedStream is a stream enriched with ranking metadata for internal use.
type RankedStream struct {
	Stream       Stream
	SourceName   string
	SourcePos    int // position within the source addon's response (0-based)
	Score        int
}
