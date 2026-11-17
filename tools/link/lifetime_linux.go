package main

import (
	"os/exec"
	"syscall"
)

func prepareServerTree() error { return nil }

// configureServer interrupts the server if the helper dies, because Modlock
// only stops its session on SIGINT: SIGTERM or SIGKILL would end it with its
// Proton tree still running.
func configureServer(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{Pdeathsig: syscall.SIGINT, Setpgid: true}
}

func killServerTree(cmd *exec.Cmd) {
	syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
}
