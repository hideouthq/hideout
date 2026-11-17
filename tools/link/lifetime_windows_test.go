package main

import (
	"testing"
	"time"

	"golang.org/x/sys/windows"
)

func processAlive(pid int) bool {
	h, err := windows.OpenProcess(windows.SYNCHRONIZE, false, uint32(pid))
	if err != nil {
		return false
	}
	defer windows.CloseHandle(h)
	state, err := windows.WaitForSingleObject(h, 0)
	return err == nil && state == uint32(windows.WAIT_TIMEOUT)
}

func TestHardKillEndsTheWholeJob(t *testing.T) {
	relay := localRelay(t)
	host := startHelperHost(t, relay, 1, "child-with-grandchild", "ignore-stdin")
	child := serverPID(t, host.stream, "child")
	grandchild := serverPID(t, host.stream, "grandchild")

	if err := host.cmd.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && (processAlive(child) || processAlive(grandchild)) {
		time.Sleep(20 * time.Millisecond)
	}
	if processAlive(child) || processAlive(grandchild) {
		t.Errorf("job did not end the tree: child %v, grandchild %v", processAlive(child), processAlive(grandchild))
	}
}
