package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"math/rand/v2"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/paralin/modlock/panorama"
	modlock "github.com/paralin/modlock/session"
	"github.com/paralin/modlock/src/host_app/control"
	"github.com/paralin/modlock/src/wasm/ui"
	"github.com/tailscale/tailcat"
	"tailscale.com/tailcfg"
)

const (
	accountA uint64 = 1001
	accountB uint64 = 2002
	accountC uint64 = 3003

	hostAccount = "4004"
	gameAgent   = "Mozilla/5.0 Valve Source2 HTML"
)

// sitePage serves a copy of the site's bridge page the way Cloudflare Pages does, redirecting
// an index.html request and answering the folder's own URL directly.
func sitePage(t testing.TB) string {
	t.Helper()
	server := httptest.NewServer(http.FileServer(http.Dir("testdata")))
	t.Cleanup(server.Close)
	return server.URL + "/bridge/"
}

func originOf(page string) string {
	return page[:strings.Index(page[len("http://"):], "/")+len("http://")]
}

func freeTCPAddress(t testing.TB) string {
	t.Helper()
	return fmt.Sprintf("127.0.0.1:%d", freePort(t, "tcp4"))
}

func freeUDPPort(t testing.TB) uint16 {
	t.Helper()
	return freePort(t, "udp4")
}

// freePort finds a port nothing holds, below the range the kernel hands out for port 0. The helper binds it only
// later, and test.sh runs tests side by side, so a port from that range could go to another test's relay in between.
func freePort(t testing.TB, network string) uint16 {
	t.Helper()
	for range 100 {
		port := 10_000 + rand.IntN(20_000)
		address := fmt.Sprintf("127.0.0.1:%d", port)
		var held io.Closer
		var err error
		if network == "udp4" {
			held, err = net.ListenPacket(network, address)
		} else {
			held, err = net.Listen(network, address)
		}
		if err == nil {
			held.Close()
			return uint16(port)
		}
	}
	t.Fatalf("no free %s port", network)
	return 0
}

func freeUDPAddress(t testing.TB) string {
	t.Helper()
	return fmt.Sprintf("127.0.0.1:%d", freeUDPPort(t))
}

type pageReader struct {
	t      testing.TB
	cancel func()
	done   chan struct{}

	mu                        sync.Mutex
	seen                      [][]string
	last                      *ui.Screen
	disconnects, screensAfter int
	connects                  []uint32
}

func unpack(text string) []byte {
	var out []byte
	for _, r := range text {
		if r >= 0x100 {
			r -= 0x100
		}
		out = append(out, byte(r))
	}
	return out
}

func pageRequest(ctx context.Context, address, origin string) *http.Request {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, "http://"+address+"/panorama/stream", nil)
	if err != nil {
		panic(err)
	}
	request.Header.Set("Origin", origin)
	request.Header.Set("User-Agent", gameAgent)
	return request
}

func openPage(t testing.TB, address, origin string) *pageReader {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	response, err := http.DefaultClient.Do(pageRequest(ctx, address, origin))
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK {
		cancel()
		t.Fatalf("the page was answered %s", response.Status)
	}
	r := &pageReader{t: t, cancel: cancel, done: make(chan struct{})}
	t.Cleanup(cancel)
	go func() {
		defer close(r.done)
		defer response.Body.Close()
		lines := bufio.NewReader(response.Body)
		for {
			line, err := lines.ReadString('\n')
			if err != nil {
				return
			}
			_, packed, _ := strings.Cut(strings.TrimSuffix(line, "\n"), ":")
			frame := &panorama.Frame{}
			if err := frame.UnmarshalVT(unpack(packed)); err != nil {
				t.Errorf("a frame did not decode: %v", err)
				return
			}
			r.mu.Lock()
			if screen := frame.GetScreen(); screen != nil {
				r.seen = append(r.seen, texts(screen))
				r.last = screen
				if r.disconnects > 0 {
					r.screensAfter++
				}
			} else if frame.GetDisconnect() != nil {
				r.disconnects++
			} else if connect, ok := frame.GetBody().(*panorama.Frame_Connect); ok {
				r.connects = append(r.connects, connect.Connect)
			}
			r.mu.Unlock()
		}
	}()
	return r
}

