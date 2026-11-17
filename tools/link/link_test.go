package main

import (
	"bufio"
	"cmp"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/tstest/integration"
	"tailscale.com/types/key"
)

const (
	eventWait = 30 * time.Second
	testLink  = 30 * time.Second
	testOffer = "dGVzdA"

	noPage = "http://127.0.0.1:1/bridge/"
)

type line struct {
	Raw    string          `json:"-"`
	Type   string          `json:"type"`
	Code   string          `json:"code"`
	Event  json.RawMessage `json:"event"`
	Line   string          `json:"line"`
	Flow   int64           `json:"flow"`
	Kind   string          `json:"kind"`
	State  string          `json:"state"`
	Reason string          `json:"reason"`
	Direct int             `json:"direct"`
	Relay  int             `json:"relay"`
	Done   int64           `json:"done"`
	Total  int64           `json:"total"`
	Page   string          `json:"page"`
	Offer  string          `json:"offer"`
	Report json.RawMessage `json:"-"`
}

type stream struct {
	t     testing.TB
	lines chan line
	mu    sync.Mutex
	all   []line
}

func newStream(t testing.TB) *stream {
	return &stream{t: t, lines: make(chan line, 4096)}
}

func (s *stream) add(raw string) {
	var wire struct {
		line
		State json.RawMessage `json:"state"`
	}
	if err := json.Unmarshal([]byte(raw), &wire); err != nil {
		s.t.Errorf("stdout line is not JSON: %q", raw)
		return
	}
	l := wire.line
	l.Raw = raw
	if json.Unmarshal(wire.State, &l.State) != nil {
		l.Report = wire.State
	}
	s.mu.Lock()
	s.all = append(s.all, l)
	s.mu.Unlock()
	s.lines <- l
}

func (s *stream) Write(b []byte) (int, error) {
	s.add(strings.TrimSuffix(string(b), "\n"))
	return len(b), nil
}

func (s *stream) seen() []line {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]line(nil), s.all...)
}

func (s *stream) until(eventType string) line {
	s.t.Helper()
	timeout := time.After(eventWait)
	for {
		select {
		case l := <-s.lines:
			if l.Type == eventType {
				return l
			}
			if l.Type == "stopped" {
				s.t.Fatalf("stopped while waiting for %s: %s", eventType, l.Raw)
			}
		case <-timeout:
			s.t.Fatalf("no %s event within %v, saw %v", eventType, eventWait, s.seen())
		}
	}
}

type session struct {
	gameListen string
	stream     *stream
	stdin      *io.PipeWriter
	stop       func(error)
	stopped    chan Stopped
}

func (s *session) end(t testing.TB) Stopped {
	t.Helper()
	s.stdin.Close()
	return s.result(t, eventWait)
}

func (s *session) result(t testing.TB, within time.Duration) Stopped {
	t.Helper()
	select {
	case st := <-s.stopped:
		s.stopped <- st
		return st
	case <-time.After(within):
		t.Fatalf("session still running after %v, saw %v", within, s.stream.seen())
		return Stopped{}
	}
}

func startSession(t testing.TB, run func(context.Context, io.Reader, Sink) Stopped) *session {
	t.Helper()
	ctx, stop := context.WithCancelCause(context.Background())
	stdinRead, stdinWrite := io.Pipe()
	s := &session{
		stream:  newStream(t),
		stdin:   stdinWrite,
		stop:    stop,
		stopped: make(chan Stopped, 1),
	}
	go func() { s.stopped <- run(ctx, stdinRead, newLineSink(s.stream)) }()
	t.Cleanup(func() {
		stdinWrite.Close()
		s.result(t, eventWait)
		stop(nil)
	})
	return s
}

func localRelay(t testing.TB) *tailcfg.DERPRegion {
	t.Helper()
	dm := integration.RunDERPAndSTUN(t, func(string, ...any) {}, "127.0.0.1")
	return dm.Regions[1]
}

func relayMap(t testing.TB, regions ...*tailcfg.DERPRegion) string {
	t.Helper()
	relays := &tailcfg.DERPMap{Regions: map[tailcfg.DERPRegionID]*tailcfg.DERPRegion{}}
	for _, r := range regions {
		relays.Regions[r.RegionID] = r
	}
	body, err := json.Marshal(relays)
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write(body) }))
	t.Cleanup(server.Close)
	return server.URL + "/derpmap.json"
}

type hosted struct {
	code    string
	region  *tailcfg.DERPRegion
	derpMap string
}

func hostedOn(t testing.TB, code string, region *tailcfg.DERPRegion) hosted {
	return hosted{code, region, relayMap(t, region)}
}

func (h hosted) addr() tailcat.Addr { return deriveKeys(h.code[:secretLength]).addr(h.region) }

func answer(w io.Writer, code string, flags ...string) {
	yes, err := json.Marshal(flags)
	if err != nil {
		panic(err)
	}
	fmt.Fprintf(w, "%s\njoin %s\n", code, yes)
}

func childArgv(args ...string) []string {
	return append([]string{os.Args[0]}, args...)
}

func hostSessionOf(t testing.TB, cfg HostConfig) *session {
	t.Helper()
	if cfg.ServerArgv == nil {
		cfg.ServerArgv = childArgv("child-until-eof")
	}
	if cfg.KillAfter == 0 {
		cfg.KillAfter = killAfter
	}
	cfg.Offer = cmp.Or(cfg.Offer, testOffer)
	cfg.PageURL, cfg.ScreenListen = noPage, "127.0.0.1:0"
	return startSession(t, func(ctx context.Context, stdin io.Reader, emit Sink) Stopped {
		cfg.Stdin = stdin
		return runHost(ctx, cfg, emit)
	})
}

func startHost(t testing.TB, cfg HostConfig) (*session, hosted) {
	t.Helper()
	s := hostSessionOf(t, cfg)
	return s, hostedOn(t, s.stream.until("invite").Code, cfg.Region)
}

func guestSessionOf(t testing.TB, gameListen, derpMap string, linkTimeout, silence time.Duration) *session {
	t.Helper()
	s := startSession(t, func(ctx context.Context, stdin io.Reader, emit Sink) Stopped {
		return runJoin(ctx, JoinConfig{GameListen: gameListen, LinkTimeout: linkTimeout, GameSilence: silence, DERPMapURL: derpMap, Stdin: stdin}, emit)
	})
	s.gameListen = gameListen
	return s
}

func guestWatching(t testing.TB, invite hosted, silence time.Duration) *session {
	t.Helper()
	s := guestSessionOf(t, freeUDPAddress(t), invite.derpMap, testLink, silence)
	go answer(s.stdin, invite.code, "--page", noPage)
	return s
}

func gameStates(s *session) []string {
	var states []string
	for _, l := range s.stream.seen() {
		if l.Type == "game" && (len(states) == 0 || states[len(states)-1] != l.State) {
			states = append(states, l.State)
		}
	}
	return states
}

