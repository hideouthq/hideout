package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io/fs"
	"maps"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"
	"testing/fstest"

	s2panorama "github.com/paralin/modlock/source2/panorama"
	"github.com/paralin/modlock/source2/resource"
	"github.com/paralin/modlock/source2/vpk"
)

const (
	overlayLayout = "panorama/layout/citadel_hud_and_db_overlay.vxml_c"
	popupLayout   = "panorama/layout/popups/citadel_popup_custom_match.vxml_c"
)

// stockLayouts is the news layout and the decompiled stock layouts under testdata/stock, as
// sources a stub install compiles.
func stockLayouts(t testing.TB) fstest.MapFS {
	t.Helper()
	layouts := maps.Clone(stockNews)
	stock := os.DirFS("testdata/stock")
	if err := fs.WalkDir(stock, ".", func(name string, entry fs.DirEntry, err error) error {
		if err != nil || entry.IsDir() {
			return err
		}
		data, err := fs.ReadFile(stock, name)
		layouts[name] = &fstest.MapFile{Data: data}
		return err
	}); err != nil {
		t.Fatal(err)
	}
	return layouts
}

func stockTexture(t testing.TB, width, height uint16) *fstest.MapFile {
	t.Helper()
	header := make([]byte, 28)
	binary.LittleEndian.PutUint16(header, 1)
	binary.LittleEndian.PutUint16(header[20:], width)
	binary.LittleEndian.PutUint16(header[22:], height)
	data, err := resource.Write(1, []resource.Block{{Tag: "DATA", Data: header}})
	if err != nil {
		t.Fatal(err)
	}
	return &fstest.MapFile{Data: data}
}

func buildMenus(t testing.TB, game string, ingame fs.FS) ([]line, string) {
	t.Helper()
	out := filepath.Join(t.TempDir(), "menus.part")
	said := newStream(t)
	runMenus(MenusConfig{Game: game, Renderer: writeRenderer(t), PageURL: "https://menus.example/bridge/", ScreenPort: 29700, IngamePort: 29701, Out: out, Ingame: ingame}, newLineSink(said))
	return said.seen(), out
}

func readPack(t testing.TB, out string) map[string][]byte {
	t.Helper()
	pack, err := vpk.OpenArchive(out)
	if err != nil {
		t.Fatal(err)
	}
	files := map[string][]byte{}
	for _, name := range pack.Names() {
		if files[name], err = pack.ReadFile(name); err != nil {
			t.Fatal(err)
		}
	}
	return files
}

func decompiled(t testing.TB, files map[string][]byte, name string) string {
	t.Helper()
	layout, err := s2panorama.Decompile(files[name])
	if err != nil {
		t.Fatalf("%s: %v", name, err)
	}
	return layout
}

func TestMenusShipOnlyCompiledPanoramaAndSayTheTokenTheyHold(t *testing.T) {
	game := writeStockGame(t, stockLayouts(t))
	out := filepath.Join(t.TempDir(), "menus.part")
	said, code := runVerb(t, "menus", "--game", game, "--renderer", writeRenderer(t), "--page", "https://menus.example/bridge/", "--screen-port", "29700", "--out", out)
	if code != 0 || len(said) != 2 || said[0].Type != "built" || said[1].Reason != "done" {
		t.Fatalf("exit %d, said %v", code, said)
	}
	var built struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal([]byte(said[0].Raw), &built); err != nil || !regexp.MustCompile("^[0-9a-f]{32}$").MatchString(built.Token) {
		t.Fatalf("built names no 32 hex token: %s", said[0].Raw)
	}
	files := readPack(t, out)
	for name := range files {
		if strings.HasPrefix(name, "panorama/") && !strings.HasSuffix(name, "_c") {
			t.Errorf("%s ships uncompiled, and the game crashes when a layout includes it", name)
		}
	}
	config := files["panorama/scripts/hideout/config.vjs_c"]
	for _, want := range []string{built.Token, `"port":29701`, `"page":"https://menus.example/bridge/?port=29701"`, `"protocol":1`} {
		if !bytes.Contains(config, []byte(want)) {
			t.Errorf("config.js lacks %s: %q", want, config)
		}
	}
	for _, name := range []string{"panorama/scripts/hideout/boot.vjs_c", "panorama/scripts/hideout/popup.vjs_c", "panorama/layout/hideout/band.vxml_c", "panorama/scripts/modlock/boot.vjs_c"} {
		if files[name] == nil {
			t.Errorf("%s is not in the pack", name)
		}
	}
}

