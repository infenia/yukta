// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.diagnosis;

/** How much a run log search returns, from cheapest to most detailed. */
public enum LogSearchMode {
  /** Lines, errors, warnings and matches per node; no lines. */
  COUNT,
  /** Only the nodes with a match, with their counts; no lines. */
  NODES,
  /** The matching lines (with their context), within the match and byte budgets. */
  LINES
}
