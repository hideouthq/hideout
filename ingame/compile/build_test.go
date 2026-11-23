package main

import (
	"bytes"
	"maps"
	"path/filepath"
	"slices"
	"testing"
)

func TestDistMatchesSources(t *testing.T) {
	want, err := Build("..")
	if err != nil {
		t.Fatal(err)
	}
	got, err := Read(filepath.Join("..", "..", "tools", "link", "ingame", "dist"))
	if err != nil {
		t.Fatal(err)
	}
	if gotNames, wantNames := slices.Sorted(maps.Keys(got)), slices.Sorted(maps.Keys(want)); !slices.Equal(gotNames, wantNames) {
		t.Fatalf("dist holds %v, the sources build %v. Run `go run ./compile` in ingame/", gotNames, wantNames)
	}
	for name, data := range want {
		if !bytes.Equal(got[name], data) {
			t.Errorf("dist's %s is stale. Run `go run ./compile` in ingame/", name)
		}
	}
}
