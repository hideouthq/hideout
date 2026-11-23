package main

import (
	"io/fs"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"testing/fstest"

	modlock "github.com/paralin/modlock/session"
	"github.com/paralin/modlock/source2/vpk"
)

const (
	rendererFile  = "renderer.js"
	layoutLibFile = "layout.js"
)

type MenusConfig struct {
	Game       string
	Renderer   string
	PageURL    string
	ScreenPort uint16
	IngamePort uint16
	Out        string
	Ingame     fs.FS
	// RendererOnly leaves out the in-game UI and its copies of the game's layouts.
	RendererOnly bool
}

func runMenus(cfg MenusConfig, emit Sink) Stopped {
	token, err := writeMenus(cfg)
	if err != nil {
		os.Remove(cfg.Out)
		stopped := Stopped{Reason: ReasonFailed, Message: err.Error()}
		emit(stopped)
		return stopped
	}
	emit(Built{Token: token})
	stopped := Stopped{Reason: ReasonDone}
	emit(stopped)
	return stopped
}

// writeMenus draws the pack token itself and returns it for stdout, so no other process
// reads it from our argv.
func writeMenus(cfg MenusConfig) (string, error) {
	stock, err := vpk.OpenArchive(filepath.Join(cfg.Game, "game", "citadel", "pak01_dir.vpk"))
	if err != nil {
		return "", err
	}
	renderer, err := rendererFiles(cfg)
	if err != nil {
		return "", err
	}
	built, token := renderer, ""
	if !cfg.RendererOnly {
		page, err := bridgeURL(cfg.PageURL, cfg.IngamePort)
		if err != nil {
			return "", err
		}
		token = newPackToken()
		ingame, err := ingameFiles(stock, cfg.Ingame, IngameConfig{Port: cfg.IngamePort, Page: page, Token: token})
		if err != nil {
			return "", err
		}
		if built, err = merged(renderer, ingame); err != nil {
			return "", err
		}
	}
	file, err := os.Create(cfg.Out)
	if err != nil {
		return "", err
	}
	if err := vpk.Write(file, built); err != nil {
		file.Close()
		return "", err
	}
	return token, file.Close()
}

// rendererFiles shadows the stock news layout, read from this player's install so
// it follows game updates and no Valve text ships in our packages. The game loads
// that layout once, at start, inside the HUD, and keeps it for the whole process.
// Modlock's session builds the same files; ours differ only in the page the boot
// script names, which carries the port the helper serves the screen on.
func rendererFiles(cfg MenusConfig) (fstest.MapFS, error) {
	script, err := os.ReadFile(filepath.Join(cfg.Renderer, rendererFile))
	if err != nil {
		return nil, err
	}
	layoutLib, err := os.ReadFile(filepath.Join(cfg.Renderer, layoutLibFile))
	if err != nil {
		return nil, err
	}
	bridge, err := bridgeURL(cfg.PageURL, cfg.ScreenPort)
	if err != nil {
		return nil, err
	}
	return modlock.RendererFiles(filepath.Join(cfg.Game, "game", "citadel"), &modlock.Renderer{Script: script, Layout: layoutLib}, bridge)
}

func bridgeURL(page string, port uint16) (string, error) {
	address, err := url.Parse(page)
	if err != nil {
		return "", err
	}
	address.RawQuery = url.Values{"port": {strconv.Itoa(int(port))}}.Encode()
	return address.String(), nil
}