func (r *pageReader) until(want ...string) *ui.Screen {
	r.t.Helper()
	want = slices.Sorted(slices.Values(want))
	deadline := time.Now().Add(eventWait)
	for time.Now().Before(deadline) {
		r.mu.Lock()
		last := r.last
		r.mu.Unlock()
		if last != nil && slices.Equal(texts(last), want) {
			return last
		}
		time.Sleep(10 * time.Millisecond)
	}
	r.t.Fatalf("the page never showed %q, saw %q", want, r.shown())
	return nil
}

func (r *pageReader) shown() [][]string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return slices.Clone(r.seen)
}

func (r *pageReader) neverShowed(marker string) {
	r.t.Helper()
	for _, screen := range r.shown() {
		for _, text := range screen {
			if strings.Contains(text, marker) {
				r.t.Errorf("the page showed %q, which belongs to another player", text)
			}
		}
	}
}

func (r *pageReader) waitClosed() {
	r.t.Helper()
	select {
	case <-r.done:
	case <-time.After(eventWait):
		r.t.Fatal("the page's stream stayed open")
	}
}

func texts(screen *ui.Screen) []string {
	out := []string{}
	for _, tree := range screen.GetTrees() {
		for _, node := range tree.GetNodes() {
			out = append(out, tree.GetMod()+"/"+node.GetId()+"="+node.GetText())
		}
	}
	return out
}

func markText(account uint64) string { return stubMod + "/mark=" + markerOf(account) }

type match struct {
	t      *testing.T
	relay  *tailcfg.DERPRegion
	host   *helper
	invite hosted
	page   string
	port   uint16
}

func startMatch(t *testing.T, hostFlags ...string) *match {
	t.Helper()
	relay := localRelay(t)
	port := freeUDPPort(t)
	page := sitePage(t)
	region, err := json.Marshal(relay)
	if err != nil {
		t.Fatal(err)
	}
	args := []string{"helper-host", string(region), "--server-port", strconv.Itoa(int(port)), "--steam-id", hostAccount, "--page", page, "--offer", testOffer}
	args = append(args, hostFlags...)
	args = append(args, "--", os.Args[0], "child-modlock", strconv.Itoa(int(port)))
	host := startHelper(t, args...)
	return &match{t: t, relay: relay, host: host, invite: hostedOn(t, host.stream.until("invite").Code, relay), page: page, port: port}
}

type guestPlayer struct {
	account uint64
	helper  *helper
	game    *gameClient
}

func (m *match) join(account uint64, flags ...string) *guestPlayer {
	m.t.Helper()
	guest := startJoinHelper(m.t, m.invite, append([]string{"--page", m.page, "--steam-id", strconv.FormatUint(account, 10)}, flags...)...)
	guest.stream.until("linked")
	player := &guestPlayer{account: account, helper: guest, game: dialGame(m.t, guest.gameListen)}
	player.game.exchange(fmt.Sprintf("join %d", account))
	return player
}

func (m *match) pageOf(p *guestPlayer) *pageReader {
	m.t.Helper()
	return openPage(m.t, p.helper.screenListen, originOf(m.page))
}

func TestEachPlayerSeesOnlyTheirOwnScreen(t *testing.T) {
	m := startMatch(t)
	a, b := m.join(accountA), m.join(accountB)
	pageA, pageB := m.pageOf(a), m.pageOf(b)

	pageA.until(markText(accountA))
	pageB.until(markText(accountB))
	for i := 1; i <= 20; i++ {
		a.game.exchange(fmt.Sprintf("press count %d", i))
		b.game.exchange(fmt.Sprintf("press count %d", i))
	}
	pageA.until(markText(accountA), stubMod+"/count=secret-1001 pressed 20")
	pageB.until(markText(accountB), stubMod+"/count=secret-2002 pressed 20")

	pageA.neverShowed(markerOf(accountB))
	pageB.neverShowed(markerOf(accountA))
	for _, l := range m.host.stream.seen() {
		if l.Type == "server" && strings.Contains(string(l.Event), `"ui"`) {
			t.Fatalf("an interface change reached Hideout: %s", l.Raw)
		}
	}
}

