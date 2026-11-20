#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# The decision maker of the supervisor "cpu-guard", run by `yukta supervisors agent`:
#   yukta supervisors agent --credential-file cpu-guard.token --exec ./cpu_guard.sh
# Every signal arrives on stdin as a JSON line; every JSON line printed on stdout is a directive.
# While the 1-minute load average is above LIMIT it pauses each run that starts or changes; when
# the load is back below LIMIT it resumes only what it paused ("held").
LIMIT="${LIMIT:-4.0}"
while read -r line; do
  load=$(cut -d' ' -f1 /proc/loadavg)
  busy=$(awk -v l="$load" -v m="$LIMIT" 'BEGIN { print (l > m) ? 1 : 0 }')
  run=$(printf '%s' "$line" | jq -r '.signal.event.executionId // empty' 2>/dev/null)
  if [ "$busy" = 1 ] && [ -n "$run" ]; then
    printf '{"type":"command","key":"pause-%s-%s","cause":"initiative","reason":"load %s is above %s","ifStatus":"RUNNING","command":{"type":"PauseWorkflow","executionId":"%s"}}\n' \
      "$run" "$(date +%s)" "$load" "$LIMIT" "$run"
  elif [ "$busy" = 0 ]; then
    printf '{"type":"command","key":"resume-%s","cause":"tick","reason":"load %s is back under %s","command":{"type":"ResumeWorkflow","target":"held"}}\n' \
      "$(date +%s)" "$load" "$LIMIT"
  fi
done