func (s *stream) untilGame(state string) {
	s.t.Helper()
	for end := time.Now().Add(eventWait); s.until("game").State != state; {
		if time.Now().After(end) {
			s.t.Fatalf("game never said %s within %v", state, eventWait)
		}
	}
}

func startGuest(t testing.TB, invite hosted, linkTimeout time.Duration) *session {
	t.Helper()
	s := guestSessionOf(t, freeUDPAddress(t), invite.derpMap, linkTimeout, 0)
	go answer(s.stdin, invite.code, "--page", noPage)
	return s
}

func (s *session) linkedGame() string {
	s.stream.until("linked")
	return s.gameListen
}

func echoServer(t testing.TB) uint16 {
	t.Helper()
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	serveEcho(conn)
	return uint16(conn.LocalAddr().(*net.UDPAddr).Port)
}

func serveEcho(conn *net.UDPConn) {
	go func() {
		buf := make([]byte, maxDatagram)
		for {
			n, from, err := conn.ReadFromUDP(buf)
			if err != nil {
				return
			}
			conn.WriteToUDP(fmt.Appendf(nil, "%s|%d", buf[:n], from.Port), from)
		}
	}()
}

type gameClient struct {
	t    testing.TB
	conn *net.UDPConn
}

func dialGame(t testing.TB, address string) *gameClient {
	t.Helper()
	addr, err := net.ResolveUDPAddr("udp4", address)
	if err != nil {
		t.Fatal(err)
	}
	conn, err := net.DialUDP("udp4", nil, addr)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	return &gameClient{t, conn}
}

// exchange resends until the echo answers, since the first datagrams can race the
// tunnel's own setup. It returns the source port the server saw.
func (g *gameClient) exchange(payload string) (serverSawPort string) {
	g.t.Helper()
	buf := make([]byte, maxDatagram)
	deadline := time.Now().Add(eventWait)
	for time.Now().Before(deadline) {
		g.conn.Write([]byte(payload))
		g.conn.SetReadDeadline(time.Now().Add(250 * time.Millisecond))
		n, err := g.conn.Read(buf)
		if err != nil {
			continue
		}
		got, port, _ := strings.Cut(string(buf[:n]), "|")
		if got != payload {
			g.t.Fatalf("echo returned %q, want %q", got, payload)
		}
		return port
	}
	g.t.Fatalf("no echo of %q within %v", payload, eventWait)
	return ""
}

func (g *gameClient) connect() {
	g.t.Helper()
	for end := time.Now().Add(2 * gamePoll); time.Now().Before(end); time.Sleep(100 * time.Millisecond) {
		g.exchange("connect")
	}
}

func TestGameRoundTrip(t *testing.T) {
	const offer = "eyJtb2RlIjoiYnJhd2wifQ_-09"
	relay := localRelay(t)
	host, invite := startHost(t, HostConfig{ServerPort: echoServer(t), Region: relay, Offer: offer})
	if !regexp.MustCompile("^[" + codeAlphabet + "]{9}$").MatchString(invite.code) {
		t.Fatalf("the code is not nine of the alphabet's characters")
	}
	guest := startGuest(t, invite, testLink)

	if got := guest.stream.until("offer").Offer; got != offer {
		t.Errorf("offer = %q, want %q", got, offer)
	}
	address := guest.linkedGame()
	path := guest.stream.until("path")
	if path.Kind != "direct" && path.Kind != "relay" {
		t.Errorf("path kind = %q", path.Kind)
	}

	game := dialGame(t, address)
	game.exchange("first")
	if open := host.stream.until("flow-open"); open.Flow != 1 {
		t.Errorf("first flow = %d", open.Flow)
	}

	restarted := dialGame(t, address)
	restarted.exchange("after a game restart")
}

func TestPathReportsOnlyChanges(t *testing.T) {
	relay := localRelay(t)
	_, invite := startHost(t, HostConfig{ServerPort: echoServer(t), Region: relay})
	guest := startGuest(t, invite, testLink)
	guest.stream.until("path")
	time.Sleep(2*pathPoll + time.Second)

	var kinds []string
	for _, l := range guest.stream.seen() {
		if l.Type == "path" {
			kinds = append(kinds, l.Kind)
		}
	}
	for i := 1; i < len(kinds); i++ {
		if kinds[i] == kinds[i-1] {
			t.Errorf("path repeated without a change: %v", kinds)
		}
	}
}

// The host runs as its own process, as in Hideout, because a Tailcat Close that races a
// guest's dial can hang and leave the node answering for as long as its process lives.
func TestAGuestHearsWhenTheTunnelStopsAnswering(t *testing.T) {
	relay := localRelay(t)
	host := startHelperHost(t, relay, echoServer(t), "child-until-eof")
	guest := startGuest(t, hostedOn(t, host.stream.until("invite").Code, relay), testLink)
	if kind := guest.stream.until("path").Kind; kind == "none" {
		t.Fatalf("first path = %q", kind)
	}
	host.cmd.Process.Kill()
	for guest.stream.until("path").Kind != "none" {
	}
}

func TestAGuestSeesItsGameConnectDropAndComeBackWithTheTunnelUp(t *testing.T) {
	relay := localRelay(t)
	_, invite := startHost(t, HostConfig{ServerPort: echoServer(t), Region: relay})
	guest := guestWatching(t, invite, 2*time.Second)
	address := guest.linkedGame()
	time.Sleep(2 * gamePoll)
	if states := gameStates(guest); !slices.Equal(states, []string{"quiet"}) {
		t.Fatalf("reported %v before the game sent anything", states)
	}

	game := dialGame(t, address)
	game.connect()
	guest.stream.untilGame("flowing")
	guest.stream.untilGame("lost")
	game.exchange("the tunnel never went away")
	guest.stream.untilGame("flowing")
}

func TestABriefStallIsNotCalledADrop(t *testing.T) {
	relay := localRelay(t)
	_, invite := startHost(t, HostConfig{ServerPort: echoServer(t), Region: relay})
	guest := guestWatching(t, invite, 3*time.Second)
	game := dialGame(t, guest.linkedGame())
	game.connect()
	guest.stream.untilGame("flowing")
	for range 3 {
		time.Sleep(1500 * time.Millisecond)
		game.exchange("still here")
	}
	if states := gameStates(guest); slices.Contains(states, "lost") {
		t.Errorf("states = %v, want no lost", states)
	}
}

func TestAGameTheServerNeverAnswersIsLostAndNeverConnected(t *testing.T) {
	relay := localRelay(t)
	_, invite := startHost(t, HostConfig{ServerPort: 1, Region: relay})
	guest := guestWatching(t, invite, 2*time.Second)
	game := dialGame(t, guest.linkedGame())
	sending := time.NewTicker(200 * time.Millisecond)
	defer sending.Stop()
	go func() {
		for range sending.C {
			if _, err := game.conn.Write([]byte("connect")); err != nil && errors.Is(err, net.ErrClosed) {
				return
			}
		}
	}()
	guest.stream.untilGame("lost")
	if states := gameStates(guest); slices.Contains(states, "flowing") {
		t.Errorf("states = %v, want no flowing", states)
	}
}

