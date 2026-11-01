// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link WorkflowValidationResult}. */
@NoArgsConstructor
class WorkflowValidationResultTest {

  private static final String MESSAGE = "bad";

  @Test
  void nullPortsBecomeEmpty() {
    assertThat(new WorkflowValidationResult(true, null, null, null).outputPorts()).isEmpty();
    assertThat(new WorkflowValidationResult(true, null, null, null).warnings()).isEmpty();
  }

  @Test
  void messageBecomesTheOnlyProblem() {
    final WorkflowValidationResult result =
        new WorkflowValidationResult(false, MESSAGE, null, null);

    assertThat(result.problems()).containsExactly(MESSAGE);
  }

  @Test
  void nullProblemsBecomeEmptyEvenWhenMessageIsSet() {
    final WorkflowValidationResult result =
        new WorkflowValidationResult(false, MESSAGE, Map.of(), null, List.of());

    assertThat(result.message()).isEqualTo(MESSAGE);
    assertThat(result.problems()).isEmpty();
    assertThat(result.warnings()).isEmpty();
  }

  @Test
  void portsAreCopied() {
    final Map<String, List<String>> ports = new ConcurrentHashMap<>(Map.of("n1", List.of("a")));
    final WorkflowValidationResult result =
        new WorkflowValidationResult(false, MESSAGE, ports, List.of());
    ports.put("n2", List.of());

    assertThat(result.outputPorts()).containsOnlyKeys("n1");
    assertThatThrownBy(() -> result.outputPorts().put("x", List.of()))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
