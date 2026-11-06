// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The result of checking a draft supervisor.
 *
 * @param valid whether saving would be accepted
 * @param errors the problems, empty when valid
 */
@Schema(description = "The result of checking a draft supervisor")
public record SupervisorValidation(boolean valid, List<FieldIssue> errors) {

  /**
   * One problem.
   *
   * @param field the field, such as {@code settings.maxRisk}
   * @param message what is wrong
   */
  public record FieldIssue(String field, String message) {}
}
