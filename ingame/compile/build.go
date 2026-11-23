package main

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"

	"github.com/evanw/esbuild/pkg/api"
)

var entries = []string{"boot", "popup", "games", "escmenu"}

func Build(dir string) (map[string][]byte, error) {
	files := map[string][]byte{}
	abs, err := filepath.Abs(dir)
	if err != nil {
		return nil, err
	}
	for _, entry := range entries {
		result := api.Build(api.BuildOptions{
			AbsWorkingDir: abs,
			EntryPoints:   []string{"src/entries/" + entry + ".ts"},
			Bundle:        true,
			Format:        api.FormatIIFE,
			Target:        api.ES2020,
			Charset:       api.CharsetASCII,
			LegalComments: api.LegalCommentsNone,
			LogLevel:      api.LogLevelSilent,
		})
		if len(result.Errors) > 0 {
			messages := api.FormatMessages(result.Errors, api.FormatMessagesOptions{Kind: api.ErrorMessage})
			return nil, fmt.Errorf("bundle %s:\n%s", entry, strings.Join(messages, ""))
		}
		files["panorama/scripts/hideout/"+entry+".js"] = result.OutputFiles[0].Contents
	}
	for _, kind := range []string{"layout", "styles"} {
		root := filepath.Join(dir, "src", kind)
		if err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
			if err != nil || d.IsDir() {
				return err
			}
			rel, err := filepath.Rel(root, path)
			if err != nil {
				return err
			}
			data, err := os.ReadFile(path)
			files["panorama/"+kind+"/"+filepath.ToSlash(rel)] = data
			return err
		}); err != nil {
			return nil, err
		}
	}
	return files, nil
}

func Write(files map[string][]byte, out string) error {
	if err := os.RemoveAll(filepath.Join(out, "panorama")); err != nil {
		return err
	}
	for name, data := range files {
		path := filepath.Join(out, filepath.FromSlash(name))
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			return err
		}
		if err := os.WriteFile(path, data, 0o644); err != nil {
			return err
		}
	}
	return nil
}

func Read(out string) (map[string][]byte, error) {
	files := map[string][]byte{}
	err := filepath.WalkDir(filepath.Join(out, "panorama"), func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		rel, err := filepath.Rel(out, path)
		if err != nil {
			return err
		}
		data, err := os.ReadFile(path)
		files[filepath.ToSlash(rel)] = data
		return err
	})
	if errors.Is(err, fs.ErrNotExist) {
		return files, nil
	}
	return files, err
}