func TestTheTunnelCarriesNoOtherPlayersBytes(t *testing.T) {
	m := startMatch(t)
	a, b := m.join(accountA), m.join(accountB)
	client := &tailcat.Client{Server: m.invite.addr(), Logf: func(string, ...any) {}}
	t.Cleanup(func() { client.Close() })
	ctx, cancel := context.WithTimeout(context.Background(), eventWait)
	defer cancel()
	conn, err := client.DialTCPPort(ctx, screenPort)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if _, err := conn.Write(binary.BigEndian.AppendUint64([]byte(streamMagic), accountA)); err != nil {
		t.Fatal(err)
	}
	var raw bytes.Buffer
	received := make(chan struct{})
	go func() {
		defer close(received)
		io.Copy(&raw, conn)
	}()
	for i := 1; i <= 10; i++ {
		b.game.exchange(fmt.Sprintf("press count %d", i))
	}
	a.game.exchange("press count 1")
	m.pageOf(a).until(markText(accountA), stubMod+"/count=secret-1001 pressed 1")
	time.Sleep(3 * time.Second)
	conn.Close()
	<-received
	if !bytes.Contains(raw.Bytes(), []byte("secret-1001 pressed 1")) {
		t.Fatalf("the stream missed the player's own screen: %d bytes", raw.Len())
	}
	if bytes.Contains(raw.Bytes(), []byte(markerOf(accountB))) {
		t.Fatal("the stream carried another player's screen")
	}
}

func TestInputReachesTheModeFromTheRightPlayer(t *testing.T) {
	m := startMatch(t)
	a, b := m.join(accountA), m.join(accountB)
	pageA, pageB := m.pageOf(a), m.pageOf(b)

	a.game.exchange("press ready 1")
	pageA.until(markText(accountA), stubMod+"/ready=secret-1001 pressed 1")
	b.game.exchange("input name friend 1")
	pageB.until(markText(accountB), stubMod+"/name=secret-2002 typed friend")
	pageA.until(markText(accountA), stubMod+"/ready=secret-1001 pressed 1")
	pageB.neverShowed("ready")
	pageA.neverShowed("typed")
}

func TestAScreenFollowsReconnects(t *testing.T) {
	m := startMatch(t)
	a := m.join(accountA)
	first := m.pageOf(a)
	a.game.exchange("press count 1")
	first.until(markText(accountA), stubMod+"/count=secret-1001 pressed 1")

	first.cancel()
	first.waitClosed()
	a.game.exchange("press count 2")
	m.pageOf(a).until(markText(accountA), stubMod+"/count=secret-1001 pressed 2")

	a.helper.stdin.Close()
	if got := a.helper.exit(); got != 0 {
		t.Fatalf("exit code = %d", got)
	}
	restarted := startJoinHelper(t, m.invite, "--page", m.page, "--steam-id", strconv.FormatUint(accountA, 10))
	restarted.stream.until("linked")
	openPage(t, restarted.screenListen, originOf(m.page)).until(markText(accountA), stubMod+"/count=secret-1001 pressed 2")
}

