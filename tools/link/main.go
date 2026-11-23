// Command hideout-link carries a custom game between a host and its guests.
// It speaks JSON lines on stdout (see protocol.go) and stops on stdin EOF.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/url"
	"os"
	"os/signal"
	"regexp"
	"strings"
	"syscall"
	"time"
)

const (
	usageExit = 2

	killAfter   = 10 * time.Second
	linkTimeout = 45 * time.Second
)

func main() {
	os.Exit(run(os.Args[1:], os.Stdin, os.Stdout))
}

func run(args []string, stdin io.Reader, stdout io.Writer) int {
	if len(args) == 0 {
		fmt.Fprintln(os.Stderr, "usage: hideout-link host|join|menus|stage|clear-dev|regions [flags]")
		return usageExit
	}
	emit := newLineSink(stdout)
	switch args[0] {
	case "host":
		cfg, err := parseHostArgs(args[1:], stdin)
		if err != nil {
			return usageExit
		}
		return exitCode(runHost(signalContext(), cfg, emit))
	case "join":
		cfg, err := parseJoinArgs(args[1:], stdin)
		if err != nil {
			return usageExit
		}
		return exitCode(runJoin(signalContext(), cfg, emit))
	case "menus":
		cfg, err := parseMenusArgs(args[1:])
		if err != nil {
			return usageExit
		}
		return exitCode(runMenus(cfg, emit))
	case "stage":
		cfg, err := parseStageArgs(args[1:])
		if err != nil {
			return usageExit
		}
		return exitCode(runStage(cfg, emit))
	case "clear-dev":
		cfg, err := parseClearDevArgs(args[1:])
		if err != nil {
			return usageExit
		}
		return exitCode(runClearDev(cfg, emit))
	case "regions":
		if len(args) != 1 {
			fmt.Fprintln(os.Stderr, "usage: hideout-link regions")
			return usageExit
		}
		return exitCode(runRegions(signalContext(), RegionsConfig{}, emit))
	default:
		fmt.Fprintf(os.Stderr, "unknown verb %q: want host, join, menus, stage, clear-dev or regions\n", args[0])
		return usageExit
	}
}

// signalContext cancels on a stop signal. It also makes a closed stdout a plain
// write error, because Go would otherwise die on SIGPIPE when the parent is gone
// and leave the server tree behind.
func signalContext() context.Context {
	signal.Ignore(syscall.SIGPIPE)
	ctx, stop := context.WithCancelCause(context.Background())
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-signals
		stop(ReasonSignal)
	}()
	return ctx
}

func parseHostArgs(args []string, stdin io.Reader) (HostConfig, error) {
	fs := flag.NewFlagSet("host", flag.ContinueOnError)
	serverPort := fs.Uint("server-port", 0, "the game server's local UDP port")
	account := fs.Uint64("steam-id", 0, "the host's own Steam ID, which picks the screen its game reads")
	screenListen := fs.String("screen-listen", "127.0.0.1:0", "the local TCP address the host's own game reads its screen from")
	page := fs.String("page", "", "the bridge page's address")
	share := fs.String("share", "", "a pack guests fetch over the tunnel")
	offer := fs.String("offer", "", "what a guest reads before it says yes, base64url")
	var mods repeated
	fs.Var(&mods, "mod", "a built mod the server runs; repeat for several")
	if err := fs.Parse(args); err != nil {
		return HostConfig{}, err
	}
	if *serverPort == 0 || *serverPort > 65535 || *account == 0 || fs.NArg() == 0 || !validPage(*page) || !offerShape.MatchString(*offer) || len(*offer) > 1024 {
		err := errors.New("usage: hideout-link host --server-port P --steam-id ID --page URL --offer TEXT [--screen-listen A] [--share FILE] [--mod DIR]... -- <server argv...>")
		fmt.Fprintln(os.Stderr, err)
		return HostConfig{}, err
	}
	return HostConfig{
		ServerPort:   uint16(*serverPort),
		ServerArgv:   fs.Args(),
		Mods:         mods,
		Account:      *account,
		ScreenListen: *screenListen,
		PageURL:      *page,
		Share:        *share,
		Offer:        *offer,
		Stdin:        stdin,
		KillAfter:    killAfter,
		DeadAfter:    serverDeadAfter,
	}, nil
}

func parseJoinArgs(args []string, stdin io.Reader) (JoinConfig, error) {
	if len(args) != 0 {
		err := errors.New("usage: hideout-link join, then the code and the join line on stdin")
		fmt.Fprintln(os.Stderr, err)
		return JoinConfig{}, err
	}
	return JoinConfig{
		GameListen:  "127.0.0.1:0",
		LinkTimeout: linkTimeout,
		GameSilence: gameSilence,
		Stdin:       stdin,
	}, nil
}

