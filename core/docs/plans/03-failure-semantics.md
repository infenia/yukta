<!--
SPDX-License-Identifier: Apache-2.0
SPDX-FileCopyrightText: 2026 Infenia Private Limited
-->

# 03 Failure semantics (4→5)

Gaps: no circuit/fail-budget, retry is per-message only, no retry-on-condition.
- `engine.retry.retryOn` / `noRetryOn` exception-class lists (default keeps `IllegalArgumentException` excluded) in `NodePolicy`.
- `engine.failureBudget {maxFailures, window}` for `onError: CONTINUE/ROUTE` → node fails when exceeded (poison-stream protection).
- Dead-letter message carries attempt count, exception class, timestamp (assert in test).
- Retry-node on the control gateway (re-run failed node with recorded input via `RestartPlan`) — closes the "partial".
- Validator rejects inconsistent combos; tests per option.

## Done when
`./gradlew spotlessApply :core:check` is green, the guide row for this area reads 5 with no "partial"/"not built" cells, and core/CLAUDE.md is updated.
