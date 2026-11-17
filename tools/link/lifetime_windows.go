package main

import (
	"os/exec"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

// prepareServerTree puts the helper in a kill-on-close job before it spawns
// anything, so children inherit the job with no race. The handle stays open for
// the life of the process, so any exit of the helper, even a hard kill, ends the
// whole tree.
func prepareServerTree() error {
	job, err := windows.CreateJobObject(nil, nil)
	if err != nil {
		return err
	}
	var info windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION
	info.BasicLimitInformation.LimitFlags = windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
	if _, err := windows.SetInformationJobObject(job, windows.JobObjectExtendedLimitInformation,
		uintptr(unsafe.Pointer(&info)), uint32(unsafe.Sizeof(info))); err != nil {
		return err
	}
	return windows.AssignProcessToJobObject(job, windows.CurrentProcess())
}

func configureServer(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{CreationFlags: windows.CREATE_NO_WINDOW}
}

// killServerTree kills the direct child only. Terminating the job would take the
// helper with it, so anything else in the tree dies when the helper exits.
func killServerTree(cmd *exec.Cmd) {
	cmd.Process.Kill()
}
