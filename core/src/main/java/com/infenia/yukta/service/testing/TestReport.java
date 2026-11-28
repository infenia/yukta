// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.testing;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of a workflow test.
 *
 * @param passed whether every assertion held
 * @param status the final run status
 * @param assertions one entry per assertion
 */
public record TestReport(boolean passed, String status, List<Check> assertions) {

  /** Copies the list, so the report cannot be changed through it. */
  public TestReport {
    assertions = List.copyOf(assertions);
  }

  /**
   * One assertion.
   *
   * @param name what was checked
   * @param passed whether it held
   * @param detail the actual value when it did not
   */
  public record Check(
      String name,
      boolean passed,
      @Schema(description = "The actual value when the assertion did not hold", nullable = true)
          @Nullable String detail) {}
}
