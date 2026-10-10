<!--
SPDX-License-Identifier: Apache-2.0
SPDX-FileCopyrightText: 2026 Infenia Private Limited
-->

# Core score-card plans (target: 5/5 everywhere)

Context: `core/docs/core-module-guide.html` (DATA.score) rates 9 areas. At the start only Streaming and Status were 5 and the rest 3.5–4.5; each plan below raises its own row to 5 when it lands. Goal: every row 5/5, done as **one plan and one branch/PR per area**, each ending with the guide's score row updated. Scope decisions: Restart gets **durable checkpoints + resume** (reverses the "memory only" non-goal); **core module only** (no web/mcp changes — Java-only features get exposed on the core control gateway, REST/MCP wiring stays a follow-up and is noted in the guide).

Implement in order; one branch/PR per plan. Each ends by updating the guide score row.

- [01 Restart and recovery (3.5→5) — biggest](01-restart-recovery.md)
- [02 Time semantics (3.5→5)](02-time-semantics.md)
- [03 Failure semantics (4→5)](03-failure-semantics.md)
- [04 Control plane (4→5)](04-control-plane.md)
- [05 Governance (4→5)](05-governance.md)
- [06 Observability (2.5→5, but guide says 3.5 — tracing already exists)](06-observability.md)
- [07 Simplicity (4→5)](07-simplicity.md)
- [08 Streaming & Status (already 5)](08-streaming-status.md)
