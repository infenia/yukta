#!/bin/sh
# Stand-in for your real release commands (Gradle, kubectl, helm, curl against /health, ...).
# Usage: release.sh STEP VERSION. The version name picks the outcome so the example can run offline:
#   *build-fails*  the build step exits 1      *test-fails*  the test step exits 1
#   *smoke-fails*  the smoke check exits 1 once that version is live
state=/tmp/yukta-example-release-approval
v=$2
case "$1" in
  build)   case "$v" in *build-fails*) echo "build: compile error in $v"; exit 1;; esac; echo "build: $v ok" ;;
  test)    case "$v" in *test-fails*) echo "tests: 3 failed for $v"; exit 1;; esac; echo "tests: 128 passed for $v" ;;
  staging) echo "$v" >"$state/staging"; echo "staging: $v deployed, staging checks green" ;;
  deploy)  cp "$state/live" "$state/previous" 2>/dev/null; echo "$v" >"$state/live"; echo "deploy: $v is live (previous: $(cat "$state/previous" 2>/dev/null || echo none))" ;;
  smoke)   case "$(cat "$state/live")" in *smoke-fails*) echo "smoke: GET /health returned 503"; exit 1;; esac; echo "smoke: GET /health returned 200" ;;
  rollback) cp "$state/previous" "$state/live"; echo "rollback: restored $(cat "$state/live")" ;;
  *) echo "unknown step $1"; exit 2 ;;
esac
