package main

import (
	"errors"
	"io"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"
)

const (
	gamePort   uint16 = 27067
	screenPort uint16 = 27167
	sharePort  uint16 = 27267
	offerPort  uint16 = 27367

	closeTimeout = 5 * time.Second

	maxDatagram = 65535

	// Full game updates arrive as bursts larger than the default socket buffer.
	udpReadBuffer = 4 << 20
)

func carry(tunnel, local io.ReadWriteCloser) {
	closeBoth := sync.OnceFunc(func() {
		tunnel.Close()
		local.Close()
	})
	var wg sync.WaitGroup
	copyDatagrams := func(dst io.Writer, src io.Reader) {
		defer wg.Done()
		defer closeBoth()
		buf := make([]byte, maxDatagram)
		for {
			n, err := src.Read(buf)
			if err != nil {
				return
			}
			dst.Write(buf[:n])
		}
	}
	wg.Add(2)
	go copyDatagrams(local, tunnel)
	go copyDatagrams(tunnel, local)
	wg.Wait()
}

// transientUDPError counts every read error but a closed socket as passing. A send
// to a port nobody listens on yet surfaces as an error on the next read, on both
// Linux and Windows.
func transientUDPError(err error) bool {
	return err != nil && !errors.Is(err, net.ErrClosed)
}

type serverSocket struct{ *net.UDPConn }

func (s serverSocket) Read(b []byte) (int, error) {
	for {
		n, err := s.UDPConn.Read(b)
		if !transientUDPError(err) {
			return n, err
		}
	}
}

// gameSocket is the guest's local game endpoint. It answers whichever address
// last sent to it, since the game picks a new source port on every launch.
type gameSocket struct {
	conn *net.UDPConn
	game atomic.Pointer[netip.AddrPort]
}

func listenGame(address string) (*gameSocket, error) {
	addr, err := net.ResolveUDPAddr("udp4", address)
	if err != nil {
		return nil, err
	}
	conn, err := net.ListenUDP("udp4", addr)
	if err != nil {
		return nil, err
	}
	conn.SetReadBuffer(udpReadBuffer)
	return &gameSocket{conn: conn}, nil
}

func (s *gameSocket) Read(b []byte) (int, error) {
	for {
		n, from, err := s.conn.ReadFromUDPAddrPort(b)
		if transientUDPError(err) {
			continue
		}
		if err != nil {
			return 0, err
		}
		s.game.Store(&from)
		return n, nil
	}
}

func (s *gameSocket) Write(b []byte) (int, error) {
	game := s.game.Load()
	if game == nil {
		return len(b), nil
	}
	return s.conn.WriteToUDPAddrPort(b, *game)
}

func (s *gameSocket) client() netip.AddrPort {
	if game := s.game.Load(); game != nil {
		return *game
	}
	return netip.AddrPort{}
}

func (s *gameSocket) Close() error { return s.conn.Close() }

func closeWithin(closeFn func() error) {
	done := make(chan struct{})
	go func() {
		closeFn()
		close(done)
	}()
	waitAtMost(done, closeTimeout)
}