func TestTheGamesStateIsSaidAgainSoHideoutCanCatchUp(t *testing.T) {
	relay := localRelay(t)
	_, invite := startHost(t, HostConfig{ServerPort: echoServer(t), Region: relay})
	guest := guestWatching(t, invite, 5*time.Second)
	game := dialGame(t, guest.linkedGame())
	game.connect()
	guest.stream.untilGame("flowing")
	game.exchange("still here")
	guest.stream.untilGame("flowing")
}

func TestAGameOnANewPortStartsOverWhateverTheLastGameLeft(t *testing.T) {
	server := newPushingServer(t)
	_, invite := startHost(t, HostConfig{ServerPort: server.port(), Region: localRelay(t)})
	guest := guestWatching(t, invite, 5*time.Second)
	address := guest.linkedGame()
	first := dialGame(t, address)
	first.sendFor(2 * gamePoll)
	guest.stream.untilGame("flowing")

	server.muted.Store(true)
	time.Sleep(2 * gamePoll)
	dialGame(t, address).sendFor(2 * gamePoll)
	guest.stream.untilGame("quiet")
}

type pushingServer struct {
	conn   *net.UDPConn
	client atomic.Pointer[net.UDPAddr]
	muted  atomic.Bool
}

func newPushingServer(t testing.TB) *pushingServer {
	t.Helper()
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	s := &pushingServer{conn: conn}
	go func() {
		buf := make([]byte, maxDatagram)
		for {
			_, from, err := conn.ReadFromUDP(buf)
			if err != nil {
				return
			}
			s.client.Store(from)
		}
	}()
	go func() {
		for {
			time.Sleep(50 * time.Millisecond)
			if client := s.client.Load(); client != nil && !s.muted.Load() {
				if _, err := conn.WriteToUDP([]byte("snapshot"), client); err != nil {
					return
				}
			}
		}
	}()
	return s
}

func (s *pushingServer) port() uint16 { return uint16(s.conn.LocalAddr().(*net.UDPAddr).Port) }

func (g *gameClient) sendFor(d time.Duration) {
	for end := time.Now().Add(d); time.Now().Before(end); time.Sleep(50 * time.Millisecond) {
		g.conn.Write([]byte("tick"))
	}
}

func TestARecoveredTunnelDoesNotConnectAGameAtItsMenu(t *testing.T) {
	server := newPushingServer(t)
	_, invite := startHost(t, HostConfig{ServerPort: server.port(), Region: localRelay(t)})
	guest := guestWatching(t, invite, 5*time.Second)
	game := dialGame(t, guest.linkedGame())
	game.sendFor(2 * gamePoll)
	guest.stream.untilGame("flowing")

	server.muted.Store(true)
	game.sendFor(4 * time.Second)
	guest.stream.untilGame("lost")
	server.muted.Store(false)
	time.Sleep(3 * gamePoll)
	states := gameStates(guest)
	if slices.Contains(states[slices.Index(states, "lost"):], "flowing") {
		t.Errorf("states = %v, the game sent nothing after it dropped", states)
	}
}

func TestWhatAGuestIsToldFollowsBothDirections(t *testing.T) {
	const (
		silence = 14 * time.Second
		fresh   = time.Second
		stale   = silence
		never   = time.Duration(math.MaxInt64)

		sentBeforeTheDrop = silence/2 + time.Second
	)
	var zero netip.AddrPort
	newGame := netip.MustParseAddrPort("127.0.0.1:5000")
	type tick struct {
		fromGame, fromServer time.Duration
		want                 GameState
		client               netip.AddrPort
	}
	tests := []struct {
		name  string
		ticks []tick
	}{
		{"nothing has been sent", []tick{{never, never, GameQuiet, zero}, {never, never, GameQuiet, zero}}},
		{"the first answer connects", []tick{{fresh, never, GameQuiet, zero}, {fresh, fresh, GameFlowing, zero}}},
		{"a stall the game outlasts changes nothing", []tick{{fresh, fresh, GameFlowing, zero}, {fresh, stale - 1, GameFlowing, zero}, {fresh, fresh, GameFlowing, zero}}},
		{"no packet from the server for the whole silence is lost", []tick{{fresh, fresh, GameFlowing, zero}, {fresh, stale, GameLost, zero}}},
		{"a game that stopped sending is lost, whatever the server still sends", []tick{{fresh, fresh, GameFlowing, zero}, {stale, fresh, GameLost, zero}}},
		{"late server packets do not bring back a game that went quiet", []tick{{fresh, fresh, GameFlowing, zero}, {stale, stale, GameLost, zero}, {stale, fresh, GameQuiet, zero}, {stale, fresh, GameQuiet, zero}}},
		{"a recovered tunnel does not connect a game that last sent before the drop", []tick{{fresh, fresh, GameFlowing, zero}, {fresh, stale, GameLost, zero}, {sentBeforeTheDrop, fresh, GameQuiet, zero}}},
		{"both talking again is connected again", []tick{{fresh, fresh, GameFlowing, zero}, {stale, stale, GameLost, zero}, {fresh, fresh, GameFlowing, zero}}},
		{"a game that asks for the whole silence with no answer is lost", []tick{{fresh, never, GameQuiet, zero}, {fresh, never, GameLost, zero}, {fresh, never, GameLost, zero}}},
		{"a new attempt after a drop can be lost again", []tick{{fresh, fresh, GameFlowing, zero}, {stale, stale, GameLost, zero}, {stale, stale, GameQuiet, zero}, {fresh, stale, GameQuiet, zero}, {fresh, stale, GameLost, zero}}},
		{"a new client hears nothing of the last one's state", []tick{{fresh, fresh, GameFlowing, zero}, {fresh, sentBeforeTheDrop, GameQuiet, newGame}, {fresh, stale, GameQuiet, newGame}, {fresh, stale, GameLost, newGame}}},
		{"a new client that is answered connects", []tick{{stale, stale, GameQuiet, zero}, {fresh, fresh, GameQuiet, newGame}, {fresh, fresh, GameFlowing, newGame}}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			watch := gameWatch{silence: silence, state: GameQuiet, counting: silence}
			for i, tick := range tt.ticks {
				if got := watch.step(gameSample{tick.fromGame, tick.fromServer, tick.client}, silence/2); got != tick.want {
					t.Fatalf("tick %d: state = %q, want %q", i, got, tick.want)
				}
			}
		})
	}
}

