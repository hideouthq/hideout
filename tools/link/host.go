package main

import (
	"cmp"
	"context"
	"fmt"
	"io"
	"maps"
	"net"
	"net/netip"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/wgengine/filter"
)

const (
	relayStartTimeout = 30 * time.Second
	goodbyeWait       = time.Second
)

type HostConfig struct {
	ServerPort uint16
	ServerArgv []string
	Mods       []string
	Stdin      io.Reader
	KillAfter  time.Duration
	DeadAfter  time.Duration

	Account      uint64
	ScreenListen string
	PageURL      string
	Share        string
	Offer        string

	Region     *tailcfg.DERPRegion
	DERPMapURL string
}

func runHost(ctx context.Context, cfg HostConfig, emit Sink) Stopped {
	ctx, stop := context.WithCancelCause(ctx)
	defer stop(nil)
	lines := make(chan string, commandBuffer)
	go func() {
		readCommands(cfg.Stdin, lines)
		stop(ReasonStdinClosed)
	}()
	stopped := hostSession(ctx, cfg, lines, emit)
	emit(stopped)
	return stopped
}

func hostSession(ctx context.Context, cfg HostConfig, lines <-chan string, emit Sink) Stopped {
	feed, err := newScreenFeed(cfg.Mods)
	if err != nil {
		return Stopped{Reason: ReasonServerStartFailed, Message: err.Error()}
	}
	inServer := func() bool {
		_, in, _ := feed.screens.Player(cfg.Account)
		return in
	}
	page, err := startPage(ctx, cfg.PageURL, cfg.ScreenListen, feed.source(cfg.Account), inServer)
	if err != nil {
		return Stopped{Reason: ReasonListenFailed, Message: err.Error()}
	}
	defer page.close()
	go followCommands(ctx, lines, page, cfg.ServerPort, emit)
	go watchOwnGame(ctx, feed, cfg.Account, emit)
	secret := newSecret()
	goodbye := &goodbyes{untilRead: map[net.Conn]chan struct{}{}}
	tunnel := newHostTunnel(cfg, deriveKeys(secret), feed, goodbye, emit)
	if err := startWithin(ctx, tunnel, cfg.DERPMapURL); err != nil {
		if ctx.Err() != nil {
			return stoppedBy(ctx)
		}
		fmt.Fprintln(os.Stderr, "relay:", err)
		return Stopped{Reason: ReasonRelayUnavailable}
	}
	console := newConsoleWatch(cfg.DeadAfter)
	server, err := startServer(cfg.ServerArgv, console.watching(ctx, modeLogs(feed.filter(emit), os.Stderr)))
	if err != nil {
		closeWithin(tunnel.Close)
		return Stopped{Reason: ReasonServerStartFailed, Message: err.Error()}
	}
	emit(Invite{Code: inviteCode(secret, tunnel.Region.RegionID), Region: Region{ID: int(tunnel.Region.RegionID), Code: tunnel.Region.RegionCode}})
	guestsCtx, stopGuests := context.WithCancel(ctx)
	go watchGuests(guestsCtx, tunnel.Status, emit)

	var stopped Stopped
	select {
	case <-ctx.Done():
		stopped = stoppedBy(ctx)
	case <-server.exited:
		stopped = Stopped{Reason: ReasonServerExited}
	case <-console.dead:
		stopped = Stopped{Reason: ReasonServerDead}
	}
	why := ReasonHostEnded
	if stopped.Reason == ReasonServerExited || stopped.Reason == ReasonServerDead {
		why = ReasonHostFailed
	}
	said := make(chan struct{})
	go func() {
		goodbye.say(why, goodbyeWait)
		close(said)
	}()
	page.close()
	stopGuests()
	<-said
	closeWithin(tunnel.Close)
	server.stop(cfg.KillAfter)
	if stopped.Reason == ReasonServerExited {
		stopped.Message = server.stderr.lastLine()
	}
	return stopped
}

