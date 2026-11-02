// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.AnswerInputCommand;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputOption;
import com.infenia.yukta.plugin.input.InputRejectedException;
import com.infenia.yukta.plugin.input.InputRequest;
import java.util.List;
import java.util.Objects;
import lombok.experimental.UtilityClass;

/**
 * Checks that an answer has the shape its request asks for: an option id for a choice (and for an
 * approval that lists options), data that fits the schema for a structured request, text for a text
 * request. A query takes anything. Sizes are already capped by the command itself. A mismatch is an
 * {@link InputRejectedException} (HTTP 422), and the request stays open.
 */
@UtilityClass
public class AnswerShape {

  /**
   * Checks the answer.
   *
   * @param request the request, as it is now
   * @param answer the answer
   * @throws InputRejectedException if the answer does not have the shape the request asks for
   */
  public static void check(final InputRequest request, final AnswerInputCommand answer) {
    final InputKind kind = request.spec().kind();
    if (kind == InputKind.CHOICE || kind == InputKind.APPROVAL) {
      requireOption(request, answer);
    } else if (kind == InputKind.STRUCTURED) {
      requireSchema(request, answer);
    } else if (kind == InputKind.TEXT) {
      requireText(request, answer);
    }
  }

  /** A choice needs an option; so does an approval, but only when it lists options. */
  private static void requireOption(final InputRequest request, final AnswerInputCommand answer) {
    final List<InputOption> options = request.spec().options();
    final boolean freeApproval = request.spec().kind() == InputKind.APPROVAL && options.isEmpty();
    if (!freeApproval
        && options.stream().noneMatch(option -> option.id().equals(answer.decision()))) {
      throw new InputRejectedException(
          request.id(),
          "The decision is not one of the options: "
              + options.stream().map(InputOption::id).toList());
    }
  }

  private static void requireSchema(final InputRequest request, final AnswerInputCommand answer) {
    final List<String> violations =
        SchemaSubset.violations(
            Objects.requireNonNull(request.spec().responseSchema()), answer.data());
    if (!violations.isEmpty()) {
      throw new InputRejectedException(
          request.id(),
          "The data does not fit the response schema: " + String.join("; ", violations));
    }
  }

  private static void requireText(final InputRequest request, final AnswerInputCommand answer) {
    final String text = answer.text();
    if (text == null || text.isBlank()) {
      throw new InputRejectedException(request.id(), "A text request needs a text answer");
    }
  }
}