func TestANewClientDoesNotInheritTheLastClientsServerPacket(t *testing.T) {
	const silence = 14 * time.Second
	oldGame := netip.MustParseAddrPort("127.0.0.1:5000")
	newGame := netip.MustParseAddrPort("127.0.0.1:5001")
	watch := gameWatch{silence: silence, state: GameQuiet, counting: silence}
	watch.step(gameSample{100 * time.Millisecond, 100 * time.Millisecond, oldGame}, gamePoll)
	if got := watch.step(gameSample{100 * time.Millisecond, 100 * time.Millisecond, oldGame}, gamePoll); got != GameFlowing {
		t.Fatalf("old client: state = %q", got)
	}
	lastServerPacket := 500 * time.Millisecond
	for i := range 20 {
		sample := gameSample{100 * time.Millisecond, lastServerPacket + time.Duration(i)*gamePoll, newGame}
		if got := watch.step(sample, gamePoll); got == GameFlowing {
			t.Fatalf("%d polls after the new client showed the server has said nothing to it, state = %q", i, got)
		}
	}
	if got := watch.step(gameSample{100 * time.Millisecond, 100 * time.Millisecond, newGame}, gamePoll); got != GameFlowing {
		t.Errorf("once answered: state = %q", got)
	}
}

func TestHostCountsEachGuestByItsPath(t *testing.T) {
	relay := localRelay(t)
	host, invite := startHost(t, HostConfig{ServerPort: echoServer(t), Region: relay})
	startGuest(t, invite, testLink).stream.until("linked")
	startGuest(t, invite, testLink).stream.until("linked")
	deadline := time.Now().Add(eventWait)
	for {
		guests := host.stream.until("guests")
		if guests.Direct+guests.Relay == 2 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("never counted two guests: %v", host.stream.seen())
		}
	}
	var last line
	for _, l := range host.stream.seen() {
		if l.Type != "guests" {
			continue
		}
		if l.Direct == last.Direct && l.Relay == last.Relay {
			t.Errorf("guests repeated without a change: %s", l.Raw)
		}
		last = l
	}
}

func TestGuestsCountsOnlyWhoTheTunnelIsTalkingTo(t *testing.T) {
	peers := func(peers ...*ipnstate.PeerStatus) *ipnstate.Status {
		status := &ipnstate.Status{Peer: map[key.NodePublic]*ipnstate.PeerStatus{}}
		for _, peer := range peers {
			status.Peer[key.NewNode().Public()] = peer
		}
		return status
	}
	direct := &ipnstate.PeerStatus{Active: true, CurAddr: "192.0.2.1:41641", Relay: "local"}
	relayed := &ipnstate.PeerStatus{Active: true, Relay: "local"}
	gone := &ipnstate.PeerStatus{CurAddr: "192.0.2.2:41641", Relay: "local"}
	tests := []struct {
		status *ipnstate.Status
		want   Guests
	}{
		{peers(), Guests{}},
		{peers(direct), Guests{Direct: 1}},
		{peers(relayed), Guests{Relay: 1}},
		{peers(direct, direct, relayed), Guests{Direct: 2, Relay: 1}},
		{peers(gone), Guests{}},
		{peers(gone, relayed), Guests{Relay: 1}},
	}
	for _, tt := range tests {
		if got := guestsOf(tt.status); got != tt.want {
			t.Errorf("guestsOf(%d peers) = %+v, want %+v", len(tt.status.Peer), got, tt.want)
		}
	}
}

func TestSecondGuestGetsItsOwnFlow(t *testing.T) {
	relay := localRelay(t)
	host, invite := startHost(t, HostConfig{ServerPort: echoServer(t), Region: relay})

	first := dialGame(t, startGuest(t, invite, testLink).linkedGame())
	second := dialGame(t, startGuest(t, invite, testLink).linkedGame())
	firstPort := first.exchange("one")
	secondPort := second.exchange("two")
	if firstPort == secondPort {
		t.Errorf("both guests reached the server from port %s", firstPort)
	}

	ids := map[int64]bool{host.stream.until("flow-open").Flow: true, host.stream.until("flow-open").Flow: true}
	if !ids[1] || !ids[2] {
		t.Errorf("flow ids = %v, want 1 and 2", ids)
	}
	if first.exchange("one again") != firstPort {
		t.Error("first guest changed flow when the second joined")
	}
}

func TestServerNotListeningYetKeepsTheFlow(t *testing.T) {
	relay := localRelay(t)
	reserved, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	addr := reserved.LocalAddr().(*net.UDPAddr)
	reserved.Close()

	host, invite := startHost(t, HostConfig{ServerPort: uint16(addr.Port), Region: relay})
	game := dialGame(t, startGuest(t, invite, testLink).linkedGame())
	for range 5 {
		game.conn.Write([]byte("too early"))
	}
	host.stream.until("flow-open")
	time.Sleep(500 * time.Millisecond)

	server, err := net.ListenUDP("udp4", addr)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { server.Close() })
	serveEcho(server)
	game.exchange("now listening")

	for _, l := range host.stream.seen() {
		if l.Type == "flow-open" && l.Flow != 1 {
			t.Errorf("flow did not survive the closed port: %s", l.Raw)
		}
	}
}

func TestOnlyTheRegisteredPortsAnswer(t *testing.T) {
	relay := localRelay(t)
	_, invite := startHost(t, HostConfig{ServerPort: echoServer(t), Region: relay})
	client := &tailcat.Client{Server: invite.addr(), Logf: func(string, ...any) {}}
	t.Cleanup(func() { client.Close() })
	ctx, cancel := context.WithTimeout(context.Background(), eventWait)
	defer cancel()

	if _, _, err := readOfferAndHoldIt(ctx, client); err != nil {
		t.Fatal(err)
	}
	for _, port := range []uint16{22, 80, gamePort, 27068} {
		if conn, err := client.DialTCPPort(ctx, port); err == nil {
			conn.Close()
			t.Errorf("TCP port %d accepted a connection", port)
		}
	}
	other, err := client.DialUDPPort(ctx, gamePort+1)
	if err != nil {
		t.Fatal(err)
	}
	defer other.Close()
	other.Write([]byte("hello"))
	other.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := other.Read(make([]byte, 16)); err == nil {
		t.Error("an unregistered UDP port answered")
	}
}

func TestStdinEOFStopsBoth(t *testing.T) {
	relay := localRelay(t)
	host, invite := startHost(t, HostConfig{ServerPort: echoServer(t), Region: relay})
	guest := startGuest(t, invite, testLink)
	guest.stream.until("linked")

	for _, s := range []struct {
		name string
		*session
	}{{"guest", guest}, {"host", host}} {
		st := s.end(t)
		if st.Reason != ReasonStdinClosed {
			t.Errorf("%s stopped with %s", s.name, st.Reason)
		}
		all := s.stream.seen()
		if last := all[len(all)-1]; last.Type != "stopped" || last.Reason != "stdin-closed" {
			t.Errorf("%s last line = %s", s.name, last.Raw)
		}
	}
	var serverSaid []string
	for _, l := range host.stream.seen() {
		if l.Type == "server-log" {
			serverSaid = append(serverSaid, l.Line)
		}
	}
	if len(serverSaid) != 1 || serverSaid[0] != "stdin closed" {
		t.Errorf("server's stdin was not closed before it stopped: %v", serverSaid)
	}
}

