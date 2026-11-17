package main

import (
	"context"
	"encoding/hex"
	"slices"
	"testing"
	"time"

	"github.com/tailscale/tailcat"
)

func TestTheGuestRebuildsTheHostsAddressFromTheCodeAlone(t *testing.T) {
	region, decoy := withID(localRelay(t), 331), withID(localRelay(t), 301)
	secret := newSecret()
	keys := deriveKeys(secret)
	tunnel := &tailcat.Server{Key: keys.node, PresharedKey: keys.psk, Region: region, Logf: func(string, ...any) {}}
	if err := tunnel.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { closeWithin(tunnel.Close) })

	ctx, cancel := context.WithTimeout(context.Background(), eventWait)
	defer cancel()
	relays, err := tailcat.FetchDERPMap(ctx, tailcat.DERPMapURL(relayMap(t, region, decoy)))
	if err != nil {
		t.Fatal(err)
	}
	gotSecret, residue, ok := parseCode(inviteCode(secret, region.RegionID))
	if !ok {
		t.Fatal("the host's code does not parse")
	}
	addrs := hostAddrs(deriveKeys(gotSecret), residue, relays)
	if len(addrs) != 2 || !slices.Contains(addrs, tunnel.TailcatAddr()) {
		t.Errorf("rebuilt %d addresses, none the host's own", len(addrs))
	}
}

func TestDerivingTheKeysTakesAboutATenthOfASecond(t *testing.T) {
	secret := newSecret()
	began := time.Now()
	keys := deriveKeys(secret)
	took := time.Since(began)
	t.Logf("Argon2id at 64 MiB took %v", took)
	if took > 2*time.Second {
		t.Errorf("deriving the keys took %v", took)
	}
	if again := deriveKeys(secret); !again.node.Equal(keys.node) || !again.psk.Equal(keys.psk) {
		t.Error("the same secret gave other keys")
	}
}

func TestTheDerivationMatchesThePinnedVector(t *testing.T) {
	keys := deriveKeys("P95MAN26")
	psk, err := keys.psk.MarshalBinary()
	if err != nil {
		t.Fatal(err)
	}
	if got := keys.node.Public().String(); got != "nodekey:bfeee3a1292cac8297632399af62bc5ae3db42bb93a371866fafcd340a6a375e" {
		t.Errorf("node public key = %s", got)
	}
	if got := hex.EncodeToString(psk); got != "ece0e17de3d77f8ec092af5b241d89ced5984b126423797fff880fca223cd5f2" {
		t.Errorf("preshared key = %s", got)
	}
}
