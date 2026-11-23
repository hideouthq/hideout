package main

import (
	"crypto/rand"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"strings"
	"testing/fstest"

	s2panorama "github.com/paralin/modlock/source2/panorama"
	"github.com/paralin/modlock/source2/vpk"
)

// ingameProtocol must equal the app's InGameProtocol.
const ingameProtocol = 1

//go:embed all:ingame/dist
var ingameDist embed.FS

func ingameSources() fs.FS {
	sources, err := fs.Sub(ingameDist, "ingame/dist")
	if err != nil {
		panic(err)
	}
	return sources
}

type shadow struct {
	layout  string
	scripts []string
}

var shadows = []shadow{
	{
		layout:  "panorama/layout/citadel_hud_and_db_overlay.xml",
		scripts: []string{"file://{resources}/scripts/hideout/config.js", "file://{resources}/scripts/hideout/boot.js"},
	},
	{
		layout:  "panorama/layout/popups/citadel_popup_custom_match.xml",
		scripts: []string{"file://{resources}/scripts/hideout/config.js", "file://{resources}/scripts/hideout/popup.js"},
	},
}

const (
	configScript = "panorama/scripts/hideout/config.js"
	// scriptsAnchor is where the includes go: every stock layout lists its styles before any scripts.
	scriptsAnchor = "</styles>"
)

type IngameConfig struct {
	Port  uint16 `json:"port"`
	Page  string `json:"page"`
	Token string `json:"token"`
}

// ingameFiles ships nothing raw, since a raw include crashes the game when its layout opens.
func ingameFiles(stock *vpk.Archive, layers []*vpk.Archive, sources fs.FS, cfg IngameConfig) (fstest.MapFS, error) {
	own := fstest.MapFS{}
	if err := fs.WalkDir(sources, ".", func(name string, entry fs.DirEntry, err error) error {
		if err != nil || !entry.Type().IsRegular() {
			return err
		}
		data, err := fs.ReadFile(sources, name)
		own[name] = &fstest.MapFile{Data: data, Mode: 0o644}
		return err
	}); err != nil {
		return nil, err
	}
	config, err := json.Marshal(struct {
		Protocol int `json:"protocol"`
		IngameConfig
	}{ingameProtocol, cfg})
	if err != nil {
		return nil, err
	}
	generated := fstest.MapFS{configScript: {Data: []byte("var HideoutConfig = " + string(config) + ";\n"), Mode: 0o644}}
	for _, s := range shadows {
		compiled, _ := s2panorama.Compiled(s.layout)
		data, err := firstHolding(compiled, layers, stock).ReadFile(compiled)
		if err != nil {
			return nil, fmt.Errorf("read the game's %s: %w", compiled, err)
		}
		layout, err := s2panorama.Decompile(data)
		if err != nil {
			return nil, fmt.Errorf("read the game's %s: %w", compiled, err)
		}
		if layout, err = withScripts(layout, s.scripts...); err != nil {
			return nil, fmt.Errorf("the game's %s: %w", compiled, err)
		}
		generated[s.layout] = &fstest.MapFile{Data: []byte(layout), Mode: 0o644}
	}
	tree, err := merged(own, generated)
	if err != nil {
		return nil, err
	}
	return s2panorama.CompileTree("hideout", withStockTextures{tree, stock})
}

func firstHolding(name string, layers []*vpk.Archive, stock *vpk.Archive) *vpk.Archive {
	for _, layer := range layers {
		if _, ok := layer.Size(name); ok {
			return layer
		}
	}
	return stock
}

func withScripts(layout string, srcs ...string) (string, error) {
	if n := strings.Count(layout, scriptsAnchor); n != 1 {
		return "", fmt.Errorf("want one %s to add Hideout's scripts after, found %d", scriptsAnchor, n)
	}
	var block strings.Builder
	block.WriteString(scriptsAnchor + "\n\t<scripts>\n")
	for _, src := range srcs {
		block.WriteString("\t\t<include src=\"" + src + "\" />\n")
	}
	block.WriteString("\t</scripts>")
	return strings.Replace(layout, scriptsAnchor, block.String(), 1), nil
}

// withStockTextures lets a sheet's url() of a stock texture read the player's copy, so the
// sheet records its real size where it would otherwise record 0x0 and draw nothing. Only
// the sources are listed, so CompileTree never puts a stock file in the pack.
type withStockTextures struct {
	sources fstest.MapFS
	stock   *vpk.Archive
}

func (t withStockTextures) Open(name string) (fs.File, error) {
	file, err := t.sources.Open(name)
	if !errors.Is(err, fs.ErrNotExist) || !strings.HasSuffix(name, ".vtex_c") {
		return file, err
	}
	if _, ok := t.stock.Size(name); !ok {
		return nil, err
	}
	data, err := t.stock.ReadFile(name)
	if err != nil {
		return nil, err
	}
	return fstest.MapFS{name: {Data: data}}.Open(name)
}

func newPackToken() string {
	token := make([]byte, 16)
	rand.Read(token)
	return hex.EncodeToString(token)
}

func merged(parts ...fstest.MapFS) (fstest.MapFS, error) {
	out := fstest.MapFS{}
	for _, part := range parts {
		for name, file := range part {
			if _, ok := out[name]; ok {
				return nil, fmt.Errorf("%s is in the menus pack twice", name)
			}
			out[name] = file
		}
	}
	return out, nil
}