func TestShadowsAddHideoutsScriptsToTheInstalledLayouts(t *testing.T) {
	said, out := buildMenus(t, writeStockGame(t, stockLayouts(t)), ingameSources())
	if said[len(said)-1].Reason != "done" {
		t.Fatalf("said %v", said)
	}
	files := readPack(t, out)
	for name, want := range map[string][]string{
		overlayLayout: {"scripts/hideout/config.vjs", "scripts/hideout/boot.vjs", `<ToastManager id="ToastManager"`},
		popupLayout:   {"scripts/hideout/config.vjs", "scripts/hideout/popup.vjs", `<TextEntry id="PartyCodeEntry"`},
	} {
		layout := decompiled(t, files, name)
		for _, part := range want {
			if strings.Count(layout, part) != 1 {
				t.Errorf("%s holds %q %d times, want once:\n%s", name, part, strings.Count(layout, part), layout)
			}
		}
	}
}

func TestMenusFailWhenAStockLayoutIsGoneOrChangedShape(t *testing.T) {
	for _, tt := range []struct {
		name  string
		popup *fstest.MapFile
	}{
		{"gone", nil},
		{"no styles", &fstest.MapFile{Data: []byte(`<root><PopupCustomMatch class="PopupPanel" /></root>`)}},
	} {
		t.Run(tt.name, func(t *testing.T) {
			layouts := stockLayouts(t)
			delete(layouts, "panorama/layout/popups/citadel_popup_custom_match.xml")
			if tt.popup != nil {
				layouts["panorama/layout/popups/citadel_popup_custom_match.xml"] = tt.popup
			}
			said, out := buildMenus(t, writeStockGame(t, layouts), ingameSources())
			if len(said) != 1 || said[0].Reason != "failed" || !strings.Contains(said[0].Raw, "citadel_popup_custom_match") {
				t.Fatalf("said %v", said)
			}
			if _, err := os.Stat(out); !errors.Is(err, fs.ErrNotExist) {
				t.Errorf("a failed build left %s: %v", out, err)
			}
		})
	}
}

func TestMenusRefuseAFileTwoPartsBothWrite(t *testing.T) {
	for _, name := range []string{
		"panorama/scripts/hideout/config.js",
		"panorama/layout/popups/citadel_popup_custom_match.xml",
		"panorama/layout/latest_news.xml",
	} {
		t.Run(name, func(t *testing.T) {
			ingame := fstest.MapFS{name: {Data: []byte("<root><Panel /></root>")}}
			said, out := buildMenus(t, writeStockGame(t, stockLayouts(t)), ingame)
			if len(said) != 1 || said[0].Reason != "failed" || !strings.Contains(said[0].Raw, "twice") {
				t.Fatalf("said %v", said)
			}
			if _, err := os.Stat(out); !errors.Is(err, fs.ErrNotExist) {
				t.Errorf("a failed build left %s: %v", out, err)
			}
		})
	}
}

func TestASheetRecordsTheSizeOfTheGamesOwnTexture(t *testing.T) {
	layouts := stockLayouts(t)
	layouts["panorama/images/textures/grain_psd.vtex_c"] = stockTexture(t, 640, 360)
	ingame := fstest.MapFS{"panorama/styles/hideout/grain.css": {Data: []byte(`.Grain { background-image: url("s2r://panorama/images/textures/grain_psd.vtex"); }`)}}
	said, out := buildMenus(t, writeStockGame(t, layouts), ingame)
	if said[len(said)-1].Reason != "done" {
		t.Fatalf("said %v", said)
	}
	files := readPack(t, out)
	if files["panorama/images/textures/grain_psd.vtex_c"] != nil {
		t.Error("the game's own texture was copied into the pack")
	}
	sizes := textureSizes(t, files["panorama/styles/hideout/grain.vcss_c"])
	if got := sizes["panorama/images/textures/grain_psd.vtex"]; got != [2]uint16{640, 360} {
		t.Errorf("the sheet records the stock texture at %dx%d, want 640x360", got[0], got[1])
	}
}

// textureSizes reads a compiled sheet's table of the resources it references with their sizes.
func textureSizes(t testing.TB, sheet []byte) map[string][2]uint16 {
	t.Helper()
	data, err := resource.ReadBlock(sheet, "DATA")
	if err != nil {
		t.Fatal(err)
	}
	sizes := map[string][2]uint16{}
	count, rest := binary.LittleEndian.Uint16(data[4:]), data[6:]
	for range count {
		name, after, _ := bytes.Cut(rest, []byte{0})
		sizes[string(name)] = [2]uint16{binary.LittleEndian.Uint16(after), binary.LittleEndian.Uint16(after[2:])}
		rest = after[8:]
	}
	return sizes
}

func TestTheInGameProtocolIsTheApps(t *testing.T) {
	app, err := os.ReadFile("../../app/shared/src/gg/hideouthq/feature/ingame/InGameWire.kt")
	if err != nil {
		t.Fatal(err)
	}
	found := regexp.MustCompile(`const val InGameProtocol = (\d+)`).FindSubmatch(app)
	if found == nil || string(found[1]) != strconv.Itoa(ingameProtocol) {
		t.Errorf("the app's InGameProtocol is %q and the pack's is %d", found, ingameProtocol)
	}
}
