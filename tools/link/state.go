package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
	"sync/atomic"

	"github.com/paralin/modlock/src/host_app/control"
)

// State is what a mode last reported on a tagged log line: a JSON object keyed by section
// kind, such as {"scoreboard":{"sides":[{"score":3,"slots":[0]}],"notes":["First to 5"]}}.
// Hideout reads the kinds it knows and skips the rest.
type State struct {
	State json.RawMessage `json:"state"`
}

func (State) eventType() string { return "state" }

// stateTag starts a log line that carries a State. A mode writes it with
// log("hideout:" + JSON.stringify(state)).
const stateTag = "hideout:"

const maxState = 4096

const maxModeLines = 1000

func modeLogs(emit Sink, logs io.Writer) Sink {
	var written atomic.Int64
	say := func(format string, args ...any) {
		switch n := written.Add(1); {
		case n < maxModeLines:
			fmt.Fprintf(logs, format, args...)
		case n == maxModeLines:
			fmt.Fprintf(logs, "mode logs: the rest are dropped after %d lines\n", maxModeLines-1)
		}
	}
	return func(e Event) {
		event, ok := e.(ServerEvent)
		if !ok {
			emit(e)
			return
		}
		log := modLog(event.Event)
		if log == nil {
			emit(e)
			return
		}
		body, tagged := strings.CutPrefix(log.GetText(), stateTag)
		if !tagged {
			say("mode %s: %s\n", log.GetMod(), oneLine(log.GetText()))
			return
		}
		state, err := stateOf(body)
		if err != nil {
			say("mode %s: report dropped, %v\n", log.GetMod(), err)
			return
		}
		emit(State{State: state})
	}
}

func modLog(raw []byte) *control.ModLog {
	var wrapped struct {
		Host json.RawMessage `json:"host"`
	}
	if json.Unmarshal(raw, &wrapped) != nil || wrapped.Host == nil {
		return nil
	}
	event := &control.HostEvent{}
	if event.UnmarshalJSON(wrapped.Host) != nil {
		return nil
	}
	return event.GetLog()
}

func stateOf(body string) (json.RawMessage, error) {
	if len(body) > maxState {
		return nil, fmt.Errorf("%d bytes is over %d", len(body), maxState)
	}
	var object map[string]json.RawMessage
	if json.Unmarshal([]byte(body), &object) != nil || object == nil {
		return nil, errors.New("not a JSON object")
	}
	return json.RawMessage(body), nil
}

func oneLine(text string) string {
	return strings.NewReplacer("\r", `\r`, "\n", `\n`).Replace(text)
}
