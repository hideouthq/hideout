package main

import (
	"cmp"
	"context"
	"fmt"
	"os"
	"slices"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/net/netcheck"
	"tailscale.com/net/netmon"
	"tailscale.com/net/netns"
	"tailscale.com/types/logger"
)

// regionsWithin bounds the relay map's fetch and netcheck's own 5 s report.
const regionsWithin = 10 * time.Second

type RegionsConfig struct {
	DERPMapURL string
}

// runRegions measures with netcheck, as Tailcat does to pick a host's region, so a listed
// game's ping is the one its host chose by.
func runRegions(ctx context.Context, cfg RegionsConfig, emit Sink) Stopped {
	ctx, cancel := context.WithTimeout(ctx, regionsWithin)
	defer cancel()
	regions, err := measureRegions(ctx, cfg.DERPMapURL)
	stopped := Stopped{Reason: ReasonDone}
	if err != nil {
		fmt.Fprintln(os.Stderr, "regions:", err)
		stopped = Stopped{Reason: ReasonRelayUnavailable}
	} else {
		emit(Regions{Regions: regions})
	}
	emit(stopped)
	return stopped
}

func measureRegions(ctx context.Context, derpMapURL string) ([]RegionLatency, error) {
	relays, err := tailcat.FetchDERPMap(ctx, tailcat.DERPMapURL(cmp.Or(derpMapURL, tailcat.DefaultDERPMapURL)))
	if err != nil {
		return nil, err
	}
	// As Tailcat's own pick does: netns's fallback binds STUN to the default interface,
	// which never reaches a loopback relay.
	netns.SetEnabled(false)
	// Verbose is netcheck's switch to measure every region, not stop after the nearest three.
	check := &netcheck.Client{NetMon: netmon.NewStatic(), Verbose: true, Logf: logger.Discard}
	if err := check.Standalone(ctx, ":0"); err != nil {
		return nil, err
	}
	report, err := check.GetReport(ctx, relays, nil)
	if err != nil {
		return nil, err
	}
	regions := []RegionLatency{}
	for id, region := range relays.Regions {
		row := RegionLatency{Region: Region{ID: int(id), Code: region.RegionCode}}
		if rtt, ok := report.RegionLatency[id]; ok {
			ms := int(rtt.Round(time.Millisecond) / time.Millisecond)
			row.MS = &ms
		}
		regions = append(regions, row)
	}
	slices.SortFunc(regions, func(a, b RegionLatency) int { return a.ID - b.ID })
	return regions, nil
}
