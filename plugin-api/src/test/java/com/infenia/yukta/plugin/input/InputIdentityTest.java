// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class InputIdentityTest {

  @Test
  void readsTheKeysTheEngineWrites() {
    final Context context =
        Context.of(
            "sessionId", "s", "workflowId", "w", "executionId", "e", "nodeId", "n", "payload", "p");
    assertThat(InputIdentity.from(context)).isEqualTo(new InputIdentity("s", "w", "e", "n"));
  }

  @Test
  void worksInsideReactiveChain() {
    StepVerifier.create(
            Mono.deferContextual(ctx -> Mono.just(InputIdentity.from(ctx).nodeId()))
                .contextWrite(
                    Context.of(
                        "sessionId", "s", "workflowId", "w", "executionId", "e", "nodeId", "n")))
        .expectNext("n")
        .verifyComplete();
  }

  @Test
  void missingKeyNamesItself() {
    assertThatThrownBy(() -> InputIdentity.from(Context.empty()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No 'sessionId' in the Reactor context");
    assertThatThrownBy(
            () ->
                InputIdentity.from(
                    Context.of("sessionId", "s", "workflowId", "w", "executionId", "e")))
        .hasMessageContaining("'nodeId'");
  }

  @Test
  void blankOrForeignValueIsMissing() {
    assertThatThrownBy(
            () ->
                InputIdentity.from(
                    Context.of(
                        "sessionId", " ", "workflowId", "w", "executionId", "e", "nodeId", "n")))
        .hasMessageContaining("'sessionId'");
    assertThatThrownBy(
            () ->
                InputIdentity.from(
                    Context.of(
                        "sessionId", 7, "workflowId", "w", "executionId", "e", "nodeId", "n")))
        .hasMessageContaining("'sessionId'");
  }

  @Test
  void theConstructorChecksTheParts() {
    assertThatThrownBy(() -> new InputIdentity("s", "w", "e", " "))
        .hasMessage("nodeId must not be blank");
  }
}
