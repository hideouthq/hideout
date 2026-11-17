package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"maps"
	"net"
	"os"
	"path/filepath"
	"slices"
	"sync"
	"sync/atomic"
	"time"

	modlock "github.com/paralin/modlock/session"
	"github.com/paralin/modlock/src/host_app/control"
	manifest "github.com/paralin/modlock/src/wasm"
	"github.com/paralin/modlock/src/wasm/ui"
)

const (
	streamMagic = "HS1"
	helloSize   = len(streamMagic) + 8
	helloWait   = 5 * time.Second
	sendWait    = 30 * time.Second
	screenRetry = time.Second
	maxChunk    = 16 << 20

	frameReset byte = 1
)

type greeting struct {
	Addons map[string]string `json:"addons"`
}

type screenFeed struct {
	screens *modlock.Screens
	addons  map[string]string
	hello   []byte

	mu       sync.Mutex
	accounts map[int32]uint64
	drawn    map[int32]map[string]bool
}

func newScreenFeed(mods []string) (*screenFeed, error) {
	addons, err := readAddons(mods)
	if err != nil {
		return nil, err
	}
	hello, err := json.Marshal(greeting{Addons: addons})
	if err != nil {
		return nil, err
	}
	return &screenFeed{screens: modlock.NewScreens(0), addons: addons, hello: hello, accounts: make(map[int32]uint64), drawn: make(map[int32]map[string]bool)}, nil
}

func readAddons(mods []string) (map[string]string, error) {
	addons := make(map[string]string, len(mods))
	for _, dir := range mods {
		data, err := os.ReadFile(filepath.Join(dir, "mod.json"))
		if err != nil {
			return nil, err
		}
		mod := &manifest.Manifest{}
		if err := mod.UnmarshalJSON(data); err != nil {
			return nil, fmt.Errorf("%s: %w", dir, err)
		}
		if addon := mod.GetContentAddon(); addon != "" {
			addons[mod.GetSlug()] = addon
		}
	}
	return addons, nil
}

func (f *screenFeed) filter(emit Sink) Sink {
	return func(e Event) {
		if event, ok := e.(ServerEvent); ok && f.take(event.Event) {
			return
		}
		emit(e)
	}
}

