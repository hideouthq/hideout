package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"

	"github.com/paralin/modlock/src/host_app/control"
	"github.com/paralin/modlock/src/wasm/ui"
)

const stubMod = "arena"

func runStubConsole(path string, lines []string) {
	console, err := os.Create(path)
	if err != nil {
		panic(err)
	}
	defer console.Close()
	note, err := json.Marshal(map[string]string{"note": "console: " + path})
	if err != nil {
		panic(err)
	}
	fmt.Printf("%s\n", note)
	fmt.Println(`{"host":{"ready":{"map":"street"}}}`)
	for _, text := range lines {
		fmt.Fprint(console, text)
	}
	io.Copy(io.Discard, os.Stdin)
}

var stubLogs = []string{
	stubPlainLog,
	"hideout:" + `[{"scoreboard":{}}]`,
	"hideout:" + `{"notes":"` + strings.Repeat("x", 4096) + `"}`,
	"hideout:" + stubReport,
}

const (
	stubPlainLog = "round 6 starts"
	stubReport   = `{"scoreboard":{"sides":[{"score":3,"slots":[0]},{"score":2,"slots":[1]}],"notes":["First to 5","Round 6"]}}`
)

func markerOf(account uint64) string { return fmt.Sprintf("secret-%d", account) }

func runStubModlock(port string) {
	number, err := strconv.Atoi(port)
	if err != nil {
		panic(err)
	}
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: number})
	if err != nil {
		panic(err)
	}
	var printing sync.Mutex
	say := func(event *control.HostEvent) {
		body, err := event.MarshalJSON()
		if err != nil {
			panic(err)
		}
		printing.Lock()
		defer printing.Unlock()
		fmt.Printf("{\"host\":%s}\n", body)
	}
	say(&control.HostEvent{Body: &control.HostEvent_Ready{Ready: &control.ServerReady{}}})
	for _, text := range stubLogs {
		say(&control.HostEvent{Body: &control.HostEvent_Log{Log: &control.ModLog{Mod: stubMod, Text: text}}})
	}

	type player struct {
		slot    int32
		account uint64
		presses int
	}
	players := map[string]*player{}
	last := map[string]string{}
	freeSlot := func() int32 {
		for slot := int32(0); ; slot++ {
			taken := false
			for _, p := range players {
				taken = taken || p.slot == slot
			}
			if !taken {
				return slot
			}
		}
	}
	show := func(slot int32, change *ui.Change) {
		say(&control.HostEvent{Body: &control.HostEvent_Ui{Ui: &control.UiChanged{Mod: stubMod, Slot: slot, Change: change}}})
	}
	go func() {
		io.Copy(io.Discard, os.Stdin)
		os.Exit(0)
	}()

	buf := make([]byte, maxDatagram)
	for {
		n, from, err := conn.ReadFromUDP(buf)
		if err != nil {
			panic(err)
		}
		text, address := string(buf[:n]), from.String()
		conn.WriteToUDP(fmt.Appendf(nil, "%s|%d", text, from.Port), from)
		if last[address] == text {
			continue
		}
		last[address] = text
		fields := strings.Fields(text)
		sender := players[address]
		switch {
		case fields[0] == "join" && sender == nil:
			account, err := strconv.ParseUint(fields[1], 10, 64)
			if err != nil {
				panic(err)
			}
			sender = &player{slot: freeSlot(), account: account}
			players[address] = sender
			say(&control.HostEvent{Body: &control.HostEvent_Joined{Joined: &control.PlayerJoined{Slot: sender.slot, SteamId: account}}})
			show(sender.slot, &ui.Change{Reset_: true, Set: []*ui.Node{{Id: "mark", Kind: ui.Kind_KIND_LABEL, Text: markerOf(account)}}})
		case fields[0] == "press" && sender != nil:
			sender.presses++
			show(sender.slot, &ui.Change{Set: []*ui.Node{{Id: fields[1], Kind: ui.Kind_KIND_LABEL, Text: fmt.Sprintf("%s pressed %d", markerOf(sender.account), sender.presses)}}})
		case fields[0] == "input" && sender != nil:
			show(sender.slot, &ui.Change{Set: []*ui.Node{{Id: fields[1], Kind: ui.Kind_KIND_LABEL, Text: markerOf(sender.account) + " typed " + fields[2]}}})
		case fields[0] == "fill" && sender != nil:
			count, err := strconv.Atoi(fields[2])
			if err != nil {
				panic(err)
			}
			show(sender.slot, &ui.Change{Set: []*ui.Node{{Id: fields[1], Kind: ui.Kind_KIND_LABEL, Text: markerOf(sender.account) + " " + strings.Repeat("é", count)}}})
		case fields[0] == "leave" && sender != nil:
			say(&control.HostEvent{Body: &control.HostEvent_Left{Left: &control.PlayerLeft{Slot: sender.slot}}})
			delete(players, address)
		}
	}
}
