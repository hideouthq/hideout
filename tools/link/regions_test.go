package main

import (
	"context"
	"encoding/json"
	"net"
	"testing"
	"time"

	"tailscale.com/tailcfg"
)

type regionLine struct {
	ID   int    `json:"id"`
	Code string `json:"code"`
	MS   *int   `json:"ms"`
}

func TestTheInviteNamesTheHostsRegion(t *testing.T) {
	relay := localRelay(t)
	host := hostSessionOf(t, HostConfig{ServerPort: echoServer(t), Region: relay})
	var invite struct {
		Region regionLine `json:"region"`
	}
	if err := json.Unmarshal([]byte(host.stream.until("invite").Raw), &invite); err != nil {
		t.Fatal(err)
	}
	if invite.Region.ID != int(relay.RegionID) || invite.Region.Code != relay.RegionCode || relay.RegionCode == "" {
		t.Errorf("the invite names region %+v, want %d %q", invite.Region, relay.RegionID, relay.RegionCode)
	}
}

func TestRegionsMeasureEachRelayAndLeaveOutOneThatIsSilent(t *testing.T) {
	silent, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer silent.Close()
	gone := &tailcfg.DERPRegion{RegionID: 7, RegionCode: "gone", Nodes: []*tailcfg.DERPNode{{
		Name: "7a", RegionID: 7, HostName: "127.0.0.1", IPv4: "127.0.0.1", IPv6: "none",
		STUNPort: silent.LocalAddr().(*net.UDPAddr).Port, DERPPort: 1,
	}}}
	said := newStream(t)
	started := time.Now()
	stopped := runRegions(context.Background(), RegionsConfig{DERPMapURL: relayMap(t, localRelay(t), gone)}, newLineSink(said))
	if took := time.Since(started); took > regionsWithin {
		t.Errorf("regions took %v, over its bound of %v", took, regionsWithin)
	}
	lines := said.seen()
	if stopped.Reason != ReasonDone || len(lines) != 2 || lines[0].Type != "regions" {
		t.Fatalf("said %v", lines)
	}
	var got struct {
		Regions []regionLine `json:"regions"`
	}
	if err := json.Unmarshal([]byte(lines[0].Raw), &got); err != nil {
		t.Fatal(err)
	}
	if len(got.Regions) != 2 || got.Regions[0].ID != 1 || got.Regions[0].MS == nil || got.Regions[1].ID != 7 || got.Regions[1].Code != "gone" || got.Regions[1].MS != nil {
		t.Errorf("want region 1 measured and region 7 without ms, got %s", lines[0].Raw)
	}
}

func TestRegionsWithoutTheRelayMapSayRelayUnavailable(t *testing.T) {
	said := newStream(t)
	stopped := runRegions(context.Background(), RegionsConfig{DERPMapURL: "http://127.0.0.1:1/derpmap.json"}, newLineSink(said))
	if lines := said.seen(); stopped.Reason != ReasonRelayUnavailable || len(lines) != 1 {
		t.Errorf("said %v", lines)
	}
}
