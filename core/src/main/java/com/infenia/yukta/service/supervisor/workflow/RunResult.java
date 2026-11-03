// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.workflow;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * How a supervisor workflow run ended.
 *
 * @param status {@code SUCCESS}, {@code FAILED}, {@code TIMEOUT} or {@code SKIPPED} (refused before
 *     it started: depth or no free slot)
 * @param outcome the OUTCOME_TERMINAL outcome ({@code SUCCESS}, {@code RETRY}, {@code ESCALATE},
 *     {@code FAILED}), or {@code null}
 * @param reason the outcome's reason, or {@code null}
 * @param results the terminal payloads
 */
public record RunResult(
    String status, @Nullable String outcome, @Nullable String reason, List<Object> results) {

  /** Keeps an immutable copy. */
  public RunResult {
    results = List.copyOf(results);
  }

  /**
   * A run that did not produce anything.
   *
   * @param status the status
   * @return the result
   */
  public static RunResult none(final String status) {
    return new RunResult(status, null, null, List.of());
  }
}
