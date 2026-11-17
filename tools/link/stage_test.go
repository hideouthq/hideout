package main

import (
	"io/fs"
	"maps"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"testing"
)

func writeFiles(t testing.TB, dir string, files map[string]string) {
	t.Helper()
	for name, text := range files {
		file := filepath.Join(dir, filepath.FromSlash(name))
		if err := os.MkdirAll(filepath.Dir(file), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(file, []byte(text), 0o644); err != nil {
			t.Fatal(err)
		}
	}
}

func listed(t testing.TB, dir string) map[string]string {
	t.Helper()
	found := map[string]string{}
	err := filepath.WalkDir(dir, func(file string, entry fs.DirEntry, err error) error {
		if err != nil || file == dir {
			return err
		}
		name, _ := filepath.Rel(dir, file)
		name = filepath.ToSlash(name)
		if entry.IsDir() {
			found[name+"/"] = ""
			return nil
		}
		data, err := os.ReadFile(file)
		found[name] = string(data)
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	return found
}

func TestAStageHoldsExactlyTheModesFilesWithTheLaterFolderWinning(t *testing.T) {
	first, second, into := t.TempDir(), t.TempDir(), t.TempDir()
	writeFiles(t, first, map[string]string{
		"models/arena/shared.vmdl_c":     "first",
		"materials/arena/only.vmat_c":    "only first",
		"panorama/layout/arena/a.vxml_c": "layout",
	})
	writeFiles(t, second, map[string]string{
		"models/arena/shared.vmdl_c": "second",
		"mod.json":                   "the server's",
		"scripts/not_content.txt":    "stays out",
		"maps/nested/not_a_map.vpk":  "stays out",
		"maps/arena.vpk":             "map",
	})
	writeFiles(t, into, map[string]string{
		"models/before/pawn.vmdl_c":               "the last mode's",
		"maps/before.vpk":                         "the last mode's map",
		"models/arena/shared.vmdl_c.hideout-part": "half a copy",
	})

	said, code := runVerb(t, "stage", "--into", into, "--content", first, "--content", second)
	if code != 0 || len(said) != 2 || said[0].Raw != `{"type":"staged","held":[]}` || said[1].Reason != "done" {
		t.Fatalf("exit %d, said %v", code, said)
	}
	want := map[string]string{
		"maps/": "", "maps/arena.vpk": "map",
		"materials/": "", "materials/arena/": "", "materials/arena/only.vmat_c": "only first",
		"models/": "", "models/arena/": "", "models/arena/shared.vmdl_c": "second",
		"panorama/": "", "panorama/layout/": "", "panorama/layout/arena/": "", "panorama/layout/arena/a.vxml_c": "layout",
	}
	if got := listed(t, into); !maps.Equal(got, want) {
		t.Errorf("the folder holds %v, want %v", got, want)
	}
}

func TestAStageLeavesACurrentFileInPlace(t *testing.T) {
	content, into := t.TempDir(), t.TempDir()
	writeFiles(t, content, map[string]string{"maps/arena.vpk": "map", "models/arena/a.vmdl_c": "a"})
	if _, err := stage(StageConfig{Into: into, Content: []string{content}}); err != nil {
		t.Fatal(err)
	}
	before, err := os.Stat(filepath.Join(into, "maps", "arena.vpk"))
	if err != nil {
		t.Fatal(err)
	}
	writeFiles(t, content, map[string]string{"models/arena/a.vmdl_c": "a, updated"})
	if _, err := stage(StageConfig{Into: into, Content: []string{content}}); err != nil {
		t.Fatal(err)
	}
	after, err := os.Stat(filepath.Join(into, "maps", "arena.vpk"))
	if err != nil {
		t.Fatal(err)
	}
	if !os.SameFile(before, after) {
		t.Error("a current file was copied again")
	}
	if got := listed(t, into)["models/arena/a.vmdl_c"]; got != "a, updated" {
		t.Errorf("an updated file reads %q", got)
	}
}

func TestAStageWithNoContentEmptiesTheFolder(t *testing.T) {
	content, into := t.TempDir(), t.TempDir()
	writeFiles(t, content, map[string]string{"maps/arena.vpk": "map", "models/arena/a.vmdl_c": "a"})
	if _, code := runVerb(t, "stage", "--into", into, "--content", content); code != 0 {
		t.Fatalf("exit %d", code)
	}
	if _, code := runVerb(t, "stage", "--into", into); code != 0 {
		t.Fatalf("exit %d", code)
	}
	if got := listed(t, into); len(got) != 0 {
		t.Errorf("an empty stage left %v", got)
	}
}

func TestAFileThatWillNotGoIsNamedAndTheStageGoesOn(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("a read-only folder still lets Windows remove its files")
	}
	content, into := t.TempDir(), t.TempDir()
	writeFiles(t, content, map[string]string{"models/arena/a.vmdl_c": "a"})
	writeFiles(t, into, map[string]string{"maps/before.vpk": "the last mode's map"})
	locked := filepath.Join(into, "maps")
	if err := os.Chmod(locked, 0o555); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.Chmod(locked, 0o755) })

	held, err := stage(StageConfig{Into: into, Content: []string{content}})
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(held, []string{"maps/before.vpk"}) {
		t.Errorf("held = %v", held)
	}
	if got := listed(t, into)["models/arena/a.vmdl_c"]; got != "a" {
		t.Errorf("the mode's file reads %q", got)
	}
}

func TestAStageThatCannotMakeItsFolderFails(t *testing.T) {
	into := filepath.Join(t.TempDir(), "mode")
	if err := os.WriteFile(into, nil, 0o644); err != nil {
		t.Fatal(err)
	}
	said, code := runVerb(t, "stage", "--into", into)
	if code != 1 || len(said) != 1 || said[0].Reason != "failed" {
		t.Fatalf("exit %d, said %v", code, said)
	}
}

func sparseFile(t *testing.T, name string, size int64) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(name), 0o755); err != nil {
		t.Fatal(err)
	}
	file, err := os.Create(name)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	if err := file.Truncate(size); err != nil {
		t.Fatal(err)
	}
}

func TestAStageDoesNotReadAModesFilesIntoMemory(t *testing.T) {
	const size = 128 << 20
	content, into := t.TempDir(), t.TempDir()
	sparseFile(t, filepath.Join(content, "maps", "arena.vpk"), size)
	sparseFile(t, filepath.Join(content, "models", "big.vmdl_c"), size)

	var before, after runtime.MemStats
	runtime.ReadMemStats(&before)
	_, err := stage(StageConfig{Into: into, Content: []string{content}})
	runtime.ReadMemStats(&after)
	if err != nil {
		t.Fatal(err)
	}
	if allocated := after.TotalAlloc - before.TotalAlloc; allocated > size/4 {
		t.Errorf("allocated %d MiB to stage %d MiB of files", allocated>>20, 2*size>>20)
	}
	if info, err := os.Stat(filepath.Join(into, "maps", "arena.vpk")); err != nil || info.Size() != size {
		t.Errorf("the map came through as %v, %v", info, err)
	}
}
