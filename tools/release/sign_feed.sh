#!/usr/bin/env bash
# Signs a release feed: sign_feed.sh KEY.pem releases.json writes releases.json.sig, a base64 Ed25519 signature on one
# line with no newline, which the app's java.security.Signature checks over the feed's exact bytes.
set -euo pipefail

trusted=${RELEASE_FEED_PUBLIC_KEY:-MCowBQYDK2VwAyEAKkjHdRhvoz4Mrt696uEkV5+XBfmnRU2amsyxY0GpwMo=}

fail() { echo "sign_feed: $1" >&2; exit 1; }

[[ $# == 2 ]] || fail 'usage: sign_feed.sh KEY.pem releases.json'
key=$1
feed=$2
[[ -r $key ]] || fail "cannot read the key file $key"
[[ -f $feed ]] || fail "no feed at $feed"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

openssl pkey -in "$key" -pubout -out "$work/public.pem" 2>/dev/null || fail "$key is not a readable private key"
actual=$(openssl pkey -pubin -in "$work/public.pem" -outform DER | base64 -w0)
[[ $actual == "$trusted" ]] ||
  fail "$key does not match the key the app trusts, so every installed copy would refuse this feed (its public key is $actual)"

openssl pkeyutl -sign -rawin -inkey "$key" -in "$feed" -out "$work/raw" 2>/dev/null || fail "openssl could not sign $feed"
[[ $(stat -c %s "$work/raw") == 64 ]] || fail 'the signature is not 64 bytes, so the key is not Ed25519'
openssl pkeyutl -verify -rawin -pubin -inkey "$work/public.pem" -sigfile "$work/raw" -in "$feed" >/dev/null ||
  fail 'the new signature does not verify'
base64 -w0 "$work/raw" > "$feed.sig"