func parseJoinLine(line string) (Joining, error) {
	usage := errors.New(`usage: join ["--page", URL, ("--steam-id", ID,) ("--screen-listen", A,) ("--fetch", FILE, "--sha256", HEX, "--bytes", N)]`)
	var args []string
	if json.Unmarshal([]byte(line), &args) != nil {
		return Joining{}, usage
	}
	fs := flag.NewFlagSet("join", flag.ContinueOnError)
	screenListen := fs.String("screen-listen", "127.0.0.1:0", "the local TCP address the game reads its screen from")
	account := fs.Uint64("steam-id", 0, "this player's Steam ID, which picks their screen on the host")
	page := fs.String("page", "", "the bridge page's address")
	fetch := fs.String("fetch", "", "where to write the host's pack, once linked")
	sha := fs.String("sha256", "", "the pack's SHA-256, with --fetch")
	size := fs.Int64("bytes", 0, "the pack's size, with --fetch")
	if fs.Parse(args) != nil || fs.NArg() != 0 {
		return Joining{}, usage
	}
	var want *PackFetch
	if *fetch != "" {
		want = &PackFetch{File: *fetch, SHA256: *sha, Bytes: *size}
	}
	if !validPage(*page) || (want == nil) != (*sha == "" && *size == 0) || want != nil && (!shaShape.MatchString(*sha) || *size <= 0) {
		return Joining{}, usage
	}
	return Joining{ScreenListen: *screenListen, Account: *account, PageURL: *page, Fetch: want}, nil
}

func parseMenusArgs(args []string) (MenusConfig, error) {
	fs := flag.NewFlagSet("menus", flag.ContinueOnError)
	game := fs.String("game", "", "the Deadlock installation")
	renderer := fs.String("renderer", "", "the folder holding Modlock's built renderer")
	page := fs.String("page", "", "the bridge page's address")
	screenPort := fs.Uint("screen-port", 0, "the local TCP port the game reads its screen from")
	ingamePort := fs.Uint("ingame-port", 29701, "the local TCP port of Hideout's in-game endpoint")
	out := fs.String("out", "", "the file to write the pack to")
	if err := fs.Parse(args); err != nil {
		return MenusConfig{}, err
	}
	if *game == "" || *renderer == "" || !validPage(*page) || *screenPort == 0 || *screenPort > 65535 || *ingamePort == 0 || *ingamePort > 65535 || *out == "" {
		err := errors.New("usage: hideout-link menus --game DIR --renderer DIR --page URL --screen-port P [--ingame-port P] --out FILE")
		fmt.Fprintln(os.Stderr, err)
		return MenusConfig{}, err
	}
	return MenusConfig{Game: *game, Renderer: *renderer, PageURL: *page, ScreenPort: uint16(*screenPort), IngamePort: uint16(*ingamePort), Out: *out, Ingame: ingameSources()}, nil
}

func parseStageArgs(args []string) (StageConfig, error) {
	fs := flag.NewFlagSet("stage", flag.ContinueOnError)
	into := fs.String("into", "", "the folder that holds the current mode")
	var content repeated
	fs.Var(&content, "content", "a folder of mode content the game loads; repeat for several, or none to empty the folder")
	if err := fs.Parse(args); err != nil {
		return StageConfig{}, err
	}
	if *into == "" || fs.NArg() != 0 {
		err := errors.New("usage: hideout-link stage --into DIR [--content DIR]...")
		fmt.Fprintln(os.Stderr, err)
		return StageConfig{}, err
	}
	return StageConfig{Into: *into, Content: content, HeldRetry: heldRetry}, nil
}

func parseClearDevArgs(args []string) (ClearDevConfig, error) {
	fs := flag.NewFlagSet("clear-dev", flag.ContinueOnError)
	game := fs.String("game", "", "the Deadlock installation")
	if err := fs.Parse(args); err != nil {
		return ClearDevConfig{}, err
	}
	if *game == "" || fs.NArg() != 0 {
		err := errors.New("usage: hideout-link clear-dev --game DIR")
		fmt.Fprintln(os.Stderr, err)
		return ClearDevConfig{}, err
	}
	return ClearDevConfig{Game: *game}, nil
}

var (
	shaShape   = regexp.MustCompile("^[0-9a-f]{64}$")
	offerShape = regexp.MustCompile("^[A-Za-z0-9_-]+$")
)

func validPage(page string) bool {
	address, err := url.Parse(page)
	return err == nil && address.Host != "" && (address.Scheme == "https" || address.Scheme == "http")
}

type repeated []string

func (r *repeated) String() string { return strings.Join(*r, ",") }

func (r *repeated) Set(value string) error {
	*r = append(*r, value)
	return nil
}