func (f *screenFeed) take(raw []byte) bool {
	var wrapped struct {
		Host json.RawMessage `json:"host"`
	}
	if json.Unmarshal(raw, &wrapped) != nil || wrapped.Host == nil {
		return false
	}
	event := &control.HostEvent{}
	if event.UnmarshalJSON(wrapped.Host) != nil {
		return false
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	switch {
	case event.GetJoined() != nil:
		f.join(event)
	case event.GetLeft() != nil:
		f.free(event.GetLeft().GetSlot())
	case event.GetUi() != nil:
		update := event.GetUi()
		if update.GetMod() != modlock.FrameMod {
			if f.drawn[update.GetSlot()] == nil {
				f.drawn[update.GetSlot()] = make(map[string]bool)
			}
			f.drawn[update.GetSlot()][update.GetMod()] = true
			f.screens.Handle(event)
		}
		return true
	}
	return false
}

// join starts a person with an empty screen and leaves them in one slot, since
// Screens picks at random between two slots of one account. Mods hear of a join
// later than the feed does, so nothing drawn before it is for this person.
func (f *screenFeed) join(event *control.HostEvent) {
	slot, person := event.GetJoined().GetSlot(), event.GetJoined().GetSteamId()
	if f.accounts[slot] != person {
		f.clear(slot)
	}
	for other, account := range f.accounts {
		if other != slot && person != 0 && account == person {
			f.free(other)
		}
	}
	f.screens.Handle(event)
	f.accounts[slot] = person
}

func (f *screenFeed) free(slot int32) {
	delete(f.accounts, slot)
	f.screens.Handle(&control.HostEvent{Body: &control.HostEvent_Left{Left: &control.PlayerLeft{Slot: slot}}})
}

// clear ends every tree in a slot, since Screens keeps one after its player left
// and would show it to the next person. A reset with no nodes ends a tree.
func (f *screenFeed) clear(slot int32) {
	for mod := range f.drawn[slot] {
		f.screens.Handle(&control.HostEvent{Body: &control.HostEvent_Ui{Ui: &control.UiChanged{
			Mod: mod, Slot: slot, Change: &ui.Change{Reset_: true},
		}}})
	}
	delete(f.drawn, slot)
}

func (f *screenFeed) source(account uint64) modlock.ScreenSource {
	return func() (*ui.Screen, <-chan struct{}) {
		screen, _, changed := f.screens.Player(account)
		return withAddons(screen, f.addons), changed
	}
}

func (f *screenFeed) serveGuest(conn net.Conn) {
	defer conn.Close()
	conn.SetReadDeadline(time.Now().Add(helloWait))
	var hello [helloSize]byte
	if _, err := io.ReadFull(conn, hello[:]); err != nil || string(hello[:len(streamMagic)]) != streamMagic {
		return
	}
	conn.SetReadDeadline(time.Time{})
	account := binary.BigEndian.Uint64(hello[len(streamMagic):])

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() {
		io.Copy(io.Discard, conn)
		cancel()
	}()

	send := func(chunk []byte) error {
		conn.SetWriteDeadline(time.Now().Add(sendWait))
		return writeChunk(conn, chunk)
	}
	if send(f.hello) != nil {
		return
	}
	sent := &ui.Screen{}
	var lastFrame time.Time
	for reset := true; ; reset = false {
		screen, _, changed := f.screens.Player(account)
		if changes := modlock.Changes(sent, screen); reset || len(changes) != 0 {
			frame, err := encodeFrame(changes, reset)
			if err != nil {
				fmt.Fprintln(os.Stderr, "screens:", err)
				return
			}
			if send(frame) != nil {
				return
			}
			sent = screen
			lastFrame = time.Now()
		}
		select {
		case <-changed:
		case <-ctx.Done():
			return
		}
		select {
		case <-time.After(frameWait(lastFrame, time.Now())):
		case <-ctx.Done():
			return
		}
	}
}

func frameWait(lastFrame, now time.Time) time.Duration {
	return max(0, lastFrame.Add(modlock.FrameGap).Sub(now))
}

type guestScreen struct {
	mirror *modlock.Mirror
	addons atomic.Pointer[map[string]string]
}

func newGuestScreen() *guestScreen { return &guestScreen{mirror: modlock.NewMirror()} }

func (g *guestScreen) source() (*ui.Screen, <-chan struct{}) {
	screen, changed := g.mirror.Screen()
	if addons := g.addons.Load(); addons != nil {
		return withAddons(screen, *addons), changed
	}
	return screen, changed
}

func (g *guestScreen) follow(ctx context.Context, dial func(context.Context) (net.Conn, error), account uint64) {
	for ctx.Err() == nil {
		dialCtx, cancel := context.WithTimeout(ctx, helloWait)
		conn, err := dial(dialCtx)
		cancel()
		if err == nil {
			unwatch := context.AfterFunc(ctx, func() { conn.Close() })
			if err := g.read(conn, account); err != nil && !errors.Is(err, io.EOF) && ctx.Err() == nil {
				fmt.Fprintln(os.Stderr, "screens:", err)
			}
			unwatch()
			conn.Close()
		}
		select {
		case <-ctx.Done():
		case <-time.After(screenRetry):
		}
	}
}

func (g *guestScreen) read(conn net.Conn, account uint64) error {
	hello := binary.BigEndian.AppendUint64([]byte(streamMagic), account)
	if _, err := conn.Write(hello); err != nil {
		return err
	}
	first, err := readChunk(conn)
	if err != nil {
		return err
	}
	var greeted greeting
	if err := json.Unmarshal(first, &greeted); err != nil {
		return err
	}
	g.addons.Store(&greeted.Addons)
	for {
		chunk, err := readChunk(conn)
		if err != nil {
			return err
		}
		changes, reset, err := decodeFrame(chunk)
		if err != nil {
			return err
		}
		g.mirror.Apply(changes, reset)
	}
}

func withAddons(screen *ui.Screen, addons map[string]string) *ui.Screen {
	for _, tree := range screen.GetTrees() {
		tree.Addon = addons[tree.GetMod()]
	}
	return screen
}

func writeChunk(w io.Writer, chunk []byte) error {
	_, err := w.Write(append(binary.BigEndian.AppendUint32(nil, uint32(len(chunk))), chunk...))
	return err
}

func readChunk(r io.Reader) ([]byte, error) {
	var size [4]byte
	if _, err := io.ReadFull(r, size[:]); err != nil {
		return nil, err
	}
	n := binary.BigEndian.Uint32(size[:])
	if n > maxChunk {
		return nil, fmt.Errorf("a chunk of %d bytes is over the limit", n)
	}
	chunk := make([]byte, n)
	_, err := io.ReadFull(r, chunk)
	return chunk, err
}

func encodeFrame(changes map[string]*ui.Change, reset bool) ([]byte, error) {
	var flags byte
	if reset {
		flags = frameReset
	}
	frame := []byte{flags}
	for _, mod := range slices.Sorted(maps.Keys(changes)) {
		value, err := changes[mod].MarshalVT()
		if err != nil {
			return nil, err
		}
		frame = binary.AppendUvarint(frame, uint64(len(mod)))
		frame = append(frame, mod...)
		frame = binary.AppendUvarint(frame, uint64(len(value)))
		frame = append(frame, value...)
	}
	return frame, nil
}

func decodeFrame(frame []byte) (map[string]*ui.Change, bool, error) {
	if len(frame) == 0 {
		return nil, false, errors.New("an empty frame")
	}
	rest := bytes.NewReader(frame[1:])
	field := func() ([]byte, error) {
		n, err := binary.ReadUvarint(rest)
		if err != nil {
			return nil, err
		}
		if n > uint64(rest.Len()) {
			return nil, errors.New("a frame's field runs past its end")
		}
		value := make([]byte, n)
		_, err = io.ReadFull(rest, value)
		return value, err
	}
	changes := make(map[string]*ui.Change)
	for rest.Len() > 0 {
		mod, err := field()
		if err != nil {
			return nil, false, err
		}
		value, err := field()
		if err != nil {
			return nil, false, err
		}
		change := &ui.Change{}
		if err := change.UnmarshalVT(value); err != nil {
			return nil, false, err
		}
		changes[string(mod)] = change
	}
	return changes, frame[0]&frameReset != 0, nil
}
