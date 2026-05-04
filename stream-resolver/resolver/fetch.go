package resolver

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/truedem0n/playbridge-stream-resolver/config"
	"github.com/truedem0n/playbridge-stream-resolver/types"
)

// FetchAll calls every configured source addon in parallel and returns all
// streams tagged with their source and position. Addons that fail or time out
// are logged and skipped — they never block the response.
func FetchAll(addons []config.SourceAddon, itemType, id string) []types.RankedStream {
	type result struct {
		streams []types.RankedStream
	}

	results := make([]result, len(addons))
	var wg sync.WaitGroup

	for i, addon := range addons {
		wg.Add(1)
		go func(idx int, a config.SourceAddon) {
			defer wg.Done()

			streams, err := fetchAddonStreams(a, itemType, id)
			if err != nil {
				log.Printf("[fetcher] %s: %v", a.Name, err)
				return
			}

			ranked := make([]types.RankedStream, 0, len(streams))
			for pos, s := range streams {
				ranked = append(ranked, types.RankedStream{
					Stream:     s,
					SourceName: a.Name,
					SourcePos:  pos,
				})
			}
			results[idx] = result{streams: ranked}
		}(i, addon)
	}

	wg.Wait()

	var all []types.RankedStream
	for _, r := range results {
		all = append(all, r.streams...)
	}
	return all
}

func fetchAddonStreams(a config.SourceAddon, itemType, id string) ([]types.Stream, error) {
	base := strings.TrimSuffix(strings.TrimSuffix(a.URL, "/"), "/manifest.json")
	url := fmt.Sprintf("%s/stream/%s/%s.json", base, itemType, id)

	client := &http.Client{Timeout: time.Duration(a.TimeoutMs) * time.Millisecond}
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return nil, fmt.Errorf("building request: %w", err)
	}
	req.Header.Set("User-Agent", "PlayBridge-StreamResolver/1.0")

	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("request to %s: %w", url, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("addon %s returned HTTP %d", a.Name, resp.StatusCode)
	}

	var sr types.StreamResponse
	if err := json.NewDecoder(resp.Body).Decode(&sr); err != nil {
		return nil, fmt.Errorf("decoding response from %s: %w", a.Name, err)
	}

	log.Printf("[fetcher] %s: got %d streams for %s/%s", a.Name, len(sr.Streams), itemType, id)
	return sr.Streams, nil
}
