package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"
)

const (
	// serverDeadAfter outlasts a world reload, which passes through ss_dead and is out of it within a second (measured).
	serverDeadAfter = 15 * time.Second
	consolePoll     = time.Second

	consoleNote = "console: "
)

var engineState = regexp.MustCompile(`(?m)^CNetworkGameServerBase::SetServerState \(\w+ -> (\w+)\)\r?$`)

// consoleWatch reads the engine's own word on its server. Neither Modlock's stream nor its
// process shows a server gone: a map that fails to load still gets ready, and modlock and
// modlock-host both stay up with the engine idle at its console (measured).
type consoleWatch struct {
	after time.Duration
	once  sync.Once
	dead  chan struct{}
}

func newConsoleWatch(after time.Duration) *consoleWatch {
	return &consoleWatch{after: after, dead: make(chan struct{})}
}

// watching starts following the console once Modlock names it, which it does only after
// emptying the last session's, so an old server's death is never read.
func (w *consoleWatch) watching(ctx context.Context, emit Sink) Sink {
	return func(e Event) {
		if event, ok := e.(ServerEvent); ok {
			if path := consolePath(event.Event); path != "" {
				w.once.Do(func() { go w.follow(ctx, path) })
			}
		}
		emit(e)
	}
}

func consolePath(raw []byte) string {
	var line struct {
		Note string `json:"note"`
	}
	if json.Unmarshal(raw, &line) != nil {
		return ""
	}
	path, named := strings.CutPrefix(line.Note, consoleNote)
	if !named {
		return ""
	}
	return path
}

func (w *consoleWatch) follow(ctx context.Context, path string) {
	var offset int64
	var deadSince time.Time
	for {
		select {
		case <-ctx.Done():
			return
		case <-time.After(consolePoll):
		}
		lines := finishedLines(path, offset)
		offset += int64(len(lines))
		for _, state := range engineState.FindAllSubmatch(lines, -1) {
			switch {
			case string(state[1]) != "ss_dead":
				deadSince = time.Time{}
			case deadSince.IsZero():
				deadSince = time.Now()
			}
		}
		if !deadSince.IsZero() && time.Since(deadSince) >= w.after {
			close(w.dead)
			return
		}
	}
}

func finishedLines(path string, offset int64) []byte {
	file, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer file.Close()
	if _, err := file.Seek(offset, io.SeekStart); err != nil {
		return nil
	}
	read, err := io.ReadAll(file)
	if err != nil {
		return nil
	}
	return read[:bytes.LastIndexByte(read, '\n')+1]
}
