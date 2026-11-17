package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/paralin/modlock/devmount"
)

// Search paths as a player's game had them on 2026-11-16: a Modlock development
// session's block, left mounted by a session that ended days before, ahead of
// Hideout's custom games lines.
var (
	beforeDev = "\"GameInfo\"\r\n{\r\n\tFileSystem\r\n\t{\r\n\t\tSearchPaths\r\n\t\t{\r\n\t\t\tGame_LowViolence\tcitadel_lv\r\n\r\n"
	devBlock  = "\t\t\t// MODLOCK DEV BEGIN\r\n\t\t\tGame\t\t\tcitadel/modlock_dev/31bb65da30bd1ed1\r\n\t\t\tMod\t\t\t\"citadel\"\r\n\t\t\tWrite\t\t\t\"citadel\"\r\n\t\t\t// MODLOCK DEV END\r\n"
	afterDev  = "\t\t\t// Hideout start\r\n\t\t\tGame\t\t\t\tcitadel/hideout_games/menus\r\n\t\t\tGame\t\t\t\tcitadel/hideout_games/mode\r\n" +
		"\t\t\tGame\t\t\t\tcitadel/addons\r\n\t\t\tMod\t\t\t\tcitadel\r\n\t\t\tWrite\t\t\t\tcitadel\r\n\t\t\t// Hideout end\r\n" +
		"\t\t\tGame\t\t\t\tcitadel\r\n\t\t\tGame\t\t\t\tcore\r\n\t\t}\r\n\t}\r\n}\r\n"
)

// writeDevGame writes a game whose gameinfo.gi is info, with the session's
// package and its lock file as Modlock leaves them.
func writeDevGame(t testing.TB, info string) (game string, read func() string) {
	t.Helper()
	game = t.TempDir()
	file := filepath.Join(game, "game", "citadel", "gameinfo.gi")
	writeFiles(t, filepath.Join(game, "game", "citadel"), map[string]string{
		"gameinfo.gi":                                   info,
		"modlock_dev/session.lock":                      "",
		"modlock_dev/31bb65da30bd1ed1/pak01_dir.vpk":    "menus",
		"modlock_dev/31bb65da30bd1ed1/maps/pillars.vpk": "map",
	})
	return game, func() string {
		t.Helper()
		data, err := os.ReadFile(file)
		if err != nil {
			t.Fatal(err)
		}
		return string(data)
	}
}

func TestClearDevTakesOutTheBlockOfASessionThatEnded(t *testing.T) {
	game, read := writeDevGame(t, beforeDev+devBlock+afterDev)
	said, code := runVerb(t, "clear-dev", "--game", game)
	if code != 0 || len(said) != 2 || said[0].Raw != `{"type":"dev-session","live":false}` || said[1].Reason != "done" {
		t.Fatalf("exit %d, said %v", code, said)
	}
	if got := read(); got != beforeDev+afterDev {
		t.Errorf("gameinfo.gi is not the file without the block:\n%s", got)
	}
	if _, err := os.Stat(filepath.Join(game, "game", "citadel", "modlock_dev", "31bb65da30bd1ed1", "pak01_dir.vpk")); err != nil {
		t.Errorf("the session's package is Modlock's to prune: %v", err)
	}
}

func TestClearDevKeepsTheBlockOfALiveSession(t *testing.T) {
	game, read := writeDevGame(t, beforeDev+devBlock+afterDev)
	held, err := devmount.Hold(game)
	if err != nil {
		t.Fatal(err)
	}
	defer held.Close()
	said, code := runVerb(t, "clear-dev", "--game", game)
	if code != 0 || len(said) != 2 || said[0].Raw != `{"type":"dev-session","live":true}` || said[1].Reason != "done" {
		t.Fatalf("exit %d, said %v", code, said)
	}
	if got := read(); got != beforeDev+devBlock+afterDev {
		t.Errorf("a live session lost its block:\n%s", got)
	}
}

func TestClearDevLeavesAGameWithNoBlockAsItIs(t *testing.T) {
	game, read := writeDevGame(t, beforeDev+afterDev)
	said, code := runVerb(t, "clear-dev", "--game", game)
	if code != 0 || len(said) != 1 || said[0].Reason != "done" {
		t.Fatalf("exit %d, said %v", code, said)
	}
	if got := read(); got != beforeDev+afterDev {
		t.Errorf("gameinfo.gi changed:\n%s", got)
	}
}

func TestClearDevWithNoGameinfoFails(t *testing.T) {
	said, code := runVerb(t, "clear-dev", "--game", t.TempDir())
	if code != 1 || len(said) != 1 || said[0].Reason != "failed" || !strings.Contains(said[0].Raw, "gameinfo.gi") {
		t.Fatalf("exit %d, said %v", code, said)
	}
}
