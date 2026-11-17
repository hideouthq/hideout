package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"syscall"
	"testing"
	"time"
)

// processAlive reads /proc so a zombie nobody has reaped yet counts as dead.
func processAlive(pid int) bool {
	stat, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/stat")
	if err != nil {
		return false
	}
	return !bytes.Contains(stat[bytes.LastIndexByte(stat, ')'):], []byte(") Z"))
}

func waitDead(t *testing.T, pid int, within time.Duration, what string) {
	t.Helper()
	deadline := time.Now().Add(within)
	for time.Now().Before(deadline) {
		if !processAlive(pid) {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Errorf("%s (pid %d) alive after %v", what, pid, within)
}

func TestHardKillInterruptsTheChildSoItCleansUp(t *testing.T) {
	relay := localRelay(t)
	host := startHelperHost(t, relay, 1, "child-cleans-up-on-interrupt")
	child := serverPID(t, host.stream, "child")
	grandchild := serverPID(t, host.stream, "grandchild")
	t.Cleanup(func() {
		syscall.Kill(child, syscall.SIGKILL)
		syscall.Kill(grandchild, syscall.SIGKILL)
	})

	if err := host.cmd.Process.Signal(syscall.SIGKILL); err != nil {
		t.Fatal(err)
	}
	waitDead(t, child, 2*time.Second, "direct child")
	waitDead(t, grandchild, 2*time.Second, "grandchild")
}

func TestHardKillEndsAChildWithNoHandler(t *testing.T) {
	relay := localRelay(t)
	host := startHelperHost(t, relay, 1, "child-ignores-stdin", "child")
	child := serverPID(t, host.stream, "child")
	t.Cleanup(func() { syscall.Kill(child, syscall.SIGKILL) })

	if err := host.cmd.Process.Signal(syscall.SIGKILL); err != nil {
		t.Fatal(err)
	}
	waitDead(t, child, 2*time.Second, "direct child")
}

func TestStopKillsAGrandchildThroughTheGroup(t *testing.T) {
	relay := localRelay(t)
	host := startHelperHost(t, relay, 1, "child-with-grandchild")
	child := serverPID(t, host.stream, "child")
	grandchild := serverPID(t, host.stream, "grandchild")
	t.Cleanup(func() { syscall.Kill(grandchild, syscall.SIGKILL) })

	host.stdin.Close()
	if got := host.exit(); got != 0 {
		t.Errorf("exit = %d", got)
	}
	waitDead(t, child, time.Second, "direct child")
	waitDead(t, grandchild, time.Second, "grandchild")
}

func TestStopInterruptsAServerThatIgnoresStdinSoItCleansUp(t *testing.T) {
	relay := localRelay(t)
	marker := filepath.Join(t.TempDir(), "interrupted")
	host, _ := startHost(t, HostConfig{
		ServerPort: 1,
		ServerArgv: childArgv("child-marks-interrupt", marker),
		KillAfter:  time.Second,
		Region:     relay,
	})
	child := serverPID(t, host.stream, "child")
	t.Cleanup(func() { syscall.Kill(child, syscall.SIGKILL) })

	if st := host.end(t); st.Reason != ReasonStdinClosed {
		t.Errorf("stopped with %s", st.Reason)
	}
	if _, err := os.Stat(marker); err != nil {
		t.Errorf("the server was never interrupted: %v", err)
	}
}

func TestServerExitKillsWhatItLeftBehind(t *testing.T) {
	relay := localRelay(t)
	host := startHelperHost(t, relay, 1, "child-with-grandchild")
	child := serverPID(t, host.stream, "child")
	grandchild := serverPID(t, host.stream, "grandchild")
	t.Cleanup(func() { syscall.Kill(grandchild, syscall.SIGKILL) })

	syscall.Kill(child, syscall.SIGKILL)
	if got := host.exit(); got != 1 {
		t.Errorf("exit = %d", got)
	}
	all := host.stream.seen()
	if last := all[len(all)-1]; last.Reason != "server-exited" {
		t.Errorf("last line = %s", last.Raw)
	}
	waitDead(t, grandchild, time.Second, "grandchild")
}

func TestStopSignalExitsCleanly(t *testing.T) {
	relay := localRelay(t)
	host := startHelperHost(t, relay, 1, "child-with-grandchild")
	grandchild := serverPID(t, host.stream, "grandchild")
	t.Cleanup(func() { syscall.Kill(grandchild, syscall.SIGKILL) })

	host.cmd.Process.Signal(syscall.SIGTERM)
	if got := host.exit(); got != 0 {
		t.Errorf("exit = %d", got)
	}
	all := host.stream.seen()
	if last := all[len(all)-1]; last.Reason != "signal" {
		t.Errorf("last line = %s", last.Raw)
	}
	waitDead(t, grandchild, time.Second, "grandchild")
}

func TestStopWorksWithStdoutGone(t *testing.T) {
	relay := localRelay(t)
	region, err := json.Marshal(relay)
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(os.Args[0], "helper-host", string(region), "--server-port", "1", "--steam-id", hostAccount, "--page", noPage, "--offer", testOffer, "--", os.Args[0], "child-with-grandchild")
	stdin, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	s := newStream(t)
	sc := bufio.NewScanner(stdout)
	sc.Buffer(nil, 1<<20)
	go func() {
		for sc.Scan() {
			s.add(sc.Text())
		}
	}()
	grandchild := serverPID(t, s, "grandchild")
	t.Cleanup(func() { syscall.Kill(grandchild, syscall.SIGKILL) })

	stdout.Close()
	stdin.Close()
	if err := cmd.Wait(); err != nil {
		t.Errorf("helper did not stop cleanly: %v", err)
	}
	waitDead(t, grandchild, time.Second, "grandchild")
}