func TestSignalStops(t *testing.T) {
	relay := localRelay(t)
	host, invite := startHost(t, HostConfig{ServerPort: echoServer(t), Region: relay})
	guest := startGuest(t, invite, testLink)
	guest.stream.until("linked")

	for _, s := range []*session{guest, host} {
		s.stop(ReasonSignal)
		if st := s.result(t, eventWait); st.Reason != ReasonSignal {
			t.Errorf("stopped with %s", st.Reason)
		}
	}
}

func TestGuestStopsWhenHostGoesSilent(t *testing.T) {
	relay := localRelay(t)
	const silence = 3 * time.Second
	// A process of its own, as a real host is. Tailcat's netstack can take past
	// closeTimeout to close, and until the process exits its engine still answers pings.
	host := startHelperHost(t, relay, echoServer(t), "child-until-eof")
	guest := startGuest(t, hostedOn(t, host.stream.until("invite").Code, relay), 2*silence)
	guest.stream.until("path")

	host.cmd.Process.Kill()
	st := guest.result(t, 2*silence+pathPoll+pathTimeout+time.Second)
	if st.Reason != ReasonLinkLost {
		t.Errorf("stopped with %s", st.Reason)
	}
}

func TestInviteFailures(t *testing.T) {
	relay := localRelay(t)
	_, invite := startHost(t, HostConfig{ServerPort: echoServer(t), Region: relay})
	otherSecret := newSecret()
	for otherSecret == invite.code[:secretLength] {
		otherSecret = newSecret()
	}

	const deadline = 3 * time.Second
	tests := []struct {
		name string
		code string
		want Reason
	}{
		{"garbage", "hello there", ReasonBadInvite},
		{"empty line", "", ReasonBadInvite},
		{"dashes", invite.code[:3] + "-" + invite.code[3:6] + "-" + invite.code[6:], ReasonBadInvite},
		{"lower case", strings.ToLower(invite.code), ReasonBadInvite},
		{"one short", invite.code[1:], ReasonBadInvite},
		{"a character outside the alphabet", "O" + invite.code[1:], ReasonBadInvite},
		{"another secret", otherSecret + invite.code[secretLength:], ReasonNotFound},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Parallel()
			guest := startGuest(t, hosted{tt.code, relay, invite.derpMap}, deadline)
			st := guest.result(t, deadline+5*time.Second)
			if st.Reason != tt.want {
				t.Errorf("stopped with %s, want %s", st.Reason, tt.want)
			}
			for _, l := range guest.stream.seen() {
				if l.Type == "offer" {
					t.Errorf("read an offer: %s", l.Raw)
				}
			}
		})
	}
}

func TestARegionWithNoRelayIsNotFoundAtOnce(t *testing.T) {
	relay := localRelay(t)
	guest := startGuest(t, hosted{newSecret() + string(codeAlphabet[2]), relay, relayMap(t, relay)}, testLink)
	if st := guest.result(t, 5*time.Second); st.Reason != ReasonNotFound {
		t.Errorf("stopped with %s", st.Reason)
	}
}

func TestAWrongCodeIsNotFoundAtOnce(t *testing.T) {
	relay := localRelay(t)
	guest := startGuest(t, hostedOn(t, newSecret()+string(codeAlphabet[int(relay.RegionID)%len(codeAlphabet)]), relay), testLink)
	if st := guest.result(t, heardWithin); st.Reason != ReasonNotFound {
		t.Errorf("stopped with %s", st.Reason)
	}
}

func TestTheCodeOfAnEndedGameIsNotFoundAtOnce(t *testing.T) {
	relay := localRelay(t)
	host := startHelperHost(t, relay, echoServer(t), "child-until-eof")
	invite := hostedOn(t, host.stream.until("invite").Code, relay)
	host.stdin.Close()
	host.exit()

	guest := startGuest(t, invite, testLink)
	if st := guest.result(t, heardWithin); st.Reason != ReasonNotFound {
		t.Errorf("stopped with %s", st.Reason)
	}
}

func TestAHostOnARelayThatSaysNothingTimesOut(t *testing.T) {
	relay := localRelay(t)
	silent := withID(relay, relay.RegionID)
	_, closed, _ := net.SplitHostPort(freeTCPAddress(t))
	for _, node := range silent.Nodes {
		node.DERPPort, _ = strconv.Atoi(closed)
	}
	guest := startGuest(t, hostedOn(t, newSecret()+string(codeAlphabet[int(relay.RegionID)%len(codeAlphabet)]), silent), 2*time.Second)
	if st := guest.result(t, 2*time.Second+heardWithin); st.Reason != ReasonLinkTimeout {
		t.Errorf("stopped with %s", st.Reason)
	}
}

func TestTheCodeFindsTheHostAmongRegionsThatShareItsCharacter(t *testing.T) {
	for _, ids := range [][2]tailcfg.DERPRegionID{{331, 301}, {301, 331}} {
		t.Run(fmt.Sprint(ids[0]), func(t *testing.T) {
			host, decoy := withID(localRelay(t), ids[0]), withID(localRelay(t), ids[1])
			_, invite := startHost(t, HostConfig{ServerPort: echoServer(t), Region: host})
			if invite.code[secretLength] != codeAlphabet[int(ids[0])%30] {
				t.Errorf("region character = %c, want %c", invite.code[secretLength], codeAlphabet[int(ids[0])%30])
			}
			invite.derpMap = relayMap(t, host, decoy, withID(decoy, 2), withID(decoy, 45))
			guest := startGuest(t, invite, testLink)
			dialGame(t, guest.linkedGame()).exchange("found among regions")
		})
	}
}

func withID(region *tailcfg.DERPRegion, id tailcfg.DERPRegionID) *tailcfg.DERPRegion {
	moved := region.Clone()
	moved.RegionID = id
	for _, node := range moved.Nodes {
		node.RegionID = id
	}
	return moved
}

func TestADeclinedOfferStopsWithNoGamePort(t *testing.T) {
	_, invite := startHost(t, HostConfig{ServerPort: echoServer(t), Region: localRelay(t)})
	game := freeUDPAddress(t)
	guest := guestSessionOf(t, game, invite.derpMap, testLink, 0)
	fmt.Fprintln(guest.stdin, invite.code)
	guest.stream.until("offer")

	time.Sleep(time.Second)
	taken, err := net.ListenUDP("udp4", net.UDPAddrFromAddrPort(netip.MustParseAddrPort(game)))
	if err != nil {
		t.Fatalf("the game port is open before the yes: %v", err)
	}
	taken.Close()
	if st := guest.end(t); st.Reason != ReasonStdinClosed {
		t.Errorf("stopped with %s", st.Reason)
	}
	for _, l := range guest.stream.seen() {
		if l.Type == "linked" || l.Type == "path" {
			t.Errorf("said %s with no yes", l.Raw)
		}
	}
}

