package prober

import (
	"context"
	"fmt"
	"log"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

// Result holds the outcome of a single probe attempt.
type Result struct {
	DurationMins int
	Err          error
}

// ProbeDuration runs ffprobe against url with the given timeout and returns
// the video duration in minutes. Returns an error if ffprobe is unavailable,
// the stream is unreachable, or the duration cannot be parsed.
func ProbeDuration(url string, timeoutMs int) (int, error) {
	ffprobe, err := findFFProbe()
	if err != nil {
		return 0, err
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	cmd := exec.CommandContext(ctx, ffprobe,
		"-v", "error",
		"-show_entries", "format=duration",
		"-of", "default=noprint_wrappers=1:nokey=1",
		url,
	)

	output, err := cmd.Output()
	if err != nil {
		return 0, fmt.Errorf("ffprobe failed: %w", err)
	}

	durationStr := strings.TrimSpace(string(output))
	secs, err := strconv.ParseFloat(durationStr, 64)
	if err != nil {
		return 0, fmt.Errorf("parsing ffprobe output %q: %w", durationStr, err)
	}

	mins := int(secs / 60)
	short := url
	if len(short) > 80 {
		short = short[:80] + "..."
	}
	log.Printf("[prober] %s → %d min", short, mins)
	return mins, nil
}

// Available reports whether ffprobe can be found on this system.
// Use this at startup to warn if probing is configured but unavailable.
func Available() bool {
	_, err := findFFProbe()
	return err == nil
}

func findFFProbe() (string, error) {
	if path, err := exec.LookPath("ffprobe"); err == nil {
		return path, nil
	}
	for _, p := range []string{
		"/opt/homebrew/bin/ffprobe",
		"/usr/local/bin/ffprobe",
		"/usr/bin/ffprobe",
	} {
		if _, err := exec.LookPath(p); err == nil {
			return p, nil
		}
	}
	return "", fmt.Errorf("ffprobe not found in PATH or common locations")
}

