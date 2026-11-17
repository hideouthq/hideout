package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"os"
	"time"
)

const fetchReport = 250 * time.Millisecond

type PackFetch struct {
	File   string
	SHA256 string
	Bytes  int64
}

func serveShare(file string) func(net.Conn) {
	return func(conn net.Conn) {
		defer conn.Close()
		pack, err := os.Open(file)
		if err != nil {
			fmt.Fprintln(os.Stderr, "share:", err)
			return
		}
		defer pack.Close()
		io.Copy(deadlineWriter{conn}, pack)
	}
}

type deadlineWriter struct{ conn net.Conn }

func (w deadlineWriter) Write(b []byte) (int, error) {
	w.conn.SetWriteDeadline(time.Now().Add(sendWait))
	return w.conn.Write(b)
}

func fetchPack(ctx context.Context, conn net.Conn, want PackFetch, emit Sink) *Stopped {
	defer conn.Close()
	defer context.AfterFunc(ctx, func() { conn.Close() })()
	file, err := os.Create(want.File)
	if err != nil {
		return &Stopped{Reason: ReasonFetchFailed, Message: err.Error()}
	}
	defer file.Close()
	hash := sha256.New()
	progress := &fetchProgress{total: want.Bytes, emit: emit}
	done, err := io.Copy(io.MultiWriter(file, hash, progress), io.LimitReader(conn, want.Bytes))
	switch {
	case err != nil:
		return &Stopped{Reason: ReasonFetchFailed, Message: err.Error()}
	case done < want.Bytes:
		return &Stopped{Reason: ReasonFetchFailed, Message: fmt.Sprintf("the host's pack ended at %d of %d bytes", done, want.Bytes)}
	case hex.EncodeToString(hash.Sum(nil)) != want.SHA256:
		return &Stopped{Reason: ReasonFetchMismatch}
	}
	if err := file.Close(); err != nil {
		return &Stopped{Reason: ReasonFetchFailed, Message: err.Error()}
	}
	emit(Fetch{Done: done, Total: want.Bytes})
	emit(Fetched{})
	return nil
}

type fetchProgress struct {
	done, total int64
	last        time.Time
	emit        Sink
}

func (p *fetchProgress) Write(b []byte) (int, error) {
	p.done += int64(len(b))
	if time.Since(p.last) >= fetchReport {
		p.last = time.Now()
		p.emit(Fetch{Done: p.done, Total: p.total})
	}
	return len(b), nil
}