func TestAHostThatVanishesBeforeTheYesEndsTheWait(t *testing.T) {
	relay := localRelay(t)
	const silence = 3 * time.Second
	host := startHelperHost(t, relay, echoServer(t), "child-until-eof")
	invite := hostedOn(t, host.stream.until("invite").Code, relay)
	guest := guestSessionOf(t, freeUDPAddress(t), invite.derpMap, silence, 0)
	fmt.Fprintln(guest.stdin, invite.code)
	guest.stream.until("offer")

	host.cmd.Process.Kill()
	if st := guest.result(t, silence+pathPoll+pathTimeout+time.Second); st.Reason != ReasonLinkLost {
		t.Errorf("stopped with %s", st.Reason)
	}
}

const heardWithin = 3 * time.Second

func TestAGuestWaitingToSayYesHearsTheHostEnd(t *testing.T) {
	relay := localRelay(t)
	host := startHelperHost(t, relay, echoServer(t), "child-until-eof")
	invite := hostedOn(t, host.stream.until("invite").Code, relay)
	guest := guestSessionOf(t, freeUDPAddress(t), invite.derpMap, testLink, 0)
	fmt.Fprintln(guest.stdin, invite.code)
	guest.stream.until("offer")

	host.stdin.Close()
	if st := guest.result(t, heardWithin); st.Reason != ReasonHostEnded {
		t.Errorf("stopped with %s", st.Reason)
	}
}

func TestALinkedGuestHearsTheHostEnd(t *testing.T) {
	relay := localRelay(t)
	host := startHelperHost(t, relay, echoServer(t), "child-until-eof")
	guest := startGuest(t, hostedOn(t, host.stream.until("invite").Code, relay), testLink)
	dialGame(t, guest.linkedGame()).exchange("before the end")

	host.stdin.Close()
	if st := guest.result(t, heardWithin); st.Reason != ReasonHostEnded {
		t.Errorf("stopped with %s", st.Reason)
	}
	if got := host.exit(); got != 0 {
		t.Errorf("host exit = %d", got)
	}
	if all := host.stream.seen(); len(all) == 0 || all[len(all)-1].Type != "stopped" || all[len(all)-1].Reason != string(ReasonStdinClosed) {
		t.Errorf("host's last lines = %v", all)
	}
}

func TestEveryGuestHearsTheHostEnd(t *testing.T) {
	relay := localRelay(t)
	host := startHelperHost(t, relay, echoServer(t), "child-until-eof")
	invite := hostedOn(t, host.stream.until("invite").Code, relay)
	first, second := startGuest(t, invite, testLink), startGuest(t, invite, testLink)
	first.stream.until("linked")
	second.stream.until("linked")

	host.stdin.Close()
	for _, guest := range []*session{first, second} {
		if st := guest.result(t, heardWithin); st.Reason != ReasonHostEnded {
			t.Errorf("stopped with %s", st.Reason)
		}
	}
}

func TestAGuestHearsTheHostsServerFail(t *testing.T) {
	relay := localRelay(t)
	host, invite := startHost(t, HostConfig{ServerPort: echoServer(t), Region: relay})
	guest := startGuest(t, invite, testLink)
	guest.stream.until("linked")

	if err := syscall.Kill(serverPID(t, host.stream, "child"), syscall.SIGKILL); err != nil {
		t.Fatal(err)
	}
	if st := host.result(t, eventWait); st.Reason != ReasonServerExited {
		t.Errorf("host stopped with %s", st.Reason)
	}
	if st := guest.result(t, heardWithin); st.Reason != ReasonHostFailed {
		t.Errorf("guest stopped with %s", st.Reason)
	}
}

func TestAGuestWhoLeftDoesNotHoldUpTheHostsEnd(t *testing.T) {
	relay := localRelay(t)
	host, invite := startHost(t, HostConfig{ServerPort: echoServer(t), Region: relay})
	guest := startGuest(t, invite, testLink)
	guest.stream.until("linked")
	guest.end(t)

	began := time.Now()
	host.end(t)
	if took := time.Since(began); took > goodbyeWait+closeTimeout+time.Second {
		t.Errorf("the host took %v to end", took)
	}
}

func TestAJoinLineThatIsNotJoinsFlagsStops(t *testing.T) {
	_, invite := startHost(t, HostConfig{ServerPort: echoServer(t), Region: localRelay(t)})
	for name, yes := range map[string]string{
		"not JSON":     "join --page " + noPage,
		"no page":      `join ["--steam-id","1"]`,
		"a page path":  `join ["--page","bridge/"]`,
		"half a fetch": `join ["--page","` + noPage + `","--fetch","pack.zip"]`,
		"a stray word": `join ["--page","` + noPage + `","more"]`,
	} {
		t.Run(name, func(t *testing.T) {
			guest := guestSessionOf(t, freeUDPAddress(t), invite.derpMap, testLink, 0)
			fmt.Fprintf(guest.stdin, "%s\nenter 5\n%s\n", invite.code, yes)
			st := guest.result(t, eventWait)
			if st.Reason != ReasonFailed || !strings.HasPrefix(st.Message, "usage: join ") {
				t.Errorf("stopped with %+v", st)
			}
		})
	}
}

func TestInviteDeadlineWithNoInvite(t *testing.T) {
	guest := guestSessionOf(t, "127.0.0.1:0", "", 200*time.Millisecond, 0)
	if st := guest.result(t, 5*time.Second); st.Reason != ReasonLinkTimeout {
		t.Errorf("stopped with %s", st.Reason)
	}
}

func TestStdinClosedBeforeInvite(t *testing.T) {
	guest := guestSessionOf(t, "127.0.0.1:0", "", testLink, 0)
	if st := guest.end(t); st.Reason != ReasonStdinClosed {
		t.Errorf("stopped with %s", st.Reason)
	}
}

func TestListenFailed(t *testing.T) {
	relay := localRelay(t)
	_, invite := startHost(t, HostConfig{ServerPort: echoServer(t), Region: relay})
	taken, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer taken.Close()
	guest := guestSessionOf(t, taken.LocalAddr().String(), invite.derpMap, testLink, 0)
	go answer(guest.stdin, invite.code, "--page", noPage)
	if st := guest.result(t, eventWait); st.Reason != ReasonListenFailed || st.Message == "" {
		t.Errorf("stopped with %+v", st)
	}
}

func TestServerOutputArrivesBeforeStopped(t *testing.T) {
	relay := localRelay(t)
	host := hostSessionOf(t, HostConfig{ServerPort: 1, ServerArgv: childArgv("child-exit", "1"), Region: relay})
	st := host.result(t, eventWait)
	if st.Reason != ReasonServerExited {
		t.Fatalf("stopped with %+v", st)
	}
	all := host.stream.seen()
	last := all[len(all)-1]
	if last.Type != "stopped" {
		t.Errorf("last line = %s", last.Raw)
	}
	var sawJSON, sawText bool
	for _, l := range all {
		sawJSON = sawJSON || (l.Type == "server" && string(l.Event) == `{"hello":"world"}`)
		sawText = sawText || (l.Type == "server-log" && l.Line == "plain text")
	}
	if !sawJSON || !sawText {
		t.Errorf("server output lost before stopped: %v", all)
	}
}

