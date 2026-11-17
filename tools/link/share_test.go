package main

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"
)

func writePack(t testing.TB, size int) (string, string) {
	t.Helper()
	data := make([]byte, size)
	rand.Read(data)
	file := filepath.Join(t.TempDir(), "pack.zip")
	if err := os.WriteFile(file, data, 0o644); err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(data)
	return file, hex.EncodeToString(sum[:])
}

func fetchingGuest(t testing.TB, invite hosted, want PackFetch, linkTimeout time.Duration) *session {
	t.Helper()
	s := guestSessionOf(t, "127.0.0.1:0", invite.derpMap, linkTimeout, 0)
	go answer(s.stdin, invite.code, "--page", noPage, "--fetch", want.File, "--sha256", want.SHA256, "--bytes", strconv.FormatInt(want.Bytes, 10))
	return s
}

func TestAGuestFetchesTheHostsPackAfterLinking(t *testing.T) {
	pack, sum := writePack(t, 3<<20)
	_, invite := startHost(t, HostConfig{ServerPort: echoServer(t), Region: localRelay(t), Share: pack})
	target := filepath.Join(t.TempDir(), "fetched")
	guest := fetchingGuest(t, invite, PackFetch{File: target, SHA256: sum, Bytes: 3 << 20}, testLink)

	guest.stream.until("linked")
	guest.stream.until("fetched")
	got, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	if digest := sha256.Sum256(got); hex.EncodeToString(digest[:]) != sum {
		t.Error("the fetched file is not the host's pack")
	}
	var last line
	for _, l := range guest.stream.seen() {
		if l.Type == "fetch" {
			last = l
		}
	}
	if last.Done != 3<<20 || last.Total != 3<<20 {
		t.Errorf("last progress = %d of %d", last.Done, last.Total)
	}
	if st := guest.end(t); st.Reason != ReasonStdinClosed {
		t.Errorf("stopped with %s", st.Reason)
	}
}

func TestAPackThatIsNotTheInvitesStopsTheJoin(t *testing.T) {
	pack, _ := writePack(t, 1<<20)
	_, invite := startHost(t, HostConfig{ServerPort: echoServer(t), Region: localRelay(t), Share: pack})
	_, other := writePack(t, 1<<20)
	tests := []struct {
		name string
		want PackFetch
		stop Reason
	}{
		{"another hash", PackFetch{SHA256: other, Bytes: 1 << 20}, ReasonFetchMismatch},
		{"shorter than said", PackFetch{SHA256: other, Bytes: 2 << 20}, ReasonFetchFailed},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			tt.want.File = filepath.Join(t.TempDir(), "fetched")
			guest := fetchingGuest(t, invite, tt.want, testLink)
			if st := guest.result(t, eventWait); st.Reason != tt.stop {
				t.Errorf("stopped with %s %q, want %s", st.Reason, st.Message, tt.stop)
			}
			for _, l := range guest.stream.seen() {
				if l.Type == "fetched" {
					t.Error("said fetched")
				}
			}
		})
	}
}

func shareHost(t *testing.T, pack string) (*helper, hosted) {
	t.Helper()
	relay := localRelay(t)
	region, err := json.Marshal(relay)
	if err != nil {
		t.Fatal(err)
	}
	host := startHelper(t, "helper-host", string(region), "--server-port", strconv.Itoa(int(echoServer(t))), "--steam-id", hostAccount, "--share", pack, "--page", noPage, "--offer", testOffer, "--", os.Args[0], "child-until-eof")
	return host, hostedOn(t, host.stream.until("invite").Code, relay)
}

func TestAHostGoneMidTransferEndsTheJoinAsLinkLost(t *testing.T) {
	const size = 256 << 20
	pack, sum := writePack(t, size)
	host, invite := shareHost(t, pack)
	const silence = 3 * time.Second
	guest := fetchingGuest(t, invite, PackFetch{File: filepath.Join(t.TempDir(), "fetched"), SHA256: sum, Bytes: size}, silence)

	if l := guest.stream.until("fetch"); l.Done >= size {
		t.Skip("the whole pack arrived before the first report, so nothing was mid-transfer")
	}
	host.cmd.Process.Kill()
	st := guest.result(t, silence+pathPoll+pathTimeout+eventWait)
	if st.Reason != ReasonLinkLost && st.Reason != ReasonFetchFailed {
		t.Errorf("stopped with %s", st.Reason)
	}
	for _, l := range guest.stream.seen() {
		if l.Type == "fetched" {
			t.Error("said fetched")
		}
	}
}

func TestAGuestFetchingHearsTheHostEnd(t *testing.T) {
	const size = 256 << 20
	pack, sum := writePack(t, size)
	host, invite := shareHost(t, pack)
	guest := fetchingGuest(t, invite, PackFetch{File: filepath.Join(t.TempDir(), "fetched"), SHA256: sum, Bytes: size}, testLink)

	if l := guest.stream.until("fetch"); l.Done >= size {
		t.Skip("the whole pack arrived before the first report, so nothing was mid-transfer")
	}
	host.stdin.Close()
	if st := guest.result(t, heardWithin); st.Reason != ReasonHostEnded {
		t.Errorf("stopped with %s", st.Reason)
	}
}

func TestARealPackCrossesTheRelay(t *testing.T) {
	pack := os.Getenv("HIDEOUT_SHARE_PACK")
	if pack == "" {
		t.Skip("set HIDEOUT_SHARE_PACK to a pack")
	}
	data, err := os.ReadFile(pack)
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(data)
	_, invite := shareHost(t, pack)
	target := filepath.Join(t.TempDir(), "fetched")
	guest := startJoinHelper(t, invite, "--page", noPage, "--fetch", target, "--sha256", hex.EncodeToString(sum[:]), "--bytes", strconv.Itoa(len(data)))
	guest.stream.until("linked")
	linked := time.Now()
	guest.stream.until("fetched")
	took := time.Since(linked)
	t.Logf("%d bytes from linked to fetched in %v, %.1f MB/s", len(data), took, float64(len(data))/took.Seconds()/1e6)
	for _, l := range guest.stream.seen() {
		if l.Type == "path" {
			t.Logf("path %s", l.Kind)
		}
	}
}
