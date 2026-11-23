package main

import (
	"bytes"
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
	if !slices.Equal(names(got), names(want)) {
		t.Fatalf("dist holds %v, the sources build %v. Run `go run ./compile` in ingame/", names(got), names(want))
	}
	for name, data := range want {
		if !bytes.Equal(got[name], data) {
			t.Errorf("dist's %s is stale. Run `go run ./compile` in ingame/", name)
		}
	}
}