func TestServerExitCarriesItsLastStderrLine(t *testing.T) {
	relay := localRelay(t)
	host := hostSessionOf(t, HostConfig{ServerPort: 1, ServerArgv: childArgv("child-says-why", "Proton is not installed"), Region: relay})
	st := host.result(t, eventWait)
	if st.Reason != ReasonServerExited || st.Message != "Proton is not installed" {
		t.Errorf("stopped with %+v", st)
	}
}

func TestServerExitMessageIsBounded(t *testing.T) {
	relay := localRelay(t)
	host := hostSessionOf(t, HostConfig{ServerPort: 1, ServerArgv: childArgv("child-long-line"), Region: relay})
	st := host.result(t, eventWait)
	if st.Reason != ReasonServerExited || len(st.Message) != maxMessage {
		t.Errorf("stopped with reason %s and a message of %d bytes", st.Reason, len(st.Message))
	}
}

const (
	engineLoading = "CNetworkGameServerBase::SetServerState (ss_waitingforgamesessionmanifest -> ss_loading)\r\n"
	engineActive  = "CNetworkGameServerBase::SetServerState (ss_loading -> ss_active)\r\n"
	engineDead    = "CNetworkGameServerBase::SetServerState (ss_active -> ss_dead)\r\n"
	engineBack    = "CNetworkGameServerBase::SetServerState (ss_dead -> ss_waitingforgamesessionmanifest)\r\n"
	engineIdle    = "Host activate: Idle (console)\r\n"
)

func hostWithConsole(t *testing.T, deadAfter time.Duration, lines ...string) *session {
	t.Helper()
	argv := childArgv(append([]string{"child-console", filepath.Join(t.TempDir(), "server.log")}, lines...)...)
	return hostSessionOf(t, HostConfig{ServerPort: 1, ServerArgv: argv, Region: localRelay(t), DeadAfter: deadAfter})
}

func TestAServerThatStaysDeadEndsTheSessionWithItsProcessAlive(t *testing.T) {
	host := hostWithConsole(t, 200*time.Millisecond, engineLoading, "CNetworkGameServerBase::SetServerState (ss_loading -> ss_dead)\r\n", engineIdle)
	host.stream.until("server")
	if st := host.result(t, eventWait); st.Reason != ReasonServerDead {
		t.Fatalf("stopped with %+v, want %s", st, ReasonServerDead)
	}
}

func TestAServerThatDiesInMidGameEndsTheSession(t *testing.T) {
	host := hostWithConsole(t, 200*time.Millisecond, engineLoading, engineActive, engineDead)
	if st := host.result(t, eventWait); st.Reason != ReasonServerDead {
		t.Fatalf("stopped with %+v, want %s", st, ReasonServerDead)
	}
}

func TestAWorldReloadPassesThroughDeadAndTheSessionGoesOn(t *testing.T) {
	host := hostWithConsole(t, 200*time.Millisecond, engineLoading, engineActive, engineDead, engineBack, engineLoading, engineActive)
	host.stream.until("invite")
	time.Sleep(2*consolePoll + time.Second)
	if st := host.end(t); st.Reason != ReasonStdinClosed {
		t.Errorf("stopped with %+v, want it to go on until asked", st)
	}
}

func TestALineTheConsoleHasNotFinishedDoesNotCount(t *testing.T) {
	host := hostWithConsole(t, 200*time.Millisecond, "CNetworkGameServerBase::SetServerState (ss_active -> ss_dead)")
	host.stream.until("invite")
	time.Sleep(2*consolePoll + time.Second)
	if st := host.end(t); st.Reason != ReasonStdinClosed {
		t.Errorf("stopped with %+v, want it to go on until asked", st)
	}
}

func TestAPlayersOwnWordsCannotEndTheSession(t *testing.T) {
	said := "[All Chat] anyone: CNetworkGameServerBase::SetServerState (ss_active -> ss_dead)\r\n"
	named := "Client \"CNetworkGameServerBase::SetServerState (ss_active -> ss_dead)\" connected\r\n"
	host := hostWithConsole(t, 200*time.Millisecond, engineLoading, engineActive, said, named)
	host.stream.until("invite")
	time.Sleep(2*consolePoll + time.Second)
	if st := host.end(t); st.Reason != ReasonStdinClosed {
		t.Errorf("stopped with %+v, want it to go on until asked", st)
	}
}

func TestAServerWithNoConsoleEndsOnlyByExiting(t *testing.T) {
	host := hostSessionOf(t, HostConfig{ServerPort: 1, ServerArgv: childArgv("child-exit", "0"), Region: localRelay(t), DeadAfter: time.Millisecond})
	if st := host.result(t, eventWait); st.Reason != ReasonServerExited {
		t.Fatalf("stopped with %+v, want %s", st, ReasonServerExited)
	}
}

func TestAskedToStopSaysNothingExtra(t *testing.T) {
	relay := localRelay(t)
	host, _ := startHost(t, HostConfig{ServerPort: 1, Region: relay})
	st := host.end(t)
	if st.Reason != ReasonStdinClosed || st.Message != "" {
		t.Errorf("stopped with %+v", st)
	}
}

func TestServerStartFailed(t *testing.T) {
	relay := localRelay(t)
	host := hostSessionOf(t, HostConfig{ServerPort: 1, ServerArgv: []string{"/no/such/server"}, Region: relay})
	st := host.result(t, eventWait)
	if st.Reason != ReasonServerStartFailed || st.Message == "" {
		t.Errorf("stopped with %+v", st)
	}
	for _, l := range host.stream.seen() {
		if l.Type == "invite" {
			t.Error("offered an invite for a server that never started")
		}
	}
}

func TestStubbornServerIsKilledAtTheDeadline(t *testing.T) {
	relay := localRelay(t)
	const grace = 1500 * time.Millisecond
	host, _ := startHost(t, HostConfig{
		ServerPort: 1,
		ServerArgv: childArgv("child-ignores-stop"),
		KillAfter:  grace,
		Region:     relay,
	})
	pid := serverPID(t, host.stream, "child")
	if !processAlive(pid) {
		t.Fatal("server not running before the stop")
	}
	began := time.Now()
	st := host.end(t)
	took := time.Since(began)
	if st.Reason != ReasonStdinClosed {
		t.Errorf("stopped with %s", st.Reason)
	}
	if took < grace {
		t.Errorf("stopped after %v, before the %v grace ended", took, grace)
	}
	if took > grace+closeTimeout {
		t.Errorf("stopped after %v", took)
	}
	if processAlive(pid) {
		t.Error("server still alive after the stop")
	}
}

func TestRelayUnavailable(t *testing.T) {
	closed, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	url := "http://" + closed.Addr().String() + "/derpmap.json"
	closed.Close()

	host := hostSessionOf(t, HostConfig{ServerPort: 1, ServerArgv: childArgv("child-exit", "0"), DERPMapURL: url})
	st := host.result(t, eventWait)
	if st.Reason != ReasonRelayUnavailable {
		t.Fatalf("stopped with %+v", st)
	}
	if all := host.stream.seen(); len(all) != 1 {
		t.Errorf("server started without a relay: %v", all)
	}
}

