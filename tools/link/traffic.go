package main

import (
	"context"
	"io"
	"math"
	"net/netip"
	"sync/atomic"
	"time"
)

const (
	// gameSilence is past the game's own timeout, which ended a real game 12.25 s after its
	// last packet (measured), so a stall the game outlasts is never called a drop.
	gameSilence = 14 * time.Second
	gamePoll    = time.Second
	// gameTalking is how recently a game on a server has sent; one at its menu sends nothing.
	gameTalking = 2 * time.Second
)

type gameTraffic struct {
	start      time.Time
	fromGame   atomic.Int64
	fromServer atomic.Int64
}

func (t *gameTraffic) since(last *atomic.Int64) time.Duration {
	sent := last.Load()
	if sent == 0 {
		return math.MaxInt64
	}
	return time.Since(t.start) - time.Duration(sent)
}

func (t *gameTraffic) inServer() bool { return t.since(&t.fromGame) < gameTalking }

type heard struct {
	io.ReadWriteCloser
	start time.Time
	last  *atomic.Int64
}

func (h heard) Read(b []byte) (int, error) {
	n, err := h.ReadWriteCloser.Read(b)
	if err == nil {
		h.last.Store(int64(time.Since(h.start)) + 1)
	}
	return n, err
}

type gameSample struct {
	fromGame, fromServer time.Duration
	client               netip.AddrPort
}

func watchGame(ctx context.Context, traffic *gameTraffic, client func() netip.AddrPort, silence time.Duration, emit Sink) {
	watch := gameWatch{silence: silence, state: GameQuiet}
	polled := traffic.start
	for {
		select {
		case <-ctx.Done():
			return
		case <-time.After(gamePoll):
		}
		now := time.Now()
		elapsed := now.Sub(polled)
		polled = now
		emit(Game{State: watch.step(gameSample{traffic.since(&traffic.fromGame), traffic.since(&traffic.fromServer), client()}, elapsed)})
	}
}

type gameWatch struct {
	silence    time.Duration
	client     netip.AddrPort
	state      GameState
	unanswered time.Duration
	counting   time.Duration
}

func (w *gameWatch) step(seen gameSample, elapsed time.Duration) GameState {
	w.counting += elapsed
	if seen.client != w.client {
		w.client, w.state, w.unanswered, w.counting = seen.client, GameQuiet, 0, 0
	}
	window := min(w.silence, w.counting)
	gameTalks, serverTalks := seen.fromGame < window, seen.fromServer < window
	switch {
	case gameTalks && serverTalks:
		w.state, w.unanswered = GameFlowing, 0
	case w.state == GameFlowing:
		w.state, w.counting = GameLost, 0
	case !gameTalks:
		w.state, w.unanswered = GameQuiet, 0
	default:
		if w.unanswered += elapsed; w.unanswered >= w.silence {
			w.state, w.counting = GameLost, 0
		}
	}
	return w.state
}
