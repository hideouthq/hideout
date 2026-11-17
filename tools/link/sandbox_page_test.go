package main

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/paralin/modlock/src/wasm/ui"
	"github.com/tailscale/tailcat"
)

func runSandboxBridge(port string, argv []string) {
	number, err := strconv.Atoi(port)
	if err != nil {
		panic(err)
	}
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: number})
	if err != nil {
		panic(err)
	}
	server := exec.Command(argv[0], argv[1:]...)
	server.Stdout, server.Stderr = os.Stdout, os.Stderr
	input, err := server.StdinPipe()
	if err != nil {
		panic(err)
	}
	if err := server.Start(); err != nil {
		panic(err)
	}
	go func() {
		io.Copy(io.Discard, os.Stdin)
		input.Close()
		server.Process.Kill()
	}()
	go func() {
		seen := map[string]bool{}
		buf := make([]byte, maxDatagram)
		for {
			n, from, err := conn.ReadFromUDP(buf)
			if err != nil {
				return
			}
			text := string(buf[:n])
			conn.WriteToUDP(fmt.Appendf(nil, "%s|%d", text, from.Port), from)
			if !seen[text] {
				seen[text] = true
				fmt.Fprintln(input, text)
			}
		}
	}()
	server.Wait()
}

func nodeOfKind(screen *ui.Screen, kind ui.Kind) string {
	for _, tree := range screen.GetTrees() {
		for _, node := range tree.GetNodes() {
			if node.GetKind() == kind {
				return node.GetId()
			}
		}
	}
	return ""
}

func screenText(screen *ui.Screen) string { return strings.Join(texts(screen), "\n") }

func (p *browserPage) newest() *ui.Screen {
	p.mu.Lock()
	defer p.mu.Unlock()
	if len(p.shown) == 0 {
		return nil
	}
	return p.shown[len(p.shown)-1]
}

func (p *browserPage) showing(want string) time.Time {
	p.t.Helper()
	deadline := time.Now().Add(eventWait)
	for time.Now().Before(deadline) {
		if screen := p.newest(); screen != nil && strings.Contains(screenText(screen), want) {
			return time.Now()
		}
		time.Sleep(2 * time.Millisecond)
	}
	p.t.Fatalf("the page never showed %q, it shows:\n%s", want, screenText(p.newest()))
	return time.Time{}
}

func joinedAccount(t testing.TB, s *stream) string {
	t.Helper()
	timeout := time.After(eventWait)
	for {
		select {
		case l := <-s.lines:
			var event struct {
				Host struct {
					Joined *struct {
						SteamID string `json:"steamId"`
					} `json:"joined"`
				} `json:"host"`
			}
			if l.Type == "server" && json.Unmarshal(l.Event, &event) == nil && event.Host.Joined != nil && event.Host.Joined.SteamID != "" {
				return event.Host.Joined.SteamID
			}
		case <-timeout:
			t.Fatalf("no player joined the sandbox, saw %v", s.seen())
		}
	}
}

func TestARealModeReachesTheRealPageAndInputComesBack(t *testing.T) {
	modlockBin := os.Getenv("HIDEOUT_PAGE_MODLOCK")
	if modlockBin == "" {
		t.Skip("HIDEOUT_PAGE_MODLOCK is not set")
	}
	mode := os.Getenv("HIDEOUT_PAGE_MODE")
	home := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", home)
	t.Setenv("XDG_CACHE_HOME", home)
	t.Setenv("MODLOCK_NO_UPDATE_CHECK", "1")
	t.Setenv("MODLOCK_HOST", os.Getenv("HIDEOUT_PAGE_HOST"))

	site := sitePageTLS(t)
	relay := localRelay(t)
	port := freeUDPPort(t)
	region, err := json.Marshal(relay)
	if err != nil {
		t.Fatal(err)
	}
	host := startHelper(t, "helper-host", string(region), "--server-port", strconv.Itoa(int(port)), "--steam-id", hostAccount, "--page", site, "--offer", testOffer, "--mod", mode, "--",
		os.Args[0], "child-sandbox", strconv.Itoa(int(port)), modlockBin, "--json", "play", "--sandbox", "--watch=false", "--ui-port", "0", mode)
	invite := hostedOn(t, host.stream.until("invite").Code, relay)
	account := joinedAccount(t, host.stream)
	id, err := strconv.ParseUint(account, 10, 64)
	if err != nil {
		t.Fatal(err)
	}

	client := &tailcat.Client{Server: invite.addr(), Logf: func(string, ...any) {}}
	t.Cleanup(func() { client.Close() })
	ctx, cancel := context.WithTimeout(context.Background(), eventWait)
	defer cancel()
	guest := startJoinHelper(t, invite, "--page", site, "--steam-id", account)
	guest.stream.until("linked")
	game := dialGame(t, guest.gameListen)
	page := openInBrowser(t, fmt.Sprintf("%s?port=%s", site, portOf(t, guest.screenListen)))

	began := time.Now()
	deadline := began.Add(eventWait)
	for page.newest() == nil || nodeOfKind(page.newest(), ui.Kind_KIND_INPUT) == "" {
		if time.Now().After(deadline) {
			t.Fatal("the page never showed the mode's menu")
		}
		time.Sleep(10 * time.Millisecond)
	}
	screen := page.newest()
	button, entry := nodeOfKind(screen, ui.Kind_KIND_BUTTON), nodeOfKind(screen, ui.Kind_KIND_INPUT)
	mod := screen.GetTrees()[0].GetMod()

	pressed := time.Now()
	game.exchange(fmt.Sprintf(`{"press":{"mod":%q,"node":%q}}`, mod, button))
	waitForLog(t, host.stream, "STRESS press slot=0")
	t.Logf("press reached the mode: %d ms", time.Since(pressed).Milliseconds())

	game.exchange(fmt.Sprintf(`{"change":{"mod":%q,"event":{"node":%q,"text":"from the guest"}}}`, mod, entry))
	waitForLog(t, host.stream, `STRESS entry slot=0 text=\"from the guest\"`)

	costing, err := client.DialTCPPort(ctx, screenPort)
	if err != nil {
		t.Fatal(err)
	}
	defer costing.Close()
	asked := time.Now()
	costing.Write(binary.BigEndian.AppendUint64([]byte(streamMagic), id))
	if _, err := readChunk(costing); err != nil {
		t.Fatal(err)
	}
	snapshot, err := readChunk(costing)
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("whole screen for a guest arriving now: %d bytes, %d ms", len(snapshot), time.Since(asked).Milliseconds())
	var steady atomic.Int64
	go io.Copy(countingWriter{&steady}, costing)
	time.Sleep(costWindow)
	t.Logf("steady cost: %d bytes/s", steady.Load()/int64(costWindow.Seconds()))
	if len(snapshot) < 100 || steady.Load() == 0 {
		t.Errorf("the stream carried too little for the mode's menu: %d bytes, then %d", len(snapshot), steady.Load())
	}
}

const costWindow = 20 * time.Second

type countingWriter struct{ total *atomic.Int64 }

func (w countingWriter) Write(p []byte) (int, error) {
	w.total.Add(int64(len(p)))
	return len(p), nil
}

func waitForLog(t testing.TB, s *stream, text string) {
	t.Helper()
	timeout := time.After(eventWait)
	for {
		select {
		case l := <-s.lines:
			if l.Type == "server" && strings.Contains(string(l.Event), text) {
				return
			}
		case <-timeout:
			t.Fatalf("no log with %q", text)
		}
	}
}
