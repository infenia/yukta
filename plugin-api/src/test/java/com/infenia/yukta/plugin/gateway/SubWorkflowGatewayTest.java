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
  void resultWithoutStatus_isSuccessThatReachedNoOutcome() {
    final SubWorkflowGateway.Result result = new SubWorkflowGateway.Result("e1", List.of());

    assertThat(result.status()).isEqualTo("SUCCESS");
    assertThat(result.outcome()).isNull();
    assertThat(result.reason()).isNull();
  }

  @Test
  void childFailed_keepsTheCausesMessage_andNamesTheRunAndHowItEnded() {
    final IllegalStateException cause = new IllegalStateException("boom");
    final SubWorkflowGateway.ChildFailed failed =
        new SubWorkflowGateway.ChildFailed("e2", "TIMED_OUT", cause);

    assertThat(failed).hasMessage("boom").hasCause(cause);
    assertThat(failed.executionId()).isEqualTo("e2");
    assertThat(failed.status()).isEqualTo("TIMED_OUT");
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
