package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strings"
	"testing"

	"github.com/paralin/modlock/src/host_app/control"
)

func TestAModesReportReachesHideoutAndItsOtherLogsReachStderr(t *testing.T) {
	m := startMatch(t)
	report := m.host.stream.until("state").Report
	var got, want any
	if err := json.Unmarshal(report, &got); err != nil {
		t.Fatalf("state %s: %v", report, err)
	}
	json.Unmarshal([]byte(stubReport), &want)
	if fmt.Sprint(got) != fmt.Sprint(want) {
		t.Errorf("state = %s, want %s", report, stubReport)
	}

	m.host.stdin.Close()
	m.host.exit()
	for _, l := range m.host.stream.seen() {
		if l.Type == "state" && string(l.Report) != string(report) {
			t.Errorf("a report Hideout must not get reached it: %s", l.Raw)
		}
		if l.Type == "server" && strings.Contains(string(l.Event), `"log"`) {
			t.Errorf("a mode's log reached Hideout's stdout: %s", l.Raw)
		}
	}
	stderr := m.host.stderr.String()
	if !strings.Contains(stderr, "mode "+stubMod+": "+stubPlainLog+"\n") {
		t.Errorf("the mode's plain log is not on stderr: %q", stderr)
	}
	if dropped := strings.Count(stderr, "mode "+stubMod+": report dropped"); dropped != 2 {
		t.Errorf("%d reports said dropped, want 2: %q", dropped, stderr)
	}
	if strings.Contains(stderr, m.invite.code) {
		t.Errorf("the invite is on stderr: %q", stderr)
	}
}

const hideoutLoggedLines = 2000

func TestAChattyModeLeavesHideoutsLogRoomForTheHelpersOwnLines(t *testing.T) {
	body, err := (&control.HostEvent{Body: &control.HostEvent_Log{Log: &control.ModLog{Mod: stubMod, Text: "tick"}}}).MarshalJSON()
	if err != nil {
		t.Fatal(err)
	}
	var stderr bytes.Buffer
	sink := modeLogs(func(Event) {}, &stderr)
	for range 10 * hideoutLoggedLines {
		sink(ServerEvent{Event: []byte(`{"host":` + string(body) + `}`)})
	}
	fmt.Fprintln(&stderr, "relay: the helper's own line")
	logged := strings.SplitAfterN(stderr.String(), "\n", hideoutLoggedLines+1)
	if len(logged) > hideoutLoggedLines {
		logged = logged[:hideoutLoggedLines]
	}
	if !strings.Contains(strings.Join(logged, ""), "the helper's own line") {
		t.Errorf("the helper's line is past the first %d lines Hideout logs", hideoutLoggedLines)
	}
	if !strings.Contains(stderr.String(), "mode "+stubMod+": tick\n") {
		t.Errorf("the mode's first lines are gone too")
	}
}
