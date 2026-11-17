package main

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/paralin/modlock/panorama"
)

const commandBuffer = 16

type Enter struct{ Within time.Duration }

func parseEnter(line string) (Enter, error) {
	fields := strings.Fields(line)
	if len(fields) == 2 && fields[0] == "enter" {
		if seconds, err := strconv.Atoi(fields[1]); err == nil && seconds > 0 {
			return Enter{Within: time.Duration(seconds) * time.Second}, nil
		}
	}
	return Enter{}, fmt.Errorf("unknown command %q", line)
}

func readCommands(r io.Reader, lines chan<- string) {
	defer close(lines)
	scanner := bufio.NewScanner(r)
	for scanner.Scan() {
		select {
		case lines <- scanner.Text():
		default:
			fmt.Fprintln(os.Stderr, "command: dropped a line while", commandBuffer, "waited")
		}
	}
}

// An enter that comes while the game is on this server is entered at once,
// since a second connect would reload the map.
func followCommands(ctx context.Context, lines <-chan string, page *pageServer, target uint16, emit Sink) {
	var wait enterWait
	for {
		var line string
		var open bool
		select {
		case <-ctx.Done():
			return
		case line, open = <-lines:
			if !open {
				return
			}
		}
		enter, err := parseEnter(line)
		if err != nil {
			fmt.Fprintln(os.Stderr, "command:", err)
			continue
		}
		if page.inServer() {
			emit(Entered{})
			continue
		}
		if wait.extend(enter.Within) {
			continue
		}
		sendCtx, done := wait.start(ctx, enter.Within)
		go func() {
			defer done()
			if page.relay.Send(sendCtx, &panorama.Frame{Body: &panorama.Frame_Connect{Connect: uint32(target)}}) {
				emit(Entered{})
			} else if ctx.Err() == nil {
				emit(EnterExpired{Page: page.reach()})
			}
		}()
	}
}

type enterWait struct {
	mu    sync.Mutex
	end   time.Time
	timer *time.Timer
}

func (w *enterWait) extend(within time.Duration) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.timer == nil {
		return false
	}
	if end := time.Now().Add(within); end.After(w.end) && w.timer.Stop() {
		w.end = end
		w.timer.Reset(within)
	}
	return true
}

func (w *enterWait) start(ctx context.Context, within time.Duration) (context.Context, func()) {
	w.mu.Lock()
	defer w.mu.Unlock()
	sendCtx, cancel := context.WithCancel(ctx)
	w.end = time.Now().Add(within)
	w.timer = time.AfterFunc(within, cancel)
	return sendCtx, func() {
		w.mu.Lock()
		defer w.mu.Unlock()
		w.timer.Stop()
		w.timer = nil
		cancel()
	}
}
