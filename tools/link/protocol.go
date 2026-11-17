package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"sync"
)

type Event interface{ eventType() string }

type Invite struct {
	Code string `json:"code"`
}

type Offer struct {
	Offer string `json:"offer"`
}

type ServerEvent struct {
	Event json.RawMessage `json:"event"`
}

type ServerLog struct {
	Line string `json:"line"`
}

type FlowOpen struct {
	Flow int64 `json:"flow"`
}

type Linked struct{}

type PathKind string

const (
	PathDirect PathKind = "direct"
	PathRelay  PathKind = "relay"
	PathNone   PathKind = "none" // the last ping went unanswered
)

type Path struct {
	Kind PathKind `json:"kind"`
}

type Guests struct {
	Direct int `json:"direct"`
	Relay  int `json:"relay"`
}

type GameState string

const (
	GameFlowing GameState = "flowing" // the game and the server both sent lately
	GameLost    GameState = "lost"    // a game that was talking stopped, or asks and gets no answer
	GameQuiet   GameState = "quiet"   // no game is sending, or a new one has yet to be answered or lost
)

// Game says what the game's traffic comes to right now, and is said again on every poll. A
// tunnel outlives a game that has dropped, so this is separate from the tunnel's own health.
type Game struct {
	State GameState `json:"state"`
}

type PageReach string

const PageUnreachable PageReach = "unreachable"

type Entered struct{}

type EnterExpired struct {
	Page PageReach `json:"page,omitempty"`
}

type Staged struct {
	Held []string `json:"held"`
}

// Fetch says how much of the host's pack has arrived, at most every fetchReport.
type Fetch struct {
	Done  int64 `json:"done"`
	Total int64 `json:"total"`
}

// Fetched says the whole pack arrived, at its size and hash, in the --fetch file.
type Fetched struct{}

// DevSession says gameinfo.gi held a Modlock development session's block: a
// live session keeps it, and one that ended had it taken out.
type DevSession struct {
	Live bool `json:"live"`
}

type Stopped struct {
	Reason  Reason `json:"reason"`
	Message string `json:"message,omitempty"`
}

func (Invite) eventType() string       { return "invite" }
func (Offer) eventType() string        { return "offer" }
func (ServerEvent) eventType() string  { return "server" }
func (ServerLog) eventType() string    { return "server-log" }
func (FlowOpen) eventType() string     { return "flow-open" }
func (Linked) eventType() string       { return "linked" }
func (Path) eventType() string         { return "path" }
func (Guests) eventType() string       { return "guests" }
func (Game) eventType() string         { return "game" }
func (Entered) eventType() string      { return "entered" }
func (EnterExpired) eventType() string { return "enter-expired" }
func (Staged) eventType() string       { return "staged" }
func (Fetch) eventType() string        { return "fetch" }
func (Fetched) eventType() string      { return "fetched" }
func (DevSession) eventType() string   { return "dev-session" }
func (Stopped) eventType() string      { return "stopped" }

type Reason string

const (
	ReasonStdinClosed       Reason = "stdin-closed"
	ReasonSignal            Reason = "signal"
	ReasonServerExited      Reason = "server-exited"
	ReasonServerStartFailed Reason = "server-start-failed"
	ReasonServerDead        Reason = "server-dead"
	ReasonLinkTimeout       Reason = "link-timeout"
	ReasonNotFound          Reason = "not-found"
	ReasonLinkLost          Reason = "link-lost"
	ReasonHostEnded         Reason = "host-ended"
	ReasonHostFailed        Reason = "host-failed"
	ReasonBadInvite         Reason = "bad-invite"
	ReasonRelayUnavailable  Reason = "relay-unavailable"
	ReasonListenFailed      Reason = "listen-failed"
	ReasonDone              Reason = "done"
	ReasonFailed            Reason = "failed"
	ReasonFetchFailed       Reason = "fetch-failed"
	ReasonFetchMismatch     Reason = "fetch-mismatch"
)

func (r Reason) Error() string { return string(r) }

func exitCode(s Stopped) int {
	switch s.Reason {
	case ReasonStdinClosed, ReasonSignal, ReasonDone:
		return 0
	}
	return 1
}

type Sink func(Event)

func newLineSink(w io.Writer) Sink {
	var mu sync.Mutex
	stopped := false
	return func(e Event) {
		mu.Lock()
		defer mu.Unlock()
		if stopped {
			return
		}
		_, stopped = e.(Stopped)
		w.Write(encodeLine(e))
	}
}

func stoppedBy(ctx context.Context) Stopped {
	return Stopped{Reason: context.Cause(ctx).(Reason)}
}

func encodeLine(e Event) []byte {
	var body bytes.Buffer
	enc := json.NewEncoder(&body)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(e); err != nil {
		panic(err)
	}
	rest := body.Bytes()[1:]
	line := []byte(`{"type":"` + e.eventType() + `"`)
	if rest[0] != '}' {
		line = append(line, ',')
	}
	return append(line, rest...)
}
