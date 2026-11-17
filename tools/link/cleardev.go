package main

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"

	"github.com/paralin/modlock/devmount"
	"github.com/paralin/modlock/source2/gameinfo"
)

type ClearDevConfig struct {
	Game string
}

// runClearDev takes a Modlock development session's block out of the game's
// gameinfo.gi once no live session holds it. A session that ended without
// unmounting leaves its package mounted ahead of everything, and that package
// ships the same menus files ours does, so the game would load its menus and
// never ours. Modlock's own next session replaces such a block, and its
// ClearStale is how another program clears one: it holds the session lock
// while it edits, so a session starting meanwhile waits, and a live session's
// block stays. It says whether there was a block and whether it stayed.
func runClearDev(cfg ClearDevConfig, emit Sink) Stopped {
	stopped := Stopped{Reason: ReasonDone}
	found, live, err := clearDev(cfg.Game)
	if err != nil {
		stopped = Stopped{Reason: ReasonFailed, Message: err.Error()}
	} else if found {
		emit(DevSession{Live: live})
	}
	emit(stopped)
	return stopped
}

func clearDev(game string) (found, live bool, err error) {
	source, err := os.ReadFile(filepath.Join(game, "game", "citadel", "gameinfo.gi"))
	if err != nil {
		return false, false, err
	}
	if unmounted, err := gameinfo.Unmount(source, devmount.Name); err != nil || bytes.Equal(unmounted, source) {
		return false, false, err
	}
	err = devmount.ClearStale(game)
	if errors.Is(err, devmount.ErrRunning) {
		return true, true, nil
	}
	return true, false, err
}
