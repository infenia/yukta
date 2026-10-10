<!--
SPDX-License-Identifier: Apache-2.0
SPDX-FileCopyrightText: 2026 Infenia Private Limited
-->

# 08 Streaming & Status (already 5)

- Regression guards only: keep benchmark test (20-node chain, 10k msgs) in `check`; add status-transition property-style test (no illegal transitions). Quick final PR.

## Done when
`./gradlew spotlessApply :core:check` is green, the guide row for this area reads 5 with no "partial"/"not built" cells, and core/CLAUDE.md is updated.