func TestStopClosesTheScreenAndNothingIsLeftListening(t *testing.T) {
	m := startMatch(t)
	a := m.join(accountA)
	page := m.pageOf(a)
	page.until(markText(accountA))

	a.helper.stdin.Close()
	if got := a.helper.exit(); got != 0 {
		t.Fatalf("guest exit code = %d", got)
	}
	page.waitClosed()
	if conn, err := net.DialTimeout("tcp4", a.helper.screenListen, time.Second); err == nil {
		conn.Close()
		t.Error("the guest's screen port still answers after it stopped")
	}
	m.host.stdin.Close()
	if got := m.host.exit(); got != 0 {
		t.Fatalf("host exit code = %d", got)
	}
	reopened, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: int(m.port)})
	if err != nil {
		t.Fatalf("the stub server outlived the host: %v", err)
	}
	reopened.Close()
}

func (r *pageReader) disconnected() (disconnects, screensAfter int) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.disconnects, r.screensAfter
}

func TestStopTellsAGameOnTheServerToLeaveBeforeTheStreamCloses(t *testing.T) {
	address := freeTCPAddress(t)
	m := startMatch(t, "--steam-id", strconv.FormatUint(accountA, 10), "--screen-listen", address)
	own := dialGame(t, fmt.Sprintf("127.0.0.1:%d", m.port))
	own.exchange(fmt.Sprintf("join %d", accountA))
	page := openPage(t, address, originOf(m.page))
	page.until(markText(accountA))
	guest := m.join(accountB)
	guestPage := m.pageOf(guest)
	guestPage.until(markText(accountB))
	guest.game.connect()

	guest.helper.stdin.Close()
	if got := guest.helper.exit(); got != 0 {
		t.Fatalf("guest exit code = %d", got)
	}
	guestPage.waitClosed()
	if disconnects, after := guestPage.disconnected(); disconnects != 1 || after != 0 {
		t.Errorf("the guest's game was told to leave %d times, then shown %d screens; want once, last", disconnects, after)
	}

	m.host.stdin.Close()
	if got := m.host.exit(); got != 0 {
		t.Fatalf("host exit code = %d", got)
	}
	page.waitClosed()
	if disconnects, after := page.disconnected(); disconnects != 1 || after != 0 {
		t.Errorf("the host's game was told to leave %d times, then shown %d screens; want once, last", disconnects, after)
	}
}

func TestStopLeavesAGameThatIsNotOnTheServerAlone(t *testing.T) {
	m := startMatch(t)
	guest := m.join(accountA)
	page := m.pageOf(guest)
	page.until(markText(accountA))
	time.Sleep(gameTalking)

	guest.helper.stdin.Close()
	if got := guest.helper.exit(); got != 0 {
		t.Fatalf("guest exit code = %d", got)
	}
	page.waitClosed()
	if disconnects, _ := page.disconnected(); disconnects != 0 {
		t.Error("a game that had left the session's server was told to disconnect, which would take it off whatever server it is on now")
	}
}

func TestTheHostsOwnGameReadsItsOwnScreen(t *testing.T) {
	address := freeTCPAddress(t)
	mod := t.TempDir()
	manifest := fmt.Sprintf(`{"slug":%q,"name":"Arena","version":"1.0.0","contentAddon":"arena-abc"}`, stubMod)
	if err := os.WriteFile(filepath.Join(mod, "mod.json"), []byte(manifest), 0o644); err != nil {
		t.Fatal(err)
	}
	m := startMatch(t, "--steam-id", strconv.FormatUint(accountA, 10), "--screen-listen", address, "--mod", mod)
	own := dialGame(t, fmt.Sprintf("127.0.0.1:%d", m.port))
	own.exchange(fmt.Sprintf("join %d", accountA))
	b := m.join(accountB)
	pageHost, pageB := openPage(t, address, originOf(m.page)), m.pageOf(b)

	onHost := pageHost.until(markText(accountA))
	onGuest := pageB.until(markText(accountB))
	own.exchange("press count 1")
	pageHost.until(markText(accountA), stubMod+"/count=secret-1001 pressed 1")
	pageHost.neverShowed(markerOf(accountB))
	pageB.neverShowed(markerOf(accountA))
	for _, screen := range []*ui.Screen{onHost, onGuest} {
		if got := screen.GetTrees()[0].GetAddon(); got != "arena-abc" {
			t.Errorf("a tree names addon %q, so its layouts would not load", got)
		}
	}
}

