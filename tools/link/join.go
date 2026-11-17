package main

import (
	"bufio"
	"cmp"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strings"
	"time"

	"github.com/tailscale/tailcat"
)

const (
	pathPoll    = 5 * time.Second
	pathTimeout = 3 * time.Second

	linkRetry  = 250 * time.Millisecond
	offerLimit = 4096
)

type JoinConfig struct {
	GameListen  string
	LinkTimeout time.Duration
	GameSilence time.Duration
	DERPMapURL  string
	Stdin       io.Reader
}

type Joining struct {
	ScreenListen string
	Account      uint64
	PageURL      string
	Fetch        *PackFetch
}

func runJoin(ctx context.Context, cfg JoinConfig, emit Sink) Stopped {
	ctx, stop := context.WithCancelCause(ctx)
	defer stop(nil)
	lines := make(chan string, commandBuffer)
	go func() {
		readCommands(cfg.Stdin, lines)
		stop(ReasonStdinClosed)
	}()
	stopped := joinSession(ctx, cfg, lines, emit)
	emit(stopped)
	return stopped
}

func joinSession(ctx context.Context, cfg JoinConfig, lines <-chan string, emit Sink) Stopped {
	linkCtx, cancel := context.WithTimeout(ctx, cfg.LinkTimeout)
	defer cancel()
	linkFailed := func(otherwise Reason) Stopped {
		switch {
		case ctx.Err() != nil:
			return stoppedBy(ctx)
		case linkCtx.Err() != nil:
			return Stopped{Reason: ReasonLinkTimeout}
		}
		return Stopped{Reason: otherwise}
	}

	var code string
	select {
	case line, open := <-lines:
		if !open {
			return Stopped{Reason: ReasonStdinClosed}
		}
		code = line
	case <-linkCtx.Done():
		return linkFailed(ReasonLinkTimeout)
	}
	secret, residue, ok := parseCode(code)
	if !ok {
		return Stopped{Reason: ReasonBadInvite}
	}
	relays, err := tailcat.FetchDERPMap(linkCtx, tailcat.DERPMapURL(cmp.Or(cfg.DERPMapURL, tailcat.DefaultDERPMapURL)))
	if err != nil {
		fmt.Fprintln(os.Stderr, "relay:", err)
		return linkFailed(ReasonRelayUnavailable)
	}
	client, offer, goodbye, err := linkHost(linkCtx, hostAddrs(deriveKeys(secret), residue, relays))
	switch {
	case errors.Is(err, errNotHere):
		return linkFailed(ReasonNotFound)
	case err != nil:
		return linkFailed(ReasonLinkTimeout)
	}
	defer closeWithin(client.Close)
	emit(Offer{Offer: offer})

	joining, stopped := awaitJoin(ctx, client, cfg.LinkTimeout, lines, goodbye)
	if stopped != nil {
		return *stopped
	}
	return play(ctx, cfg, joining, client, lines, goodbye, emit)
}

var errNotHere = errors.New("the relay has no such host")

// notHereLog is how magicsock reports the relay's peer-gone "not here" frame, which Tailcat
// passes to Logf and nowhere else. TestAWrongCodeIsNotFoundAtOnce fails if it is reworded.
const notHereLog = "does not know about peer"

func linkHost(ctx context.Context, addrs []tailcat.Addr) (*tailcat.Client, string, <-chan Stopped, error) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	type answer struct {
		client  *tailcat.Client
		offer   string
		goodbye <-chan Stopped
		err     error
	}
	answers := make(chan answer, len(addrs))
	for _, addr := range addrs {
		asking, notHere := context.WithCancelCause(ctx)
		client := &tailcat.Client{Server: addr, Logf: func(format string, _ ...any) {
			if strings.Contains(format, notHereLog) {
				notHere(errNotHere)
			}
		}}
		go func() {
			offer, goodbye, err := readOfferAndHoldIt(asking, client)
			if err != nil {
				err = context.Cause(asking)
			}
			answers <- answer{client, offer, goodbye, err}
		}()
	}
	err := errNotHere
	for left := len(addrs); left > 0; left-- {
		a := <-answers
		if a.err != nil {
			if a.err != errNotHere {
				err = a.err
			}
			go a.client.Close()
			continue
		}
		go func() {
			for range left - 1 {
				(<-answers).client.Close()
			}
		}()
		return a.client, a.offer, a.goodbye, nil
	}
	return nil, "", nil, err
}

func readOfferAndHoldIt(ctx context.Context, client *tailcat.Client) (string, <-chan Stopped, error) {
	for {
		if conn, err := client.DialTCPPort(ctx, offerPort); err == nil {
			deadline, _ := ctx.Deadline()
			conn.SetReadDeadline(deadline)
			host := bufio.NewReaderSize(conn, offerLimit)
			line, err := host.ReadSlice('\n')
			if err == nil {
				offer := string(line[:len(line)-1])
				conn.SetReadDeadline(time.Time{})
				goodbye := make(chan Stopped, 1)
				go hearGoodbye(conn, host, goodbye)
				return offer, goodbye, nil
			}
			conn.Close()
		}
		select {
		case <-ctx.Done():
			return "", nil, ctx.Err()
		case <-time.After(linkRetry):
		}
	}
}

