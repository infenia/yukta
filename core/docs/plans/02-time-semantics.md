<!--
SPDX-License-Identifier: Apache-2.0
SPDX-FileCopyrightText: 2026 Infenia Private Limited
-->

# 02 Time semantics (3.5→5)

Gap: no node wall-clock deadline; expression timeouts unknown at compile time.
- Add `engine.deadlineMs` to `NodePolicy` (parsing) + `NodePolicies` (enforce, pause-aware via existing `NodeActivity` clock, not reset by heartbeat); validator: must be ≥ idle/element timeouts.
- Resolve expression timeouts at node start (first input) instead of falling back to the default; keep default as last resort.
- Schedule-to-start timeout: `yukta.execution.queue-timeout` → QUEUED start refused with 429/TIMED_OUT.
- Tests: deadline fires despite heartbeats, not during pause, expression timeout applied.
- Guide: time table row "Node wall-clock deadline" → Done; drop "not built on purpose".

## Done when
`./gradlew spotlessApply :core:check` is green, the guide row for this area reads 5 with no "partial"/"not built" cells, and core/CLAUDE.md is updated.
