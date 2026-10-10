<!--
SPDX-License-Identifier: Apache-2.0
SPDX-FileCopyrightText: 2026 Infenia Private Limited
-->

# 07 Simplicity (4→5)

- Run `ponytail-audit` on core; delete dead code/duplicate helpers found; collapse overlapping `Node*` stream helpers where audit confirms.
- Split classes > ~400 lines (`WorkflowOrchestrator` 429, check others) by extracting cohesive collaborators; no behavior change.
- Update guide package table and line/test stats. Must come after 01–06.

## Done when
`./gradlew spotlessApply :core:check` is green, the guide row for this area reads 5 with no "partial"/"not built" cells, and core/CLAUDE.md is updated.
