// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The result of a check that changes nothing.
 *
 * @param valid whether nothing is wrong
 * @param problems each as {@code field: message}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorCheck(boolean valid, List<String> problems) {

  /** Keeps an immutable copy of the problems. */
  public SupervisorCheck {
    problems = List.copyOf(problems);
  }
}
