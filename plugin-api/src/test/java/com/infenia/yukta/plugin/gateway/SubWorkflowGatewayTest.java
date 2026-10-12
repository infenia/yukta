// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class SubWorkflowGatewayTest {

  @Test
  void resultCarriesTheChildRunAndAnImmutableCopyOfItsOutput() {
    final List<Object> output = new ArrayList<>(List.of("a"));
    final SubWorkflowGateway.Result result = new SubWorkflowGateway.Result("e1", output);
    output.add("b");

    assertThat(result.executionId()).isEqualTo("e1");
    assertThat(result.results()).containsExactly("a");
    assertThatThrownBy(() -> result.results().add("c"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void limitsAndKeysArePublished() {
    final SubWorkflowGateway gateway =
        (session, workflow, payload, parent, depth) ->
            Mono.just(new SubWorkflowGateway.Result(workflow, List.of(payload)));

    assertThat(SubWorkflowGateway.MAX_DEPTH).isPositive();
    assertThat(SubWorkflowGateway.DEPTH_KEY).startsWith("yukta.");
    assertThat(SubWorkflowGateway.PARENT_KEY).startsWith("yukta.");
    assertThat(gateway.run("s", "w", Map.of(), "p", 1).block()).isNotNull();
  }
}
