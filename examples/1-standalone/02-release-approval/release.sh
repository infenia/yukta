#!/bin/sh
# Stand-in for your real release commands (Gradle, kubectl, helm, curl against /health, ...).
# Usage: release.sh STEP VERSION. The version name picks the outcome so the example can run offline:
#   *build-fails*  the build step exits 1      *test-fails*  the test step exits 1
#   *smoke-fails*  the smoke check exits 1 once that version is live
state=/tmp/yukta-examples/release-approval
v=$2
case "$1" in
  build)   case "$v" in *build-fails*) echo "build: compile error in $v"; exit 1;; esac; echo "build: $v ok" ;;
  test)    mkdir -p "$state/test-results"
           case "$v" in *test-fails*)
             # what a real test runner leaves behind: a JUnit XML report, read by the node's reports option
             cat >"$state/test-results/TEST-ReleaseTest.xml" <<EOF
<testsuite name="ReleaseTest" tests="128" failures="3">
  <testcase classname="com.example.ReleaseTest" name="migratesSchema"><failure message="expected version 12 but was 11">at ReleaseTest.java:41</failure></testcase>
  <testcase classname="com.example.ReleaseTest" name="keepsSessions"><failure message="session lost after restart">at ReleaseTest.java:77</failure></testcase>
  <testcase classname="com.example.ReleaseTest" name="servesHealth"><failure message="GET /health returned 503">at ReleaseTest.java:102</failure></testcase>
</testsuite>
EOF
             echo "tests: 3 failed for $v"; exit 1;; esac
           echo "tests: 128 passed for $v" ;;
  staging) echo "$v" >"$state/staging"; echo "staging: $v deployed, staging checks green" ;;
  deploy)  cp "$state/live" "$state/previous" 2>/dev/null; echo "$v" >"$state/live"; echo "deploy: $v is live (previous: $(cat "$state/previous" 2>/dev/null || echo none))" ;;
  smoke)   case "$(cat "$state/live")" in *smoke-fails*) echo "smoke: GET /health returned 503"; exit 1;; esac; echo "smoke: GET /health returned 200" ;;
  rollback) cp "$state/previous" "$state/live"; echo "rollback: restored $(cat "$state/live")" ;;
  *) echo "unknown step $1"; exit 2 ;;
esac