func TestOnlyTheGamesPageReadsTheScreen(t *testing.T) {
	m := startMatch(t)
	a := m.join(accountA)
	ask := func(mutate func(*http.Request)) *http.Response {
		t.Helper()
		ctx, cancel := context.WithTimeout(context.Background(), eventWait)
		t.Cleanup(cancel)
		request := pageRequest(ctx, a.helper.screenListen, originOf(m.page))
		mutate(request)
		response, err := http.DefaultClient.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { response.Body.Close() })
		return response
	}
	refused := map[string]func(*http.Request){
		"another origin":    func(r *http.Request) { r.Header.Set("Origin", "https://elsewhere.example") },
		"an ordinary agent": func(r *http.Request) { r.Header.Set("User-Agent", "Mozilla/5.0 Chrome") },
		"a rebound host":    func(r *http.Request) { r.Host = "evil.example:80" },
	}
	for name, mutate := range refused {
		if response := ask(mutate); response.StatusCode != http.StatusForbidden {
			t.Errorf("%s was answered %s", name, response.Status)
		}
	}
	preflight := ask(func(r *http.Request) {
		r.Method = http.MethodOptions
		r.Header.Set("Access-Control-Request-Private-Network", "true")
	})
	if preflight.StatusCode != http.StatusNoContent || preflight.Header.Get("Access-Control-Allow-Private-Network") != "true" ||
		preflight.Header.Get("Access-Control-Allow-Origin") != originOf(m.page) {
		t.Errorf("preflight = %s %v", preflight.Status, preflight.Header)
	}
	if response := ask(func(*http.Request) {}); response.StatusCode != http.StatusOK {
		t.Errorf("the page was answered %s", response.Status)
	}
}

func applyFrames(t testing.TB, stream io.Reader, screen *guestScreen, frames *int, wanted func() bool) {
	t.Helper()
	for !wanted() {
		chunk, err := readChunk(stream)
		if err != nil {
			t.Fatal(err)
		}
		changes, reset, err := decodeFrame(chunk)
		if err != nil {
			t.Fatal(err)
		}
		screen.mirror.Apply(changes, reset)
		*frames++
	}
}

func feedEvent(t testing.TB, feed *screenFeed, event *control.HostEvent) {
	t.Helper()
	body, err := event.MarshalJSON()
	if err != nil {
		t.Fatal(err)
	}
	feed.take(fmt.Appendf(nil, `{"host":%s}`, body))
}

func joinedEvent(slot int32, account uint64) *control.HostEvent {
	return &control.HostEvent{Body: &control.HostEvent_Joined{Joined: &control.PlayerJoined{Slot: slot, SteamId: account}}}
}

func labelEvent(slot int32, id, text string) *control.HostEvent {
	return &control.HostEvent{Body: &control.HostEvent_Ui{Ui: &control.UiChanged{
		Mod: stubMod, Slot: slot, Change: &ui.Change{Set: []*ui.Node{{Id: id, Kind: ui.Kind_KIND_LABEL, Text: text}}},
	}}}
}

func leftEvent(slot int32) *control.HostEvent {
	return &control.HostEvent{Body: &control.HostEvent_Left{Left: &control.PlayerLeft{Slot: slot}}}
}

func drawEvent(slot int32, mod, text string) *control.HostEvent {
	return &control.HostEvent{Body: &control.HostEvent_Ui{Ui: &control.UiChanged{
		Mod: mod, Slot: slot, Change: &ui.Change{Set: []*ui.Node{{Id: "panel", Kind: ui.Kind_KIND_LABEL, Text: text}}},
	}}}
}

