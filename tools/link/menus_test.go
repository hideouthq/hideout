package main

import (
	"bytes"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"testing/fstest"

	s2panorama "github.com/paralin/modlock/source2/panorama"
	"github.com/paralin/modlock/source2/vpk"
)

var stockNews = fstest.MapFS{"panorama/layout/latest_news.xml": {Data: []byte("<root><Panel id=\"StockNews\" /></root>")}}

func writeStockGame(t testing.TB, layouts fstest.MapFS) string {
	t.Helper()
	game := t.TempDir()
	citadel := filepath.Join(game, "game", "citadel")
	if err := os.MkdirAll(citadel, 0o755); err != nil {
		t.Fatal(err)
	}
	compiled, err := s2panorama.CompileTree("stock", layouts)
	if err != nil {
		t.Fatal(err)
	}
	archive, err := os.Create(filepath.Join(citadel, "pak01_dir.vpk"))
	if err != nil {
		t.Fatal(err)
	}
	defer archive.Close()
	if err := vpk.Write(archive, compiled); err != nil {
		t.Fatal(err)
	}
	return game
}

func writeRenderer(t testing.TB) string {
	t.Helper()
	dir := t.TempDir()
	for _, name := range []string{rendererFile, layoutLibFile} {
		if err := os.WriteFile(filepath.Join(dir, name), []byte("// built "+name+"\n"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return dir
}

func runVerb(t testing.TB, args ...string) ([]line, int) {
	t.Helper()
	out := newStream(t)
	code := run(args, strings.NewReader(""), out)
	return out.seen(), code
}

func TestMenusNameThePageAndPortAndKeepTheStockNewsLayout(t *testing.T) {
	game := writeStockGame(t, stockLayouts(t))
	out := filepath.Join(t.TempDir(), "menus.part")
	page := "https://menus.example/bridge/"
	said, code := runVerb(t, "menus", "--game", game, "--renderer", writeRenderer(t), "--page", page, "--screen-port", "29700", "--out", out)
	if code != 0 || said[len(said)-1].Reason != "done" {
		t.Fatalf("exit %d, said %v", code, said)
	}
	pack, err := vpk.OpenArchive(out)
	if err != nil {
		t.Fatal(err)
	}
	read := func(name string) []byte {
		t.Helper()
		data, err := pack.ReadFile(name)
		if err != nil {
			t.Fatalf("%s is not in the pack: %v", name, err)
		}
		return data
	}
	if boot := read("panorama/scripts/modlock/boot.vjs_c"); !bytes.Contains(boot, []byte(page+"?port=29700")) {
		t.Errorf("the boot script does not name the page and port: %q", boot)
	}
	news, err := s2panorama.Decompile(read("panorama/layout/latest_news.vxml_c"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(news, "scripts/modlock/boot.vjs") || !strings.Contains(news, "StockNews") {
		t.Errorf("the news layout lost the boot script or the stock layout: %s", news)
	}
	for _, name := range []string{"panorama/layout/modlock/ui.vxml_c", "panorama/scripts/modlock/ui.vjs_c", "panorama/scripts/modlock/layout.vjs_c"} {
		read(name)
	}
}

func TestMenusThatCannotReadTheNewsLayoutLeaveNoPack(t *testing.T) {
	game := writeStockGame(t, fstest.MapFS{"panorama/layout/some_other_layout.xml": {Data: []byte("<root><Panel /></root>")}})
	out := filepath.Join(t.TempDir(), "menus.part")
	said, code := runVerb(t, "menus", "--game", game, "--renderer", writeRenderer(t), "--page", "https://menus.example/bridge/", "--screen-port", "29700", "--out", out)
	if code != 1 || len(said) != 1 || said[0].Reason != "failed" {
		t.Fatalf("exit %d, said %v", code, said)
	}
	if _, err := os.Stat(out); !errors.Is(err, fs.ErrNotExist) {
		t.Errorf("a failed build left %s: %v", out, err)
	}
}
