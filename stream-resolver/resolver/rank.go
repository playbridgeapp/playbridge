package resolver

import (
	"sort"
	"strings"

	"github.com/truedem0n/playbridge-stream-resolver/config"
	"github.com/truedem0n/playbridge-stream-resolver/types"
)

// Rank scores, deduplicates, and sorts a flat list of RankedStreams.
// Streams from higher-priority addons (lower Priority value) receive a tie-
// breaking bonus. AIOStreams position 0 ends up first because position score
// dominates for the top-priority addon.
func Rank(streams []types.RankedStream, addons []config.SourceAddon) []types.RankedStream {
	// Build a priority lookup: addon name → priority value (lower = better).
	priorityOf := make(map[string]int, len(addons))
	for _, a := range addons {
		priorityOf[a.Name] = a.Priority
	}

	for i := range streams {
		streams[i].Score = score(&streams[i], priorityOf)
	}

	// Sort descending by score.
	sort.SliceStable(streams, func(i, j int) bool {
		return streams[i].Score > streams[j].Score
	})

	return deduplicate(streams)
}

// score computes a numeric rank for a single stream.
//
// Scoring breakdown:
//   - Source priority bonus: max 500, decreasing by 50 per priority level.
//     Priority 0 → +500, priority 1 → +450, etc.
//   - Position-within-source penalty: -10 per position.
//     Preserves the upstream addon's own ordering within each source.
//   - Resolution bonus: 2160p→100, 1080p→80, 720p→60, else 0.
//     Applied on top of the above so a 1080p at position 0 beats a 720p there.
func score(rs *types.RankedStream, priorityOf map[string]int) int {
	priority, ok := priorityOf[rs.SourceName]
	if !ok {
		priority = 99 // unknown addon goes to back
	}

	s := 500 - (priority * 50)
	s -= rs.SourcePos * 10
	s += resolutionBonus(rs.Stream)
	return s
}

func resolutionBonus(s types.Stream) int {
	content := strings.ToLower(s.Name + " " + s.Title + " " + s.Description)
	switch {
	case strings.Contains(content, "2160") || strings.Contains(content, "4k") || strings.Contains(content, "uhd"):
		return 100
	case strings.Contains(content, "1080"):
		return 80
	case strings.Contains(content, "720"):
		return 60
	default:
		return 0
	}
}

// deduplicate removes streams with duplicate URLs, keeping the highest-scored
// copy (which is already first after sorting).
func deduplicate(streams []types.RankedStream) []types.RankedStream {
	seen := make(map[string]bool, len(streams))
	out := streams[:0]
	for _, s := range streams {
		key := s.Stream.URL
		if key == "" {
			key = s.Stream.InfoHash
		}
		if key == "" || !seen[key] {
			if key != "" {
				seen[key] = true
			}
			out = append(out, s)
		}
	}
	return out
}