func TestTheNextPersonInASlotSeesNothingOfWhatCameBefore(t *testing.T) {
	tests := []struct {
		name   string
		before []*control.HostEvent
	}{
		{"a draw that lands after the leave", []*control.HostEvent{
			joinedEvent(1, accountA), drawEvent(1, stubMod, "A"), leftEvent(1), drawEvent(1, stubMod, "A, late"),
		}},
		{"a bot's panel, with no join to say whose", []*control.HostEvent{
			drawEvent(1, stubMod, "bot"), leftEvent(1),
		}},
		{"a bot's panel with no leave at all", []*control.HostEvent{
			drawEvent(1, stubMod, "bot"),
		}},
		{"a person replaced with no leave", []*control.HostEvent{
			joinedEvent(1, accountA), drawEvent(1, stubMod, "A"),
		}},
		{"a player who moved to another slot before the old one was freed", []*control.HostEvent{
			joinedEvent(1, accountA), drawEvent(1, "old-mod", "A, old"), joinedEvent(2, accountA), drawEvent(2, "new-mod", "A, new"), leftEvent(1),
		}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			// Maps iterate in a random order, and one run can miss a wrong pick.
			for range 100 {
				feed, err := newScreenFeed(nil)
				if err != nil {
					t.Fatal(err)
				}
				for _, event := range test.before {
					feedEvent(t, feed, event)
				}
				feedEvent(t, feed, joinedEvent(1, accountC))
				screen, _ := feed.source(accountC)()
				if got := texts(screen); len(got) != 0 {
					t.Fatalf("the next person is shown %q", got)
				}
			}
		})
	}
}

func TestAnAccountInTwoSlotsKeepsOnlyTheNewerOnesScreen(t *testing.T) {
	feed, err := newScreenFeed(nil)
	if err != nil {
		t.Fatal(err)
	}
	feedEvent(t, feed, joinedEvent(1, accountA))
	feedEvent(t, feed, drawEvent(1, stubMod, "older"))
	feedEvent(t, feed, joinedEvent(2, accountA))
	feedEvent(t, feed, drawEvent(2, stubMod, "newer"))
	want := []string{stubMod + "/panel=newer"}
	for range 200 {
		screen, _ := feed.source(accountA)()
		if got := texts(screen); !slices.Equal(got, want) {
			t.Fatalf("the account is shown %q, want %q", got, want)
		}
	}
	feedEvent(t, feed, leftEvent(1))
	screen, _ := feed.source(accountA)()
	if got := texts(screen); !slices.Equal(got, want) {
		t.Fatalf("the older slot's leave changed the screen to %q", got)
	}
}

func TestAPersonWhoStaysInASlotKeepsTheirScreenThroughARepeatedJoin(t *testing.T) {
	feed, err := newScreenFeed(nil)
	if err != nil {
		t.Fatal(err)
	}
	feedEvent(t, feed, joinedEvent(1, accountA))
	feedEvent(t, feed, drawEvent(1, stubMod, "mine"))
	feedEvent(t, feed, joinedEvent(1, accountA))
	screen, _ := feed.source(accountA)()
	if got, want := texts(screen), []string{stubMod + "/panel=mine"}; !slices.Equal(got, want) {
		t.Fatalf("shown %q, want %q", got, want)
	}
}

const pageSettle = time.Second

func TestASlowGuestSkipsStatesRatherThanQueueingThem(t *testing.T) {
	feed, err := newScreenFeed(nil)
	if err != nil {
		t.Fatal(err)
	}
	feedEvent(t, feed, joinedEvent(0, accountA))
	feedEvent(t, feed, labelEvent(0, "n", "start"))
	host, guest := net.Pipe()
	defer guest.Close()
	go feed.serveGuest(host)
	guest.SetDeadline(time.Now().Add(eventWait))
	if _, err := guest.Write(binary.BigEndian.AppendUint64([]byte(streamMagic), accountA)); err != nil {
		t.Fatal(err)
	}
	if _, err := readChunk(guest); err != nil {
		t.Fatal(err)
	}
	screen := newGuestScreen()
	frames := 0
	shown := func() string {
		got, _ := screen.source()
		return strings.Join(texts(got), ",")
	}
	applyFrames(t, guest, screen, &frames, func() bool { return shown() != "" })

	for i := 1; i <= 2000; i++ {
		feedEvent(t, feed, labelEvent(0, "n", fmt.Sprintf("state %d", i)))
	}
	time.Sleep(pageSettle)
	frames = 0
	applyFrames(t, guest, screen, &frames, func() bool { return shown() == stubMod+"/n=state 2000" })
	if frames > 3 {
		t.Fatalf("a guest that stopped reading got %d frames for 2000 states", frames)
	}
}

