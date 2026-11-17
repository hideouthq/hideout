package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"strings"
	"testing"
	"time"

	"tailscale.com/envknob"
	"tailscale.com/net/netcheck"
	"tailscale.com/syncs"
	"tailscale.com/tailcfg"
)

func TestMain(m *testing.M) {
	envknob.Setenv("IN_TS_TEST", "true")
	netcheck.HookStartCaptivePortalDetection.SetForTest(func(context.Context, *netcheck.Client, *tailcfg.DERPMap, tailcfg.DERPRegionID, func(bool)) (<-chan struct{}, func()) {
		return syncs.ClosedChan(), func() {}
	})
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "child-exit":
			fmt.Println(`{"hello":"world"}`)
			fmt.Println("plain text")
			code, _ := strconv.Atoi(os.Args[2])
			os.Exit(code)
		case "child-says-why":
			fmt.Fprintln(os.Stderr, "starting")
			fmt.Fprintln(os.Stderr, os.Args[2])
			fmt.Fprintln(os.Stderr, "  ")
			os.Exit(1)
		case "child-long-line":
			fmt.Fprint(os.Stderr, strings.Repeat("x", 100_000))
			os.Exit(1)
		case "child-until-eof":
			fmt.Fprintln(os.Stderr, "up and running")
			printPID("child")
			io.Copy(io.Discard, os.Stdin)
			fmt.Println("stdin closed")
			os.Exit(0)
		case "child-ignores-stdin":
			printPID(os.Args[2])
			time.Sleep(time.Hour)
			os.Exit(0)
		case "child-ignores-stop":
			signal.Ignore(os.Interrupt)
			printPID("child")
			time.Sleep(time.Hour)
			os.Exit(0)
		case "child-marks-interrupt":
			interrupted := make(chan os.Signal, 1)
			signal.Notify(interrupted, os.Interrupt)
			printPID("child")
			<-interrupted
			if err := os.WriteFile(os.Args[2], nil, 0o644); err != nil {
				panic(err)
			}
			os.Exit(0)
		case "child-with-grandchild":
			printPID("child")
			grandchild := exec.Command(os.Args[0], "child-ignores-stdin", "grandchild")
			grandchild.Stdout = os.Stdout
			if err := grandchild.Start(); err != nil {
				panic(err)
			}
			if len(os.Args) > 2 && os.Args[2] == "ignore-stdin" {
				time.Sleep(time.Hour)
			}
			io.Copy(io.Discard, os.Stdin)
			os.Exit(0)
		case "child-cleans-up-on-interrupt":
			interrupted := make(chan os.Signal, 1)
			signal.Notify(interrupted, os.Interrupt)
			printPID("child")
			grandchild := exec.Command(os.Args[0], "child-ignores-stdin", "grandchild")
			grandchild.Stdout = os.Stdout
			if err := grandchild.Start(); err != nil {
				panic(err)
			}
			<-interrupted
			grandchild.Process.Kill()
			os.Exit(0)
		case "child-dumps-environment":
			fmt.Println(os.Args, os.Environ())
			fmt.Fprintln(os.Stderr, os.Args, os.Environ())
			io.Copy(io.Discard, os.Stdin)
			os.Exit(0)
		case "child-modlock":
			runStubModlock(os.Args[2])
			os.Exit(0)
		case "child-console":
			runStubConsole(os.Args[2], os.Args[3:])
			os.Exit(0)
		case "child-sandbox":
			runSandboxBridge(os.Args[2], os.Args[3:])
			os.Exit(0)
		case "helper-host":
			cfg, err := parseHostArgs(os.Args[3:], os.Stdin)
			if err != nil {
				panic(err)
			}
			if err := json.Unmarshal([]byte(os.Args[2]), &cfg.Region); err != nil {
				panic(err)
			}
			os.Exit(exitCode(runHost(signalContext(), cfg, newLineSink(os.Stdout))))
		case "helper-join":
			cfg, err := parseJoinArgs(nil, os.Stdin)
			if err != nil {
				os.Exit(usageExit)
			}
			cfg.GameListen, cfg.DERPMapURL = os.Args[2], os.Args[3]
			os.Exit(exitCode(runJoin(signalContext(), cfg, newLineSink(os.Stdout))))
		}
	}
	os.Exit(m.Run())
}

func printPID(role string) {
	fmt.Printf("{\"role\":%q,\"pid\":%d}\n", role, os.Getpid())
}
