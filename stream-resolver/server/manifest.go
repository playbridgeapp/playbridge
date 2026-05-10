package server

import (
	"encoding/json"
	"net/http"

	"github.com/truedem0n/playbridge-stream-resolver/types"
)

func (s *Server) handleManifest(w http.ResponseWriter, r *http.Request) {
	manifest := types.Manifest{
		ID:          "com.playbridge.stream-resolver",
		Name:        "Stream Resolver",
		Description: "Aggregates streams from multiple Stremio addons, ranks them, and validates with ffprobe.",
		Version:     "1.0.0",
		Resources:   []string{"stream"},
		Types:       []string{"movie", "series"},
		Catalogs:    []any{},
		BehaviorHints: types.ManifestHints{
			Configurable: true,
			PlayEndpoint: "/api/play/{type}/{id}",
		},
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(manifest)
}