type helper struct {
	gameListen   string
	screenListen string
	t            *testing.T
	cmd          *exec.Cmd
	stream       *stream
	stdin        io.WriteCloser
	stderr       *strings.Builder
	waited       chan struct{}
}

func startHelper(t *testing.T, args ...string) *helper {
	t.Helper()
	h := &helper{t: t, stream: newStream(t), stderr: new(strings.Builder), waited: make(chan struct{})}
	h.cmd = exec.Command(os.Args[0], args...)
	h.cmd.Stderr = h.stderr
	h.cmd.WaitDelay = time.Second
	stdin, err := h.cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	h.stdin = stdin
	stdout, err := h.cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := h.cmd.Start(); err != nil {
		t.Fatal(err)
	}
	go func() {
		defer close(h.waited)
		sc := bufio.NewScanner(stdout)
		sc.Buffer(nil, 1<<20)
		for sc.Scan() {
			h.stream.add(sc.Text())
		}
		h.cmd.Wait()
	}()
	t.Cleanup(func() {
		h.cmd.Process.Kill()
		stdout.Close()
		<-h.waited
	})
	return h
}

func startJoinHelper(t *testing.T, invite hosted, flags ...string) *helper {
	t.Helper()
	game, screen := freeUDPAddress(t), freeTCPAddress(t)
	h := startHelper(t, "helper-join", game, invite.derpMap)
	h.gameListen, h.screenListen = game, screen
	answer(h.stdin, invite.code, append([]string{"--screen-listen", screen}, flags...)...)
	return h
}

func startHelperHost(t *testing.T, relay *tailcfg.DERPRegion, serverPort uint16, child ...string) *helper {
	t.Helper()
	region, err := json.Marshal(relay)
	if err != nil {
		t.Fatal(err)
	}
	args := []string{"helper-host", string(region), "--server-port", strconv.Itoa(int(serverPort)), "--steam-id", hostAccount, "--page", noPage, "--offer", testOffer, "--", os.Args[0]}
	return startHelper(t, append(args, child...)...)
}

func (h *helper) exit() int {
	h.t.Helper()
	select {
	case <-h.waited:
		return h.cmd.ProcessState.ExitCode()
	case <-time.After(eventWait):
		h.t.Fatalf("helper still running, saw %v", h.stream.seen())
		return -1
	}
}

func serverPID(t testing.TB, s *stream, role string) int {
	t.Helper()
	timeout := time.After(eventWait)
	for {
		select {
		case l := <-s.lines:
			var said struct {
				Role string `json:"role"`
				PID  int    `json:"pid"`
			}
			if l.Type == "server" && json.Unmarshal(l.Event, &said) == nil && said.Role == role {
				return said.PID
			}
		case <-timeout:
			t.Fatalf("no pid from %s, saw %v", role, s.seen())
		}
	}
}

func TestProcessExitCodes(t *testing.T) {
	guest := startHelper(t, "helper-join", freeUDPAddress(t), "")
	fmt.Fprintln(guest.stdin, "garbage")
	if got := guest.exit(); got != 1 {
		t.Errorf("exit code = %d, want 1", got)
	}
	if all := guest.stream.seen(); len(all) != 1 || all[0].Reason != "bad-invite" {
		t.Errorf("events = %v", all)
	}
}

func TestUsageErrors(t *testing.T) {
	for _, args := range [][]string{
		{},
		{"nonsense"},
		{"host"},
		{"host", "--server-port", "70000", "--", "x"},
		{"host", "--server-port", "1"},
		{"host", "--server-port", "1", "--page", "https://hideouthq.gg/bridge/", "--", "x"},
		{"host", "--server-port", "1", "--steam-id", "1", "--page", "https://hideouthq.gg/bridge/", "--", "x"},
		{"host", "--server-port", "1", "--steam-id", "1", "--page", "https://hideouthq.gg/bridge/", "--offer", "a b", "--", "x"},
		{"host", "--server-port", "1", "--steam-id", "1", "--page", "https://hideouthq.gg/bridge/", "--offer", strings.Repeat("a", 1025), "--", "x"},
		{"join", "--page", "https://hideouthq.gg/bridge/"},
	} {
		var stdout strings.Builder
		if got := run(args, strings.NewReader(""), &stdout); got != usageExit {
			t.Errorf("run(%v) = %d, want %d", args, got, usageExit)
		}
		if stdout.Len() != 0 {
			t.Errorf("run(%v) wrote %q to stdout", args, stdout.String())
		}
	}
}

func TestSecretsStayInTheInviteEvent(t *testing.T) {
	relay := localRelay(t)
	host := startHelperHost(t, relay, echoServer(t), "child-dumps-environment")
	invite := hostedOn(t, host.stream.until("invite").Code, relay)
	guest := startJoinHelper(t, invite, "--page", noPage)
	guest.stream.until("linked")
	dialGame(t, guest.gameListen).exchange("hello")
	host.stream.until("flow-open")
	guest.stream.until("path")

	guest.stdin.Close()
	if got := guest.exit(); got != 0 {
		t.Errorf("guest exit = %d", got)
	}
	host.stdin.Close()
	if got := host.exit(); got != 0 {
		t.Errorf("host exit = %d", got)
	}

	keys := deriveKeys(invite.code[:secretLength])
	psk, err := keys.psk.MarshalBinary()
	if err != nil {
		t.Fatal(err)
	}
	pskText, err := keys.psk.MarshalText()
	if err != nil {
		t.Fatal(err)
	}
	secrets := map[string]string{
		"code":       invite.code,
		"secret":     invite.code[:secretLength],
		"address":    string(invite.addr()),
		"psk text":   string(pskText),
		"psk base64": base64.StdEncoding.EncodeToString(psk),
		"psk hex":    fmt.Sprintf("%x", psk),
		"server key": keys.node.Public().String(),
	}
	address := regexp.MustCompile(`\b\d{1,3}(\.\d{1,3}){3}\b`)
	check := func(text string) {
		for name, secret := range secrets {
			if strings.Contains(text, secret) {
				t.Errorf("%s leaked in %q", name, text)
			}
		}
	}
	for _, h := range []*helper{host, guest} {
		check(h.stderr.String())
		all := h.stream.seen()
		if last := all[len(all)-1]; last.Type != "stopped" {
			t.Errorf("last line = %s", last.Raw)
		}
		for _, l := range all {
			if l.Type == "invite" {
				continue
			}
			check(l.Raw)
			if l.Type == "server-log" {
				continue
			}
			for _, ip := range address.FindAllString(l.Raw, -1) {
				if ip != "127.0.0.1" {
					t.Errorf("address %s in %s", ip, l.Raw)
				}
			}
		}
	}
}