func TestACutStreamComesBackWithTheNewestScreen(t *testing.T) {
	feed, err := newScreenFeed(nil)
	if err != nil {
		t.Fatal(err)
	}
	feedEvent(t, feed, joinedEvent(0, accountA))
	feedEvent(t, feed, labelEvent(0, "stale", "goes during the cut"))

	var current sync.Mutex
	var open net.Conn
	dial := func(context.Context) (net.Conn, error) {
		host, guest := net.Pipe()
		go feed.serveGuest(host)
		current.Lock()
		defer current.Unlock()
		open = guest
		return guest, nil
	}
	ctx, stop := context.WithCancel(context.Background())
	defer stop()
	screen := newGuestScreen()
	go screen.follow(ctx, dial, accountA)
	waitForTexts := func(want ...string) {
		t.Helper()
		deadline := time.Now().Add(eventWait)
		for time.Now().Before(deadline) {
			got, _ := screen.source()
			if slices.Equal(texts(got), want) {
				return
			}
			time.Sleep(10 * time.Millisecond)
		}
		got, _ := screen.source()
		t.Fatalf("shows %q, want %q", texts(got), want)
	}
	waitForTexts(stubMod + "/stale=goes during the cut")

	current.Lock()
	open.Close()
	current.Unlock()
	feedEvent(t, feed, &control.HostEvent{Body: &control.HostEvent_Ui{Ui: &control.UiChanged{
		Mod: stubMod, Change: &ui.Change{Removed: []string{"stale"}, Set: []*ui.Node{{Id: "fresh", Kind: ui.Kind_KIND_LABEL, Text: "after the cut"}}},
	}}})
	waitForTexts(stubMod + "/fresh=after the cut")
}

func TestABadHelloGetsNothing(t *testing.T) {
	feed, err := newScreenFeed(nil)
	if err != nil {
		t.Fatal(err)
	}
	host, guest := net.Pipe()
	defer guest.Close()
	go feed.serveGuest(host)
	guest.SetDeadline(time.Now().Add(eventWait))
	if _, err := guest.Write([]byte("GET / HTTP/")); err != nil {
		t.Fatal(err)
	}
	if n, err := guest.Read(make([]byte, 1)); err != io.EOF || n != 0 {
		t.Fatalf("read %d bytes, %v", n, err)
	}
}

func TestAQuietMenusChangeGoesAtOnceAndABurstWaitsItsGap(t *testing.T) {
	now := time.Now()
	if wait := frameWait(time.Time{}, now); wait != 0 {
		t.Errorf("the first frame waited %v", wait)
	}
	if wait := frameWait(now.Add(-time.Second), now); wait != 0 {
		t.Errorf("a change after a quiet moment waited %v", wait)
	}
	if wait := frameWait(now.Add(-10*time.Millisecond), now); wait != modlock.FrameGap-10*time.Millisecond {
		t.Errorf("a change in a burst waited %v", wait)
	}
}

