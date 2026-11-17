package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"sync"
	"time"
)

const (
	killedExitWait = 5 * time.Second
	stdoutDrain    = time.Second
	maxMessage     = 300
)

type serverProcess struct {
	cmd        *exec.Cmd
	stdin      io.Closer
	stdout     *os.File
	stdoutDone chan struct{}
	stderr     *stderrTail
	stderrDone chan struct{}
	exited     chan struct{}
}

func startServer(argv []string, emit Sink) (*serverProcess, error) {
	if err := prepareServerTree(); err != nil {
		fmt.Fprintln(os.Stderr, "process tree guard unavailable:", err)
	}
	cmd := exec.Command(argv[0], argv[1:]...)
	configureServer(cmd)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, childStdout, err := os.Pipe()
	if err != nil {
		return nil, err
	}
	defer childStdout.Close()
	cmd.Stdout = childStdout
	// A pipe of our own, because with any other writer Wait lasts until every
	// grandchild holding the stream has gone.
	stderr, childStderr, err := os.Pipe()
	if err != nil {
		stdout.Close()
		return nil, err
	}
	defer childStderr.Close()
	cmd.Stderr = childStderr

	p := &serverProcess{
		cmd:        cmd,
		stdin:      stdin,
		stdout:     stdout,
		stdoutDone: make(chan struct{}),
		stderr:     &stderrTail{through: os.Stderr},
		stderrDone: make(chan struct{}),
		exited:     make(chan struct{}),
	}
	started := make(chan error, 1)
	go func() {
		// PDEATHSIG fires when the thread that forked exits, not the process,
		// so that thread has to outlive the child.
		runtime.LockOSThread()
		err := cmd.Start()
		started <- err
		if err != nil {
			return
		}
		cmd.Wait()
		fmt.Fprintln(os.Stderr, "server:", cmd.ProcessState)
		close(p.exited)
	}()
	if err := <-started; err != nil {
		stdout.Close()
		stderr.Close()
		return nil, err
	}
	go p.readStdout(emit)
	go func() {
		defer close(p.stderrDone)
		defer stderr.Close()
		io.Copy(p.stderr, stderr)
	}()
	return p, nil
}

func (p *serverProcess) readStdout(emit Sink) {
	defer close(p.stdoutDone)
	r := bufio.NewReader(p.stdout)
	for {
		line, err := r.ReadBytes('\n')
		if len(line) > 0 {
			emit(serverLine(line))
		}
		if err != nil {
			return
		}
	}
}

func serverLine(line []byte) Event {
	line = bytes.TrimRight(line, "\r\n")
	if json.Valid(line) {
		return ServerEvent{Event: line}
	}
	return ServerLog{Line: string(line)}
}

// stop closes the server's stdin, interrupts it halfway through its grace, then
// kills what is left. Stdin EOF does not cancel Modlock during downloads or Proton
// setup, and an interrupt is the one signal it cleans up its Proton tree on. Windows
// has no interrupt, so there Signal does nothing and only the kill follows the EOF.
func (p *serverProcess) stop(grace time.Duration) {
	p.stdin.Close()
	waitAtMost(p.exited, grace/2)
	p.cmd.Process.Signal(os.Interrupt)
	waitAtMost(p.exited, grace/2)
	killServerTree(p.cmd)
	waitAtMost(p.exited, killedExitWait)
	waitAtMost(p.stdoutDone, stdoutDrain)
	p.stdout.Close()
	waitAtMost(p.stderrDone, stdoutDrain)
}

func waitAtMost(done <-chan struct{}, d time.Duration) {
	select {
	case <-done:
	case <-time.After(d):
	}
}

type stderrTail struct {
	through io.Writer

	mu      sync.Mutex
	current []byte
	last    string
}

func (t *stderrTail) Write(p []byte) (int, error) {
	t.through.Write(p)
	t.mu.Lock()
	defer t.mu.Unlock()
	for rest := p; len(rest) > 0; {
		line, tail, ended := bytes.Cut(rest, []byte("\n"))
		rest = tail
		if room := maxMessage - len(t.current); room > 0 {
			t.current = append(t.current, line[:min(room, len(line))]...)
		}
		if ended {
			t.finishLine()
		}
	}
	return len(p), nil
}

func (t *stderrTail) finishLine() {
	if line := strings.TrimSpace(strings.ToValidUTF8(string(t.current), "")); line != "" {
		t.last = line
	}
	t.current = t.current[:0]
}

func (t *stderrTail) lastLine() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.finishLine()
	return t.last
}
