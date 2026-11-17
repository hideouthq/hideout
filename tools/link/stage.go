package main

import (
	"errors"
	"fmt"
	"io"
	"io/fs"
	"maps"
	"os"
	"path"
	"path/filepath"
	"slices"
	"time"

	"github.com/paralin/modlock/check"
)

const (
	heldRetry  = 10 * time.Second
	heldPoll   = 250 * time.Millisecond
	partSuffix = ".hideout-part"
)

type StageConfig struct {
	Into      string
	Content   []string
	HeldRetry time.Duration
}

func runStage(cfg StageConfig, emit Sink) Stopped {
	stopped := Stopped{Reason: ReasonDone}
	if held, err := stage(cfg); err != nil {
		stopped = Stopped{Reason: ReasonFailed, Message: err.Error()}
	} else {
		emit(Staged{Held: held})
	}
	emit(stopped)
	return stopped
}

func stage(cfg StageConfig) ([]string, error) {
	wanted, err := readContent(cfg.Content)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(cfg.Into, 0o755); err != nil {
		return nil, err
	}
	var unwanted []string
	err = filepath.WalkDir(cfg.Into, func(file string, entry fs.DirEntry, err error) error {
		if err != nil || entry.IsDir() {
			return err
		}
		name, err := filepath.Rel(cfg.Into, file)
		if _, ok := wanted[filepath.ToSlash(name)]; err == nil && !ok {
			unwanted = append(unwanted, file)
		}
		return err
	})
	if err != nil {
		return nil, err
	}
	held := []string{}
	for _, file := range unwanted {
		if err := retryHeld(cfg.HeldRetry, func() error { return removeFile(file) }); err != nil {
			name, _ := filepath.Rel(cfg.Into, file)
			held = append(held, filepath.ToSlash(name))
		}
	}
	for _, name := range slices.Sorted(maps.Keys(wanted)) {
		if err := place(wanted[name], filepath.Join(cfg.Into, filepath.FromSlash(name)), cfg.HeldRetry); err != nil {
			return nil, fmt.Errorf("put %s in the game: %w", name, err)
		}
	}
	removeEmptyFolders(cfg.Into)
	return held, nil
}

func readContent(dirs []string) (map[string]string, error) {
	content := map[string]string{}
	for _, dir := range dirs {
		built := os.DirFS(dir)
		for _, kind := range check.AddonDirs() {
			err := fs.WalkDir(built, kind, func(name string, entry fs.DirEntry, err error) error {
				switch {
				case name == kind && errors.Is(err, fs.ErrNotExist):
					return nil
				case err != nil || !entry.Type().IsRegular():
					return err
				}
				content[name] = filepath.Join(dir, filepath.FromSlash(name))
				return nil
			})
			if err != nil {
				return nil, fmt.Errorf("read the mode's content: %w", err)
			}
		}
		archives, err := fs.Glob(built, path.Join(check.MapsDir, "*.vpk"))
		if err != nil {
			return nil, err
		}
		for _, name := range archives {
			content[name] = filepath.Join(dir, filepath.FromSlash(name))
		}
	}
	return content, nil
}

func place(source, target string, retry time.Duration) error {
	from, err := os.Stat(source)
	if err != nil {
		return err
	}
	if to, err := os.Stat(target); err == nil && to.Size() == from.Size() && to.ModTime().Unix() == from.ModTime().Unix() {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		return err
	}
	part := target + partSuffix
	err = copyFile(source, part)
	if err == nil {
		err = os.Chtimes(part, from.ModTime(), from.ModTime())
	}
	if err == nil {
		err = retryHeld(retry, func() error { return os.Rename(part, target) })
	}
	if err != nil {
		os.Remove(part)
	}
	return err
}

func copyFile(source, target string) error {
	in, err := os.Open(source)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.Create(target)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}

func removeFile(file string) error {
	if err := os.Remove(file); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	return nil
}

// Windows refuses to remove or replace a file another process has open.
func retryHeld(retry time.Duration, op func() error) error {
	deadline := time.Now().Add(retry)
	for {
		err := op()
		if err == nil || time.Now().After(deadline) {
			return err
		}
		time.Sleep(heldPoll)
	}
}

func removeEmptyFolders(root string) {
	var folders []string
	filepath.WalkDir(root, func(folder string, entry fs.DirEntry, err error) error {
		if err == nil && entry.IsDir() && folder != root {
			folders = append(folders, folder)
		}
		return nil
	})
	for _, folder := range slices.Backward(folders) {
		os.Remove(folder)
	}
}
