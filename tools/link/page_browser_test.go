package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/paralin/modlock/panorama"
	"github.com/paralin/modlock/src/wasm/ui"
)

var devtoolsLine = regexp.MustCompile(`DevTools listening on (ws://\S+)`)

type browserPage struct {
	t           testing.TB
	mu          sync.Mutex
	shown       []*ui.Screen
	lines       []string
	disconnects []int
}

func openInBrowser(t testing.TB, pageURL string) *browserPage {
	t.Helper()
	chrome, err := exec.LookPath("google-chrome")
	if err != nil {
		t.Skip("no browser here")
	}
	node, err := exec.LookPath("node")
	if err != nil {
		t.Skip("no node here")
	}
	harness, err := filepath.Abs("testdata/page_in_browser.mjs")
	if err != nil {
		t.Fatal(err)
	}
	profile, err := os.MkdirTemp("", "hideout-page-browser")
	if err != nil {
		t.Fatal(err)
	}
	browser := exec.Command(chrome, "--headless=new", "--no-first-run", "--disable-gpu", "--ignore-certificate-errors", "--password-store=basic",
		"--user-data-dir="+profile, "--remote-debugging-port=0", "--user-agent="+gameAgent, "about:blank")
	stderr, err := browser.StderrPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := browser.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		browser.Process.Kill()
		browser.Wait()
		// The browser's helper processes write to the profile for a moment after it dies.
		for attempt := 0; os.RemoveAll(profile) != nil && attempt < 50; attempt++ {
			time.Sleep(100 * time.Millisecond)
		}
	})
	found := make(chan string, 1)
	go func() {
		lines := bufio.NewScanner(stderr)
		for lines.Scan() {
			if match := devtoolsLine.FindStringSubmatch(lines.Text()); match != nil {
				found <- match[1]
			}
		}
	}()
	var socket string
	select {
	case socket = <-found:
	case <-time.After(eventWait):
		t.Fatal("the browser gave no debugging address")
	}

	driver := exec.Command(node, harness, socket, pageURL)
	driver.Stderr = os.Stderr
	stdout, err := driver.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := driver.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		driver.Process.Kill()
		driver.Wait()
	})
	page := &browserPage{t: t}
	go func() {
		lines := bufio.NewScanner(stdout)
		lines.Buffer(nil, 64<<20)
		for lines.Scan() {
			var said struct {
				Screen *string `json:"screen"`
			}
			if err := json.Unmarshal(lines.Bytes(), &said); err != nil {
				t.Errorf("the driver said %q", lines.Text())
				return
			}
			var screen *ui.Screen
			if said.Screen != nil {
				frame := &panorama.Frame{}
				if err := frame.UnmarshalVT(unpack(*said.Screen)); err != nil {
					t.Errorf("a frame from the page did not decode: %v", err)
				}
				if screen = frame.GetScreen(); screen == nil {
					page.mu.Lock()
					if frame.GetDisconnect() != nil {
						page.disconnects = append(page.disconnects, len(page.shown))
					}
					page.mu.Unlock()
					continue
				}
			}
			page.mu.Lock()
			page.shown = append(page.shown, screen)
			page.lines = append(page.lines, lines.Text())
			page.mu.Unlock()
		}
	}()
	return page
}

func (p *browserPage) until(want ...string) {
	p.t.Helper()
	want = slices.Sorted(slices.Values(want))
	deadline := time.Now().Add(eventWait)
	for time.Now().Before(deadline) {
		p.mu.Lock()
		var last *ui.Screen
		if len(p.shown) > 0 {
			last = p.shown[len(p.shown)-1]
		}
		count := len(p.shown)
		p.mu.Unlock()
		if count > 0 && slices.Equal(texts(last), want) {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	p.t.Fatalf("the page never showed %q, saw %d screens", want, len(p.shown))
}

func (p *browserPage) neverShowed(marker string) {
	p.t.Helper()
	p.mu.Lock()
	defer p.mu.Unlock()
	for _, screen := range p.shown {
		for _, text := range texts(screen) {
			if strings.Contains(text, marker) {
				p.t.Errorf("the page showed %q, which belongs to another player", text)
			}
		}
	}
}

func TestThePageDrawsEachPlayersOwnScreenInARealBrowser(t *testing.T) {
	m := startMatch(t)
	m.page = sitePageTLS(t)

	a, b := m.join(accountA), m.join(accountB)
	pageA := openInBrowser(t, fmt.Sprintf("%s?port=%s", m.page, portOf(t, a.helper.screenListen)))
	pageB := openInBrowser(t, fmt.Sprintf("%s?port=%s", m.page, portOf(t, b.helper.screenListen)))

	pageA.until(markText(accountA))
	pageB.until(markText(accountB))

	a.game.exchange("press count 1")
	pageA.until(markText(accountA), stubMod+"/count=secret-1001 pressed 1")
	b.game.exchange("input name friend 1")
	pageB.until(markText(accountB), stubMod+"/name=secret-2002 typed friend")

	a.game.exchange("fill long 6000 2")
	pageA.until(markText(accountA), stubMod+"/count=secret-1001 pressed 1", stubMod+"/long=secret-1001 "+strings.Repeat("é", 6000))

	a.game.exchange("leave 3")
	pageA.until()

	pageA.neverShowed(markerOf(accountB))
	pageB.neverShowed(markerOf(accountA))

	a.game.connect()
	a.helper.stdin.Close()
	if got := a.helper.exit(); got != 0 {
		t.Fatalf("guest exit code = %d", got)
	}
	deadline := time.Now().Add(eventWait)
	for {
		pageA.mu.Lock()
		disconnects, shown := slices.Clone(pageA.disconnects), slices.Clone(pageA.shown)
		pageA.mu.Unlock()
		if len(disconnects) == 1 && len(shown) > disconnects[0] && shown[len(shown)-1] == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("the page passed on disconnects after screens %v, then %d screens in all", disconnects, len(shown))
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func sitePageTLS(t testing.TB) string {
	t.Helper()
	site := httptest.NewTLSServer(http.FileServer(http.Dir("testdata")))
	t.Cleanup(site.Close)
	return site.URL + "/bridge/"
}

func portOf(t testing.TB, address string) string {
	t.Helper()
	_, port, found := strings.Cut(address, ":")
	if !found {
		t.Fatalf("no port in %q", address)
	}
	return port
}
