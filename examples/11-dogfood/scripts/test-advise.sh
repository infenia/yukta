#!/usr/bin/env bash
# Offline test of advise.sh in a throwaway git repo against the fake Ollama: the floor from the paths, and a model that can only widen it.
# The fake model answers the label after "difficulty:" in the digest, so a changed file is named "difficulty:<label>".
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
repo="$(mktemp -d)"
trap '[[ -z "${pid:-}" ]] || kill "$pid" 2>/dev/null || true; rm -rf "$repo"' EXIT
python3 -I "$here/scripts/fake-ollama.py" "$repo/port" &
pid=$!
for ((i = 0; i < 20; i++)); do [[ -s "$repo/port" ]] && break; sleep 0.25; done
export DEV_HOME="$repo/home" YUKTA_DECISION_MODEL=fake YUKTA_DECISION_URL="http://127.0.0.1:$(cat "$repo/port")"
mkdir -p "$repo/w/d/scripts" && cd "$repo/w"
git init -q
cp "$here/scripts/advise.sh" "$here/scripts/_lib.sh" d/scripts/; cp "$here/decisions.json" d/
echo x >seed && git add -A && git -c user.email=t@t -c user.name=t commit -qm init
tier() { d/scripts/advise.sh --base HEAD "$@" | sed -E 's/^advice: tier ([0-9]).*/\1/'; }
change() { git clean -fdq && git checkout -q . && mkdir -p "$(dirname "$1")" && echo c >"$1"; }

change README.md;                          [[ "$(tier)" == 0 ]] || fail "docs: $(tier)"
change core/src/main/A.java;               [[ "$(tier --no-model)" == 1 ]] || fail "one class: $(tier --no-model)"
change plugin-api/src/main/B.java;         [[ "$(tier --no-model)" == 3 ]] || fail "shared code: $(tier --no-model)"
change build-logic/x.kts;                  [[ "$(tier --no-model)" == 4 ]] || fail "build: $(tier --no-model)"
# the model widens
change "core/src/main/difficulty:public-contract"; [[ "$(tier)" == 2 ]] || fail "public contract: $(tier)"
change "core/src/main/difficulty:build-or-config"; [[ "$(tier)" == 3 ]] || fail "build or config: $(tier)"
change "core/src/main/difficulty:uncertain";       [[ "$(tier)" == 2 ]] || fail "unsure: $(tier)"
# ... and never narrows
change "core/src/main/difficulty:docs-or-comment-only"; [[ "$(tier)" == 1 ]] || fail "docs label narrowed: $(tier)"
change "plugin-api/difficulty:internal-change";         [[ "$(tier)" == 3 ]] || fail "internal label narrowed: $(tier)"
# no model, or one that errors: the floor stands
change "core/src/main/difficulty:boom";      [[ "$(tier)" == 1 ]] || fail "model error: $(tier)"
change "core/src/main/difficulty:public-contract"; [[ "$(YUKTA_DECISION=off tier)" == 1 ]] || fail "model off: $(YUKTA_DECISION=off tier)"
echo "ok   advise"
