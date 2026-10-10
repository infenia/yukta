<!--
SPDX-License-Identifier: Apache-2.0
SPDX-FileCopyrightText: 2026 Infenia Private Limited
-->

# 04 Control plane (4→5)

Gaps: runtime skip rejected; breakpoints/limits/priority only Java API; retry-node = restart.
- Runtime skip: valve switches node to pass-through (`SKIPPED`) while idle; reject only when mid-element (clear reason).
- Add gateway commands in `service/control` (command enum + FIFO path + audit): `SET_BREAKPOINT/CLEAR_BREAKPOINT` (predicate from a safe declarative form: header/field equals), `SET_LIMITS`, `SET_PRIORITY` (re-sorts queue), `RETRY_NODE`.
- All recorded as `CONTROL_APPLIED/REJECTED`; unit + E2E tests.
- Guide control table: all ✓, note REST/MCP exposure as a separate follow-up.

## Done when
`./gradlew spotlessApply :core:check` is green, the guide row for this area reads 5 with no "partial"/"not built" cells, and core/CLAUDE.md is updated.
