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

  @Test
  void nullPortsBecomeEmpty() {
    assertThat(new WorkflowValidationResult(true, null, null).outputPorts()).isEmpty();
  }

  @Test
  void portsAreCopied() {
    final Map<String, List<String>> ports = new ConcurrentHashMap<>(Map.of("n1", List.of("a")));
    final WorkflowValidationResult result = new WorkflowValidationResult(false, "bad", ports);
    ports.put("n2", List.of());

    assertThat(result.outputPorts()).containsOnlyKeys("n1");
    assertThatThrownBy(() -> result.outputPorts().put("x", List.of()))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
