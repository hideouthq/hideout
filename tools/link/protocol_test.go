package main

import (
	"bytes"
	"encoding/json"
	"testing"
)

func TestEventLines(t *testing.T) {
	tests := []struct {
		event Event
		want  string
	}{
		{Invite{Code: "P95MAN26D", Region: Region{ID: 303, Code: "fra"}}, `{"type":"invite","code":"P95MAN26D","region":{"id":303,"code":"fra"}}`},
		{Built{Token: "00112233445566778899aabbccddeeff"}, `{"type":"built","token":"00112233445566778899aabbccddeeff"}`},
		{Regions{Regions: []RegionLatency{{Region{1, "nyc"}, nil}, {Region{3, "fra"}, new(31)}}}, `{"type":"regions","regions":[{"id":1,"code":"nyc"},{"id":3,"code":"fra","ms":31}]}`},
		{Offer{Offer: "eyJt_-9"}, `{"type":"offer","offer":"eyJt_-9"}`},
		{ServerEvent{Event: json.RawMessage(`{"host":{"ready":"<a&b>"}}`)}, `{"type":"server","event":{"host":{"ready":"<a&b>"}}}`},
		{ServerLog{Line: "not json <x>"}, `{"type":"server-log","line":"not json <x>"}`},
		{FlowOpen{Flow: 1}, `{"type":"flow-open","flow":1}`},
		{Linked{}, `{"type":"linked"}`},
		{Entered{}, `{"type":"entered"}`},
		{EnterExpired{}, `{"type":"enter-expired"}`},
		{EnterExpired{Page: PageUnreachable}, `{"type":"enter-expired","page":"unreachable"}`},
		{Staged{Held: []string{"maps/winter_arena.vpk"}}, `{"type":"staged","held":["maps/winter_arena.vpk"]}`},
		{Path{Kind: PathDirect}, `{"type":"path","kind":"direct"}`},
		{Path{Kind: PathRelay}, `{"type":"path","kind":"relay"}`},
		{Game{State: GameFlowing}, `{"type":"game","state":"flowing"}`},
		{Game{State: GameLost}, `{"type":"game","state":"lost"}`},
		{Guests{Direct: 2}, `{"type":"guests","direct":2,"relay":0}`},
		{Stopped{Reason: ReasonStdinClosed}, `{"type":"stopped","reason":"stdin-closed"}`},
		{Stopped{Reason: ReasonListenFailed, Message: "in use"}, `{"type":"stopped","reason":"listen-failed","message":"in use"}`},
	}
	for _, tt := range tests {
		var out bytes.Buffer
		newLineSink(&out)(tt.event)
		if got := out.String(); got != tt.want+"\n" {
			t.Errorf("got  %swant %s", got, tt.want+"\n")
		}
	}
}

func TestNothingFollowsStopped(t *testing.T) {
	var out bytes.Buffer
	emit := newLineSink(&out)
	emit(Stopped{Reason: ReasonSignal})
	emit(FlowOpen{Flow: 1})
	if got, want := out.String(), "{\"type\":\"stopped\",\"reason\":\"signal\"}\n"; got != want {
		t.Errorf("got %q, want %q", got, want)
	}
}

func TestExitCodes(t *testing.T) {
	success := map[Reason]bool{ReasonStdinClosed: true, ReasonSignal: true, ReasonDone: true}
	for _, r := range []Reason{
		ReasonStdinClosed, ReasonSignal, ReasonServerExited, ReasonServerStartFailed, ReasonLinkTimeout,
		ReasonLinkLost, ReasonBadInvite, ReasonRelayUnavailable, ReasonListenFailed, ReasonServerDead,
		ReasonDone, ReasonFailed, ReasonNotFound, ReasonHostEnded, ReasonHostFailed,
	} {
		if got := exitCode(Stopped{Reason: r}); (got == 0) != success[r] {
			t.Errorf("exitCode(%s) = %d", r, got)
		}
	}
}
