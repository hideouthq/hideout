package main

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"slices"
	"strconv"
	"testing"
	"time"
)

func (r *pageReader) sentConnects() []uint32 {
	r.mu.Lock()
	defer r.mu.Unlock()
	return slices.Clone(r.connects)
}

func portNumber(t testing.TB, address string) uint32 {
	t.Helper()
	port, err := strconv.ParseUint(portOf(t, address), 10, 16)
	if err != nil {
		t.Fatal(err)
	}
	return uint32(port)
}

func count(s *stream, eventType string) int {
	n := 0
	for _, l := range s.seen() {
		if l.Type == eventType {
			n++
		}
	}
	return n
}

func TestAnEnterWaitsForTheGamesPageAndSendsOneConnect(t *testing.T) {
	m := startMatch(t)
	guest := startJoinHelper(t, m.invite, "--page", m.page, "--steam-id", strconv.FormatUint(accountA, 10))
	guest.stream.until("linked")
	fmt.Fprintln(guest.stdin, "enter 30")
	fmt.Fprintln(guest.stdin, "enter 30")
	time.Sleep(pageSettle)
	if count(guest.stream, "entered") != 0 {
		t.Fatal("the helper said entered before the game's page opened")
	}

	page := openPage(t, guest.screenListen, originOf(m.page))
	guest.stream.until("entered")
	time.Sleep(pageSettle)
	if got := page.sentConnects(); !slices.Equal(got, []uint32{portNumber(t, guest.gameListen)}) {
		t.Fatalf("the game was told to connect to %v, want once to the guest's game listener", got)
	}
	if said := count(guest.stream, "entered"); said != 1 {
		t.Fatalf("two enters said entered %d times, want once", said)
	}

	game := dialGame(t, guest.gameListen)
	game.exchange(fmt.Sprintf("join %d", accountA))
	fmt.Fprintln(guest.stdin, "enter 30")
	guest.stream.until("entered")
	time.Sleep(pageSettle)
	if got := page.sentConnects(); len(got) != 1 {
		t.Errorf("a game already on the server was told to connect again: %v", got)
	}
}

func TestARepeatEnterWhileOneWaitsWaitsAsLongAsTheLongerAndStillSendsOneConnect(t *testing.T) {
	m := startMatch(t)
	guest := startJoinHelper(t, m.invite, "--page", m.page, "--steam-id", strconv.FormatUint(accountA, 10))
	guest.stream.until("linked")
	fmt.Fprintln(guest.stdin, "enter 1")
	fmt.Fprintln(guest.stdin, "enter 30")
	time.Sleep(2 * time.Second)

	page := openPage(t, guest.screenListen, originOf(m.page))
	guest.stream.until("entered")
	time.Sleep(pageSettle)
	if expired := count(guest.stream, "enter-expired"); expired != 0 {
		t.Fatalf("the first window expired %d times though the second was still open", expired)
	}
	if said := count(guest.stream, "entered"); said != 1 {
		t.Fatalf("said entered %d times, want once", said)
	}
	if got := page.sentConnects(); len(got) != 1 {
		t.Fatalf("the game was told to connect %d times, want once", len(got))
	}
}

func TestTheHostsGameEntersByTheServersPortAndIsSaidOnItWhileItsAccountIs(t *testing.T) {
	address := freeTCPAddress(t)
	m := startMatch(t, "--steam-id", strconv.FormatUint(accountA, 10), "--screen-listen", address)
	fmt.Fprintln(m.host.stdin, "enter 30")
	page := openPage(t, address, originOf(m.page))
	m.host.stream.until("entered")
	time.Sleep(pageSettle)
	if got := page.sentConnects(); !slices.Equal(got, []uint32{uint32(m.port)}) {
		t.Fatalf("the host's game was told to connect to %v, want the server's port %d", got, m.port)
	}

	own := dialGame(t, fmt.Sprintf("127.0.0.1:%d", m.port))
	own.exchange(fmt.Sprintf("join %d", accountA))
	m.host.stream.untilGame("flowing")
	fmt.Fprintln(m.host.stdin, "enter 30")
	m.host.stream.until("entered")
	own.exchange("leave 2")
	m.host.stream.untilGame("quiet")
	if got := page.sentConnects(); len(got) != 1 {
		t.Errorf("a game already on the server was told to connect again: %v", got)
	}
}

func TestAnEnterThePageNeverTakesSaysWhetherThePageCouldLoad(t *testing.T) {
	parked := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		fmt.Fprint(w, "<html>This domain is parked</html>")
	}))
	defer parked.Close()
	redirected := httptest.NewServer(http.RedirectHandler("/elsewhere", http.StatusPermanentRedirect))
	defer redirected.Close()
	down := httptest.NewServer(http.NotFoundHandler())
	downURL := down.URL + "/bridge/"
	down.Close()

	m := startMatch(t)
	for name, test := range map[string]struct{ page, want string }{
		"down":       {downURL, "unreachable"},
		"parked":     {parked.URL + "/bridge/", "unreachable"},
		"redirected": {redirected.URL + "/bridge/", "unreachable"},
		"up":         {m.page, ""},
	} {
		t.Run(name, func(t *testing.T) {
			guest := startJoinHelper(t, m.invite, "--page", test.page, "--steam-id", strconv.FormatUint(accountA, 10))
			guest.stream.until("linked")
			ctx, cancel := context.WithTimeout(context.Background(), eventWait)
			defer cancel()
			if refused, err := http.DefaultClient.Do(pageRequest(ctx, guest.screenListen, "https://elsewhere.example")); err == nil {
				refused.Body.Close()
			}
			fmt.Fprintln(guest.stdin, "not a command")
			fmt.Fprintln(guest.stdin, "enter 2")
			if got := guest.stream.until("enter-expired"); got.Page != test.want {
				t.Errorf("said %s, want page %q", got.Raw, test.want)
			}
			dialGame(t, guest.gameListen).exchange("no menus, still a game " + name)
			guest.stdin.Close()
			if got := guest.exit(); got != 0 {
				t.Errorf("exit code = %d", got)
			}
		})
	}
}

func TestAnOpenPageOutranksALateFailedCheck(t *testing.T) {
	release := make(chan struct{})
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		<-release
		w.WriteHeader(http.StatusNotFound)
	}))
	defer slow.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	page, err := startPage(ctx, slow.URL+"/bridge/", "127.0.0.1:0", newGuestScreen().source, func() bool { return false })
	if err != nil {
		t.Fatal(err)
	}
	defer page.close()
	reader := openPage(t, page.addr(), slow.URL)
	reader.until()
	reader.cancel()
	reader.waitClosed()
	close(release)
	time.Sleep(pageSettle)

	said := make(chan Event, 1)
	lines := make(chan string, 1)
	go followCommands(ctx, lines, page, 1, func(e Event) { said <- e })
	lines <- "enter 1"
	select {
	case got := <-said:
		if got != (EnterExpired{}) {
			t.Fatalf("said %#v, want an expiry that blames no page", got)
		}
	case <-time.After(eventWait):
		t.Fatal("the enter never expired")
	}
}

func TestOnlyAWholePositiveNumberOfSecondsIsAnEnter(t *testing.T) {
	if got, err := parseEnter("enter 15"); err != nil || got.Within != 15*time.Second {
		t.Errorf("enter 15 = %v, %v", got, err)
	}
	for _, line := range []string{"enter", "enter 0", "enter -3", "enter 1.5", "enter 2 3", "enter15", "connect 1"} {
		if _, err := parseEnter(line); err == nil {
			t.Errorf("%q was taken as an enter", line)
		}
	}
}
