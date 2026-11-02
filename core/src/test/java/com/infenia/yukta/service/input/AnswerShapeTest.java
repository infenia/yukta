// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.AnswerInputCommand;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputOption;
import com.infenia.yukta.plugin.input.InputRejectedException;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRisk;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** An answer has the shape its request asks for. */
@NoArgsConstructor
class AnswerShapeTest {

  private static InputRequest request(
      final InputKind kind, final List<InputOption> options, final String schema) {
    return InputTestSupport.request(
        "r1",
        InputTestSupport.spec(
            "k", kind, InputRisk.LOW, Audience.CURRENT_TIER, options, schema, Map.of()));
  }

  private static AnswerInputCommand answer(final String decision) {
    return new AnswerInputCommand("exec", "r1", null, decision, null, Map.of(), null, null);
  }

  @Test
  void anApprovalWithoutOptionsTakesAnyDecision() {
    assertThatCode(
            () ->
                AnswerShape.check(request(InputKind.APPROVAL, List.of(), null), answer("whatever")))
        .doesNotThrowAnyException();
  }

  @Test
  void anApprovalThatListsOptionsNeedsOneOfThem() {
    final InputRequest request =
        request(
            InputKind.APPROVAL,
            List.of(new InputOption("approve"), new InputOption("reject")),
            null);

    assertThatCode(() -> AnswerShape.check(request, answer("reject"))).doesNotThrowAnyException();
    assertThatThrownBy(() -> AnswerShape.check(request, answer("maybe")))
        .isInstanceOf(InputRejectedException.class)
        .hasMessageContaining("[approve, reject]");
  }

  @Test
  void queryTakesTextDataOrBoth() {
    final InputRequest request = request(InputKind.QUERY, List.of(), null);
    final AnswerInputCommand both =
        new AnswerInputCommand("exec", "r1", null, "ok", "text", Map.of("a", 1), null, null);

    assertThatCode(() -> AnswerShape.check(request, answer("ok"))).doesNotThrowAnyException();
    assertThatCode(() -> AnswerShape.check(request, both)).doesNotThrowAnyException();
  }
}