func TestFramesSurviveTheWireAndBadOnesAreRefused(t *testing.T) {
	changes := map[string]*ui.Change{
		"b": {Reset_: true, Generation: 7, Set: []*ui.Node{{Id: "n", Kind: ui.Kind_KIND_LABEL, Text: "é"}}},
		"a": {Removed: []string{"x"}},
	}
	frame, err := encodeFrame(changes, true)
	if err != nil {
		t.Fatal(err)
	}
	decoded, reset, err := decodeFrame(frame)
	if err != nil || !reset || len(decoded) != 2 {
		t.Fatalf("decoded %v, reset %v, %v", decoded, reset, err)
	}
	for mod, change := range changes {
		if !change.EqualVT(decoded[mod]) {
			t.Errorf("%s changed on the wire", mod)
		}
	}
	for cut := 2; cut < len(frame); cut++ {
		decodeFrame(frame[:cut])
	}
	if _, _, err := decodeFrame(nil); err == nil {
		t.Error("an empty frame was accepted")
	}
	huge := binary.BigEndian.AppendUint32(nil, maxChunk+1)
	if _, err := readChunk(bytes.NewReader(huge)); err == nil {
		t.Error("an oversize chunk was accepted")
	}
}

func TestCapturedEventsFoldAndOnlyLifecycleReachesHideout(t *testing.T) {
	data, err := os.ReadFile("testdata/screens-host.jsonl")
	if err != nil {
		t.Fatal(err)
	}
	feed, err := newScreenFeed(nil)
	if err != nil {
		t.Fatal(err)
	}
	passed := map[string]int{}
	sink := feed.filter(func(e Event) {
		if server, ok := e.(ServerEvent); ok {
			var event struct {
				Host map[string]json.RawMessage `json:"host"`
			}
			json.Unmarshal(server.Event, &event)
			for name := range event.Host {
				passed[name]++
			}
		}
	})
	for _, l := range bytes.Split(bytes.TrimSpace(data), []byte("\n")) {
		sink(ServerEvent{Event: l})
	}
	if passed["ui"] != 0 || passed["joined"] != 2 || passed["started"] == 0 {
		t.Fatalf("passed %v", passed)
	}
	first, _ := feed.source(76561197960265729)()
	second, _ := feed.source(76561197960265730)()
	if got := texts(first); !slices.Contains(got, "hello-ts/1=Hello, Player") || slices.Contains(got, "hello-ts/1=Hello, Friend") {
		t.Errorf("first player sees %q", got)
	}
	if got := texts(second); !slices.Contains(got, "hello-ts/1=Hello, Friend") || slices.Contains(got, "hello-ts/1=Hello, Player") {
		t.Errorf("second player sees %q", got)
	}
	for _, screen := range []*ui.Screen{first, second} {
		for _, tree := range screen.GetTrees() {
			if tree.GetMod() == "modlock" {
				t.Error("Modlock's own frame reached a player")
			}
		}
	}
}

func TestEveryVerbThatServesOrNamesThePageRefusesToRunWithoutIt(t *testing.T) {
	stdin := strings.NewReader("")
	for name, args := range map[string][]string{
		"host":  {"host", "--server-port", "1", "--", "x"},
		"menus": {"menus", "--game", "g", "--renderer", "r", "--screen-port", "1", "--out", "o"},
	} {
		if got := run(args, stdin, io.Discard); got != usageExit {
			t.Errorf("%s exit code = %d, want %d", name, got, usageExit)
		}
	}
}

func TestAModeThatTunesModifierDefinitionsStillNamesItsAddon(t *testing.T) {
	mod := t.TempDir()
	manifest := `{"slug":"bebop-wars","name":"Bebop Wars","version":"0.1.0","contentAddon":"bebop-wars-2ca41534",
		"definitions":[{"file":"FILE_MODIFIERS","entry":"citadel_ability_hook/modifier_citadel_hooktarget",
		"fields":{"CCitadel_Modifier_HookTargetVData.m_flReturnSpeed":1333.3334}}]}`
	if err := os.WriteFile(filepath.Join(mod, "mod.json"), []byte(manifest), 0o644); err != nil {
		t.Fatal(err)
	}
	addons, err := readAddons([]string{mod})
	if err != nil {
		t.Fatal(err)
	}
	if addons["bebop-wars"] != "bebop-wars-2ca41534" {
		t.Fatalf("addons = %v", addons)
	}
}
