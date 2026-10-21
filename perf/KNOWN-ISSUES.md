# Known issues found by the perf tests

Updated 2026-10-21. The earlier "runs never finish" and "fan-out never finishes" issues are fixed (#331) and
were removed from this file.

## `bootRun` needs the UI build

`./gradlew bootRun` fails at `:ui:pnpmInstall` when pnpm cannot be downloaded (offline/sandbox). Workaround:
`-x :ui:pnpmInstall -x :ui:pnpmBuild -x :ui:pnpmSetup -x :ui:nodeSetup` (API only).
