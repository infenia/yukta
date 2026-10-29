#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Test release/install.sh against a throwaway local server that looks like GitHub release downloads.
# It never writes outside a temporary folder. Needs python3, curl and tar.
#
# Usage: release/test-install.sh
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

work="$(mktemp -d)"
pids=()
cleanup() { for p in "${pids[@]:-}"; do kill "$p" 2>/dev/null || true; done; rm -rf "$work"; }
trap cleanup EXIT
version=9.9.9-test
rel="$work/srv/v$version"
mkdir -p "$rel" "$work/stage/yukta-$version-linux-amd64"
printf '#!/bin/sh\necho server\n' > "$work/stage/yukta-$version-linux-amd64/yukta"
printf '#!/bin/sh\n[ "${1:-}" = --version ] && echo "yukta-cli version 9.9.9-test" || echo client\n' > "$work/stage/yukta-$version-linux-amd64/yukta-cli"
chmod +x "$work"/stage/*/yukta*
tar -C "$work/stage" -czf "$rel/yukta-$version-linux-amd64.tar.gz" "yukta-$version-linux-amd64"
cp "$work/stage/yukta-$version-linux-amd64/yukta-cli" "$rel/yukta-cli-linux-amd64"
cp "$work/stage/yukta-$version-linux-amd64/yukta-cli" "$rel/yukta-cli-darwin-arm64"
(cd "$rel" && sha256sum -- * > SHA256SUMS)

# Bind to port 0 so the system picks a free one; the server prints it once it is listening, so there
# is no guess at a port and no sleep.
(cd "$work/srv" && exec python3 -u -c '
import http.server
s = http.server.ThreadingHTTPServer(("127.0.0.1", 0), http.server.SimpleHTTPRequestHandler)
print(s.server_address[1], flush=True)
s.serve_forever()' >"$work/port" 2>"$work/server.err") &
pids+=($!)
port=""
for _ in $(seq 1 50); do
  port="$(cat "$work/port" 2>/dev/null || true)"
  [[ -n $port ]] && break
  kill -0 "${pids[0]}" 2>/dev/null || die "download server exited: $(cat "$work/server.err")"
  sleep 0.2
done
[[ -n $port ]] || die "download server did not report a port"
export YUKTA_DOWNLOAD_BASE="http://127.0.0.1:$port"
script="$RELEASE_DIR/install.sh"
fail() { echo "FAIL: $*" >&2; exit 1; }

echo "1. installs server and client"
sh "$script" --version "$version" --install-dir "$work/a" >/dev/null
[[ "$("$work/a/yukta")" == server && "$("$work/a/yukta-cli")" == client ]] || fail "wrong programs"

echo "2. --cli-only and --server-only"
sh "$script" --version "$version" --install-dir "$work/b" --cli-only >/dev/null
[[ -x "$work/b/yukta-cli" && ! -e "$work/b/yukta" ]] || fail "--cli-only"
sh "$script" --version "$version" --install-dir "$work/c" --server-only >/dev/null
[[ -x "$work/c/yukta" && ! -e "$work/c/yukta-cli" ]] || fail "--server-only"

echo "3. a changed download is refused"
echo tamper >> "$rel/yukta-$version-linux-amd64.tar.gz"
if sh "$script" --version "$version" --install-dir "$work/d" 2>"$work/err"; then fail "tampered file was installed"; fi
grep -q "checksum mismatch" "$work/err" || fail "no checksum message"
[[ ! -e "$work/d/yukta" ]] || fail "tampered file was copied"
# put the good archive back for the next checks
tar -C "$work/stage" -czf "$rel/yukta-$version-linux-amd64.tar.gz" "yukta-$version-linux-amd64"
(cd "$rel" && sha256sum -- $(ls | grep -v '^SHA256SUMS$') > SHA256SUMS)

echo "4. macOS gets the client and a Docker hint"
out="$(YUKTA_OS=Darwin YUKTA_ARCH=arm64 sh "$script" --version "$version" --install-dir "$work/e")"
[[ -x "$work/e/yukta-cli" && ! -e "$work/e/yukta" ]] || fail "macOS client"
grep -q "docker run" <<<"$out" || fail "no Docker hint"
if YUKTA_OS=Darwin YUKTA_ARCH=arm64 sh "$script" --version "$version" --install-dir "$work/f" --server-only 2>/dev/null; then
  fail "--server-only should fail without a native server"
fi

echo "5. piped to sh with options"
cat "$script" | sh -s -- --version "$version" --install-dir "$work/g" --cli-only >/dev/null || fail "piped run"
[[ -x "$work/g/yukta-cli" ]] || fail "piped install"

echo "6. uninstall"
sh "$script" --install-dir "$work/a" --uninstall >/dev/null
[[ ! -e "$work/a/yukta" && ! -e "$work/a/yukta-cli" ]] || fail "uninstall"

echo "6b. upgrade keeps *.prev, same version short-circuits, --check reports"
mkdir -p "$work/u"; printf '#!/bin/sh\necho old\n' > "$work/u/yukta"; chmod +x "$work/u/yukta"
out="$(sh "$script" --version "$version" --install-dir "$work/u")"
[[ "$("$work/u/yukta.prev")" == old && "$("$work/u/yukta")" == server ]] || fail "previous server not kept"
grep -q "kept as \*.prev" <<<"$out" || fail "no rollback hint"
grep -q "checksum" <<<"$out" || fail "no verification line"
sh "$script" --version "$version" --install-dir "$work/u" | grep -q "already installed" || fail "no up-to-date message"
sh "$script" --version "$version" --install-dir "$work/u" --check >/dev/null || fail "--check should pass when current"
if sh "$script" --version 9.9.8 --install-dir "$work/u" --check >/dev/null; then fail "--check should fail when behind"; fi

echo "7. bad input"
if sh "$script" --bogus 2>/dev/null; then fail "unknown option accepted"; fi
if YUKTA_ARCH=riscv64 sh "$script" --version "$version" --install-dir "$work/h" 2>/dev/null; then fail "unsupported arch accepted"; fi

for bad in '1.0/../../x' '1.0.0?x=1' '1.0.0#frag' '1.0' 'latest' '1.0.0
../x'; do
  if sh "$script" --version "$bad" --install-dir "$work/i" 2>"$work/err"; then fail "version '$bad' accepted"; fi
  grep -q "is not a version" "$work/err" || fail "no version message for '$bad'"
done

echo "8. an archive with a link instead of a file is refused"
ln -sf /etc/hostname "$work/stage/yukta-$version-linux-amd64/yukta"
tar -C "$work/stage" -czf "$rel/yukta-$version-linux-amd64.tar.gz" "yukta-$version-linux-amd64"
(cd "$rel" && sha256sum -- $(ls | grep -v '^SHA256SUMS$') > SHA256SUMS)
if sh "$script" --version "$version" --install-dir "$work/j" 2>"$work/err"; then fail "linked file was installed"; fi
grep -q "regular file" "$work/err" || fail "no regular-file message"

echo "9. redirected output is plain; --quiet is silent; a failure adds no control codes"
rm -f "$work/stage/yukta-$version-linux-amd64/yukta"
printf '#!/bin/sh\necho server\n' > "$work/stage/yukta-$version-linux-amd64/yukta"; chmod +x "$work/stage/yukta-$version-linux-amd64/yukta"
tar -C "$work/stage" -czf "$rel/yukta-$version-linux-amd64.tar.gz" "yukta-$version-linux-amd64"
(cd "$rel" && sha256sum -- $(ls | grep -v '^SHA256SUMS$') > SHA256SUMS)
sh "$script" --version "$version" --install-dir "$work/k" >"$work/plain.out" 2>&1
grep -q "Download server + client" "$work/plain.out" || fail "no step lines"
! grep -q $'\033\|\r' "$work/plain.out" || fail "escape codes or carriage returns in redirected output"
[[ -z "$(sh "$script" --version "$version" --install-dir "$work/k" --force --quiet 2>&1)" ]] || fail "--quiet printed something"
echo tamper >> "$rel/yukta-$version-linux-amd64.tar.gz"
sh "$script" --version "$version" --install-dir "$work/l" >"$work/plain.out" 2>&1 || true
! grep -q $'\033\|\r' "$work/plain.out" || fail "control characters on the failure path"

echo "install.sh: all checks passed"
