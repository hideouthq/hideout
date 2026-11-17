package main

import (
	"crypto/rand"
	"strings"

	"github.com/tailscale/tailcat"
	"go4.org/mem"
	"golang.org/x/crypto/argon2"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

const (
	codeAlphabet = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
	secretLength = 8

	unbiasedBelow = 256 - 256%len(codeAlphabet)

	keySalt     = "hideout invite v1"
	keyPasses   = 3
	keyMemoryKB = 64 * 1024
	keyThreads  = 1
	keyBytes    = 64
)

type tunnelKeys struct {
	node key.NodePrivate
	psk  tailcat.PresharedKey
}

func newSecret() string {
	secret := make([]byte, 0, secretLength)
	var b [1]byte
	for len(secret) < secretLength {
		rand.Read(b[:])
		if int(b[0]) < unbiasedBelow {
			secret = append(secret, codeAlphabet[int(b[0])%len(codeAlphabet)])
		}
	}
	return string(secret)
}

func deriveKeys(secret string) tunnelKeys {
	raw := argon2.IDKey([]byte(secret), []byte(keySalt), keyPasses, keyMemoryKB, keyThreads, keyBytes)
	return tunnelKeys{node: key.NodePrivateFromRaw32(mem.B(raw[:32])), psk: tailcat.PresharedKey(raw[32:])}
}

func inviteCode(secret string, region tailcfg.DERPRegionID) string {
	return secret + string(codeAlphabet[int(region)%len(codeAlphabet)])
}

func parseCode(code string) (secret string, residue int, ok bool) {
	if len(code) != secretLength+1 || strings.Trim(code, codeAlphabet) != "" {
		return "", 0, false
	}
	return code[:secretLength], strings.IndexByte(codeAlphabet, code[secretLength]), true
}

func (k tunnelKeys) addr(region *tailcfg.DERPRegion) tailcat.Addr {
	ci := tailcat.ConnInfo{
		ServerPublic:      tailcat.NodePublic{NodePublic: k.node.Public()},
		ServerDiscoPublic: tailcat.DiscoPublicForNode(k.node),
		PresharedKey:      k.psk,
		Region:            []*tailcfg.DERPRegion{region},
	}
	return ci.Addr()
}

func hostAddrs(keys tunnelKeys, residue int, relays *tailcfg.DERPMap) []tailcat.Addr {
	var addrs []tailcat.Addr
	for id, region := range relays.Regions {
		if int(id)%len(codeAlphabet) == residue {
			addrs = append(addrs, keys.addr(region))
		}
	}
	return addrs
}
