package main

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/paralin/modlock/panorama"
	modlock "github.com/paralin/modlock/session"
	"github.com/paralin/modlock/src/wasm/ui"
)

const (
	pageCheckWait  = 10 * time.Second
	pageCheckRead  = 1 << 20
	pageMark       = "modlock:frame:"
	disconnectWait = time.Second
)

type pageServer struct {
	http     *http.Server
	listen   net.Listener
	relay    *modlock.Relay
	opened   *atomic.Bool
	failed   atomic.Bool
	inServer func() bool
	closing  sync.Once
}

func startPage(ctx context.Context, page, listen string, source modlock.ScreenSource, inServer func() bool) (*pageServer, error) {
	address, err := url.Parse(page)
	if err != nil {
		return nil, err
	}
	listener, err := net.Listen("tcp4", listen)
	if err != nil {
		return nil, err
	}
	opened := &atomic.Bool{}
	// Relay reads the source only for a request it admitted, so this marks the
	// game's page and not anything else that knocks on the port.
	watched := func() (*ui.Screen, <-chan struct{}) {
		opened.Store(true)
		return source()
	}
	relay := modlock.NewRelay(address.Scheme+"://"+address.Host, watched)
	server := &http.Server{Handler: relay}
	go server.Serve(listener)
	p := &pageServer{http: server, listen: listener, relay: relay, opened: opened, inServer: inServer}
	go func() {
		if err := checkPage(ctx, page); err != nil && ctx.Err() == nil {
			fmt.Fprintln(os.Stderr, "page:", err)
			p.failed.Store(true)
		}
	}()
	return p, nil
}

func (p *pageServer) addr() string { return p.listen.Addr().String() }

func (p *pageServer) reach() PageReach {
	if p.failed.Load() && !p.opened.Load() {
		return PageUnreachable
	}
	return ""
}

func (p *pageServer) close() {
	p.closing.Do(func() {
		if p.opened.Load() && p.inServer() {
			ctx, cancel := context.WithTimeout(context.Background(), disconnectWait)
			p.relay.Send(ctx, &panorama.Frame{Body: &panorama.Frame_Disconnect{Disconnect: &panorama.Disconnect{}}})
			cancel()
		}
		p.http.Close()
	})
}

// checkPage counts a redirect as no page, since the renderer's fragment
// acknowledgments would then reload it.
func checkPage(ctx context.Context, page string) error {
	ctx, cancel := context.WithTimeout(ctx, pageCheckWait)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, page, nil)
	if err != nil {
		return err
	}
	client := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	response, err := client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("the page answered %s", response.Status)
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, pageCheckRead))
	if err != nil {
		return err
	}
	if !strings.Contains(string(body), pageMark) {
		return fmt.Errorf("the page at %s is not the bridge page", page)
	}
	return nil
}