func hearGoodbye(conn net.Conn, host *bufio.Reader, goodbye chan<- Stopped) {
	defer conn.Close()
	line, err := host.ReadSlice('\n')
	if why := Reason(strings.TrimSuffix(string(line), "\n")); err == nil && (why == ReasonHostEnded || why == ReasonHostFailed) {
		goodbye <- Stopped{Reason: why}
	}
}

func endedBy(ctx context.Context, goodbye <-chan Stopped, otherwise Stopped) Stopped {
	if ctx.Err() != nil {
		return stoppedBy(ctx)
	}
	select {
	case said := <-goodbye:
		return said
	default:
		return otherwise
	}
}

func awaitJoin(ctx context.Context, client *tailcat.Client, silence time.Duration, lines <-chan string, goodbye <-chan Stopped) (Joining, *Stopped) {
	waiting, stopWaiting := context.WithCancel(ctx)
	defer stopWaiting()
	hostGone := make(chan struct{})
	go func() {
		watchPath(waiting, client, silence, func(Event) {})
		close(hostGone)
	}()
	for {
		select {
		case said := <-goodbye:
			return Joining{}, &said
		case <-hostGone:
			stopped := endedBy(ctx, goodbye, Stopped{Reason: ReasonLinkLost})
			return Joining{}, &stopped
		case line, open := <-lines:
			if !open {
				return Joining{}, &Stopped{Reason: ReasonStdinClosed}
			}
			flags, isJoin := strings.CutPrefix(line, "join ")
			if !isJoin {
				fmt.Fprintf(os.Stderr, "command: %q before join, skipped\n", line)
				continue
			}
			joining, err := parseJoinLine(flags)
			if err != nil {
				return Joining{}, &Stopped{Reason: ReasonFailed, Message: err.Error()}
			}
			return joining, nil
		}
	}
}

func play(ctx context.Context, cfg JoinConfig, joining Joining, client *tailcat.Client, lines <-chan string, goodbye <-chan Stopped, emit Sink) Stopped {
	game, err := listenGame(cfg.GameListen)
	if err != nil {
		return Stopped{Reason: ReasonListenFailed, Message: err.Error()}
	}
	defer game.Close()
	remote, err := client.DialUDPPort(ctx, gamePort)
	if err != nil {
		return endedBy(ctx, goodbye, Stopped{Reason: ReasonLinkLost})
	}
	defer remote.Close()

	traffic := &gameTraffic{start: time.Now()}
	screen := newGuestScreen()
	page, err := startPage(ctx, joining.PageURL, joining.ScreenListen, screen.source, traffic.inServer)
	if err != nil {
		return Stopped{Reason: ReasonListenFailed, Message: err.Error()}
	}
	defer page.close()
	go followCommands(ctx, lines, page, uint16(game.conn.LocalAddr().(*net.UDPAddr).Port), emit)
	go screen.follow(ctx, func(ctx context.Context) (net.Conn, error) { return client.DialTCPPort(ctx, screenPort) }, joining.Account)
	emit(Linked{})

	go watchGame(ctx, traffic, game.client, cfg.GameSilence, emit)

	fetchFailed := make(chan Stopped, 1)
	if joining.Fetch != nil {
		go func() {
			conn, err := client.DialTCPPort(ctx, sharePort)
			if err != nil {
				fetchFailed <- Stopped{Reason: ReasonFetchFailed, Message: err.Error()}
			} else if stopped := fetchPack(ctx, conn, *joining.Fetch, emit); stopped != nil {
				fetchFailed <- *stopped
			}
		}()
	}

	lost := make(chan struct{}, 2)
	go func() {
		carry(heard{remote, traffic.start, &traffic.fromServer}, heard{game, traffic.start, &traffic.fromGame})
		lost <- struct{}{}
	}()
	go func() {
		watchPath(ctx, client, cfg.LinkTimeout, emit)
		lost <- struct{}{}
	}()
	select {
	case <-ctx.Done():
		return stoppedBy(ctx)
	case said := <-goodbye:
		return endedBy(ctx, nil, said)
	case stopped := <-fetchFailed:
		return endedBy(ctx, goodbye, stopped)
	case <-lost:
		return endedBy(ctx, goodbye, Stopped{Reason: ReasonLinkLost})
	}
}

func watchPath(ctx context.Context, client *tailcat.Client, silence time.Duration, emit Sink) {
	var current PathKind
	lastAnswer := time.Now()
	for {
		pingCtx, cancel := context.WithTimeout(ctx, pathTimeout)
		pong, err := client.DiscoPing(pingCtx)
		cancel()
		if err == nil {
			lastAnswer = time.Now()
			kind := PathRelay
			if pong.Endpoint != "" {
				kind = PathDirect
			}
			if kind != current {
				current = kind
				emit(Path{Kind: kind})
			}
		} else if time.Since(lastAnswer) > silence {
			return
		} else if current != "" && current != PathNone {
			current = PathNone
			emit(Path{Kind: PathNone})
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(pathPoll):
		}
	}
}
