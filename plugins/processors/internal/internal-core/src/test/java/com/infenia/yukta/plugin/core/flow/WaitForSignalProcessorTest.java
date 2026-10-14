// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** Tests for WaitForSignalProcessor. */
@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class WaitForSignalProcessorTest {

  private final WaitForSignalProcessor processor =
      new WaitForSignalProcessor(new com.infenia.yukta.plugin.util.SignalWaits());
  private final Message<?> input = DefaultMessage.create(UUID.randomUUID(), "work");

  private static Message<?> signal(final String decision, final String data) {
    final Map<String, Object> body = new HashMap<>();
    body.put("executionId", "e1");
    body.put("nodeId", "n1");
    body.put("name", "approval");
    body.put("decision", decision);
    body.put("data", data);
    return DefaultMessage.create(UUID.randomUUID(), body);
  }

  private Flux<Message<?>> run(final Map<String, Object> config) {
    return processor
        .process(Flux.just(input), config)
        .contextWrite(Context.of("executionId", "e1", "nodeId", "n1"));
  }

  private String answer(final Message<?> signal) {
    return String.valueOf(
        java.util.Objects.requireNonNull(processor.onControlSignal(signal).block()).payload());
  }

  @Test
  void approve_releasesTheMessageTaggedWithTheDecision() {
    StepVerifier.create(run(Map.of("signal", "approval")))
        .then(() -> assertThat(answer(signal("approve", "fine"))).isEqualTo("ACCEPTED"))
        .assertNext(
            out -> {
              assertThat(out.metadata())
                  .containsEntry("signal.name", "approval")
                  .containsEntry("signal.decision", "approve")
                  .containsEntry("signal.data", "fine");
              assertThat(out.payload()).isEqualTo("work");
            })
        .verifyComplete();
  }

  @Test
  void signalWithoutWaiting_isKeptForTheWait() {
    assertThat(answer(signal("approve", null))).isEqualTo("QUEUED");
    assertThat(processor.onControlSignal(DefaultMessage.create(UUID.randomUUID(), "text")).block())
        .isNull();
  }

  @Test
  void reject_failsByDefault_andDropsWhenAsked() {
    StepVerifier.create(run(Map.of("signal", "approval")))
        .then(() -> answer(signal("reject", "no")))
        .verifyErrorSatisfies(
            e ->
                assertThat(e).isInstanceOf(IllegalStateException.class).hasMessageContaining("no"));

    StepVerifier.create(run(Map.of("signal", "approval", "onReject", "CONTINUE")))
        .then(() -> answer(signal("reject", null)))
        .verifyComplete();
  }

  @Test
  void timeout_failsByDefault_andContinuesWhenAsked() {
    StepVerifier.withVirtualTime(() -> run(Map.of("signal", "approval", "timeoutSeconds", 5)))
        .thenAwait(Duration.ofSeconds(6))
        .verifyError(IllegalStateException.class);

    StepVerifier.withVirtualTime(
            () -> run(Map.of("signal", "approval", "timeoutSeconds", 5, "onTimeout", "CONTINUE")))
        .thenAwait(Duration.ofSeconds(6))
        .assertNext(out -> assertThat(out.metadata()).containsEntry("signal.decision", "timeout"))
        .verifyComplete();
  }

  @Test
  void waitIsForgottenWhenItEnds() {
    StepVerifier.create(run(Map.of("signal", "approval")))
        .then(() -> answer(signal("approve", null)))
        .expectNextCount(1)
        .verifyComplete();

    assertThat(answer(signal("approve", null))).isEqualTo("QUEUED");
  }

  @Test
  void configIsValidated_andTheEngineTimeoutOutlastsTheWait() {
    StepVerifier.create(processor.validateConfig(Map.of("signal", "ok"))).verifyComplete();
    assertThatThrownBy(() -> processor.validateConfig(Map.of()).block())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                processor.validateConfig(Map.of("signal", "ok", "timeoutSeconds", "soon")).block())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> processor.validateConfig(Map.of("signal", " ")).block())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> processor.validateConfig(Map.of("signal", "ok", "timeoutSeconds", 0)).block())
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(processor.getDefaultTimeout(Map.of("signal", "ok", "timeoutSeconds", 60)))
        .isEqualTo(Duration.ofSeconds(90));
    assertThat(processor.getDefaultTimeout(Map.of("signal", "ok")))
        .isEqualTo(Duration.ofSeconds(3630));
  }

  @Test
  void typeAndDescription() {
    assertThat(processor.getType()).isEqualTo("WAIT_FOR_SIGNAL");
    assertThat(processor.describe().description()).contains("signal");
    assertThat(processor.getConfigSchema()).extracting("name").contains("signal");
  }

  @Test
  void theDefaultConstructor_usesTheSharedWaits() {
    final WaitForSignalProcessor shared = new WaitForSignalProcessor();

    assertThat(shared.describe()).isNotNull();
  }
}
