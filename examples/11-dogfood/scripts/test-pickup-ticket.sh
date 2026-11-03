#!/usr/bin/env bash
# Offline test of pickup mode A (YUKTA_PICKUP_MODE=ticket): no channel needed, the top Ready issue starts `yk ticket run` (a fake yk), one per tick.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/home"
cat >"$tmp/bin/gh" <<'GH'
#!/usr/bin/env bash
case "$1 $2" in
  "issue list") echo '[{"number":11,"title":"low","url":"u11"},{"number":12,"title":"top","url":"u12"}]' ;;
  "project item-list") echo '{"items":[{"status":"Ready","priority":"P2","content":{"number":11}},{"status":"Ready","priority":"P0","content":{"number":12}}]}' ;;
  "pr list") echo '[]' ;;
  "api repos/"*) echo '[]' ;;
  *) echo "fake gh: unexpected $*" >&2; exit 9 ;;
esac
GH
printf '#!/usr/bin/env bash\necho "$*" >>%q/yk.log\n' "$tmp" >"$tmp/yk"; chmod +x "$tmp/bin/gh" "$tmp/yk"
export PATH="$tmp/bin:$PATH" YUKTA_DEV_HOME="$tmp/home" YUKTA_PICKUP_YK="$tmp/yk"
out="$(YUKTA_PICKUP_MODE=ticket "$here/scripts/pickup.sh" tick)" || fail "ticket mode: $out"
[[ "$out" == "PICKUP handed=#12 to=ticket" ]] || fail "the top priority issue is handed: $out"
[[ "$(cat "$tmp/yk.log")" == "ticket run 12 --detach" ]] || fail "yk ticket run 12 --detach: $(cat "$tmp/yk.log")"
echo ticket >"$tmp/home/pickup-mode"; : >"$tmp/yk.log"
out="$("$here/scripts/pickup.sh" tick)"; [[ "$out" == "PICKUP handed=#12 to=ticket" ]] || fail "the pickup-mode file switches too: $out"
rm "$tmp/home/pickup-mode"
out="$(YUKTA_DEV_HOME="$tmp/home" "$here/scripts/pickup.sh" tick)" || true; [[ "$out" == "PICKUP skipped=no channel"* ]] || fail "default mode stays sessions: $out"
echo "OK test-pickup-ticket"