func newHostTunnel(cfg HostConfig, keys tunnelKeys, feed *screenFeed, goodbye *goodbyes, emit Sink) *tailcat.Server {
	server := net.UDPAddrFromAddrPort(netip.AddrPortFrom(netip.AddrFrom4([4]byte{127, 0, 0, 1}), cfg.ServerPort))
	var flows atomic.Int64
	return &tailcat.Server{
		Key:            keys.node,
		PresharedKey:   keys.psk,
		Logf:           func(string, ...any) {},
		Region:         cfg.Region,
		ServedUDPPorts: []filter.PortRange{{First: gamePort, Last: gamePort}},
		OnUDP: func(port uint16) func(tailcat.ConnPacketConn) {
			if port != gamePort {
				return nil
			}
			return func(c tailcat.ConnPacketConn) { forwardFlow(c, server, flows.Add(1), emit) }
		},
		OnTCP: func(port uint16) func(net.Conn) {
			switch {
			case port == screenPort:
				return feed.serveGuest
			case port == sharePort && cfg.Share != "":
				return serveShare(cfg.Share)
			case port == offerPort:
				return func(conn net.Conn) { goodbye.serve(conn, cfg.Offer) }
			}
			return nil
		},
	}
}

type goodbyes struct {
	mu        sync.Mutex
	untilRead map[net.Conn]chan struct{}
	said      Reason
}

func (g *goodbyes) serve(conn net.Conn, offer string) {
	defer conn.Close()
	if _, err := io.WriteString(deadlineWriter{conn}, offer+"\n"); err != nil {
		return
	}
	guestClosed := make(chan struct{})
	defer close(guestClosed)
	g.mu.Lock()
	said := g.said
	if said == "" {
		g.untilRead[conn] = guestClosed
	}
	g.mu.Unlock()
	if said != "" {
		io.WriteString(deadlineWriter{conn}, string(said)+"\n")
		return
	}
	io.Copy(io.Discard, conn)
	g.mu.Lock()
	delete(g.untilRead, conn)
	g.mu.Unlock()
}

func (g *goodbyes) say(why Reason, within time.Duration) {
	g.mu.Lock()
	g.said = why
	open := maps.Clone(g.untilRead)
	g.mu.Unlock()
	deadline := time.Now().Add(within)
	for conn := range open {
		conn.SetDeadline(deadline)
		io.WriteString(conn, string(why)+"\n")
	}
	for _, guestClosed := range open {
		<-guestClosed
	}
}

func watchOwnGame(ctx context.Context, feed *screenFeed, account uint64, emit Sink) {
	in := false
	for {
		_, now, changed := feed.screens.Player(account)
		if now != in {
			in = now
			state := GameQuiet
			if in {
				state = GameFlowing
			}
			emit(Game{State: state})
		}
		select {
		case <-ctx.Done():
			return
		case <-changed:
		}
	}
}

func watchGuests(ctx context.Context, status func() *ipnstate.Status, emit Sink) {
	var current Guests
	for {
		select {
		case <-ctx.Done():
			return
		case <-time.After(pathPoll):
		}
		if guests := guestsOf(status()); guests != current {
			current = guests
			emit(guests)
		}
	}
}

// guestsOf counts only peers the tunnel sent to lately, since Tailcat keeps a peer that left.
func guestsOf(status *ipnstate.Status) Guests {
	var guests Guests
	for _, peer := range status.Peer {
		switch {
		case !peer.Active:
		case peer.CurAddr != "":
			guests.Direct++
		default:
			guests.Relay++
		}
	}
	return guests
}

func forwardFlow(tunnel tailcat.ConnPacketConn, server *net.UDPAddr, id int64, emit Sink) {
	upstream, err := net.DialUDP("udp4", nil, server)
	if err != nil {
		tunnel.Close()
		return
	}
	upstream.SetReadBuffer(udpReadBuffer)
	emit(FlowOpen{Flow: id})
	carry(tunnel, serverSocket{upstream})
}

// startWithin bounds all of Tailcat's startup, not only the part its context covers. With no
// region set, it picks one as Start would, since the code names it and the tunnel's own
// address does not keep region IDs.
func startWithin(ctx context.Context, tunnel *tailcat.Server, derpMapURL string) error {
	ctx, cancel := context.WithTimeout(ctx, relayStartTimeout)
	defer cancel()
	started := make(chan error, 1)
	go func() {
		if tunnel.Region == nil {
			nearest := tailcat.ConnInfo{RegionID: -1}
			if err := nearest.Expand(ctx, tailcat.ExpandForServer, tailcat.DERPMapURL(cmp.Or(derpMapURL, tailcat.DefaultDERPMapURL))); err != nil {
				started <- err
				return
			}
			tunnel.Region = nearest.Region[0]
		}
		started <- tunnel.StartContext(ctx)
	}()
	select {
	case err := <-started:
		return err
	case <-ctx.Done():
		return ctx.Err()
	}
}
