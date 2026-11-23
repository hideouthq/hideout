// Command compile writes the in-game UI into tools/link/ingame/dist, where hideout-link embeds it.
//
// Run it from ingame/ with `go run ./compile`. Each entry under src/entries becomes one plain script, because Panorama
// loads scripts without modules, and the layouts and sheets under src/layout and src/styles are copied as they are.
package main

import (
	"fmt"
	"os"
	"path/filepath"
)

func main() {
	files, err := Build(".")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	if err := Write(files, filepath.Join("..", "tools", "link", "ingame", "dist")); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
