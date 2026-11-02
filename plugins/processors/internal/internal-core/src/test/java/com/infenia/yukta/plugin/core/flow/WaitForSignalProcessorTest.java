// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.flow.InputFakes.Gateway;
import com.infenia.yukta.plugin.core.flow.InputFakes.ListStore;
import com.infenia.yukta.plugin.input.InputExpiredException;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.TimeoutAction;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests for WaitForSignalProcessor on the input gateway. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class WaitForSignalProcessorTest {

  private final Message<?> input = DefaultMessage.create(UUID.randomUUID(), "work");

  private Flux<Message<?>> run(final Gateway gateway, final Map<String, Object> config) {
    return run(new WaitForSignalProcessor(InputFakes.provider(gateway)), config);
  }

  private Flux<Message<?>> run(
      final WaitForSignalProcessor processor, final Map<String, Object> config) {
    return processor.process(Flux.just(input), config).contextWrite(InputFakes.NODE);
  }

  @Test
  void approve_releasesTheMessageTaggedWithTheDecision() {
    final Gateway gateway = InputFakes.answering("approve", "fine");
    StepVerifier.create(run(gateway, Map.of("signal", "approval")))
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
  void theRequestIsHighRiskApprovalWithoutOptions() {
    final Gateway gateway = InputFakes.answering("go", null);
    StepVerifier.create(run(gateway, Map.of("signal", "approval", "timeoutSeconds", 90)))
        .assertNext(out -> assertThat(out.metadata()).containsEntry("signal.data", ""))
        .verifyComplete();
    final var spec = gateway.asked.getFirst();
    assertThat(spec.kind()).isEqualTo(InputKind.APPROVAL);
    assertThat(spec.risk()).isEqualTo(InputRisk.HIGH);
    assertThat(spec.options()).isEmpty();
    assertThat(spec.audience().isCurrentTier()).isTrue();
    assertThat(spec.ttl()).isEqualTo(Duration.ofSeconds(90));
    assertThat(spec.onTimeout()).isEqualTo(TimeoutAction.FAIL);
    assertThat(spec.context()).containsEntry("signal", "approval");
    assertThat(spec.prompt()).contains("approval");
    assertThat(spec.key()).hasSize(64);
  }

  @Test
  void sameMessageAndNameGiveTheSameKey() {
    final Gateway gateway = InputFakes.answering("approve", null);
    run(gateway, Map.of("signal", "a")).blockLast();
    run(gateway, Map.of("signal", "a")).blockLast();
    run(gateway, Map.of("signal", "b")).blockLast();
    assertThat(gateway.asked.get(0).key()).isEqualTo(gateway.asked.get(1).key());
    assertThat(gateway.asked.get(0).key()).isNotEqualTo(gateway.asked.get(2).key());
  }

  @Test
  void reject_failsByDefault_andDropsWhenAsked() {
    final Gateway gateway = InputFakes.answering("reject", "no");
    StepVerifier.create(run(gateway, Map.of("signal", "approval")))
        .verifyErrorSatisfies(
            e ->
                assertThat(e).isInstanceOf(IllegalStateException.class).hasMessageContaining("no"));
    StepVerifier.create(run(gateway, Map.of("signal", "approval", "onReject", "CONTINUE")))
        .verifyComplete();
  }

  @Test
  void timeout_failsByDefault_andContinuesWhenAsked() {
    final Gateway gateway = new Gateway(_ -> Mono.error(new InputExpiredException("r", "expired")));
    StepVerifier.create(run(gateway, Map.of("signal", "approval", "timeoutSeconds", 5)))
        .verifyErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No 'approval' signal"));
    StepVerifier.create(
            run(
                gateway,
                Map.of("signal", "approval", "timeoutSeconds", 5, "onTimeout", "CONTINUE")))
        .assertNext(out -> assertThat(out.metadata()).containsEntry("signal.decision", "timeout"))
        .verifyComplete();
  }

  @Test
  void withoutGatewayTheNodeFailsClearly() {
    StepVerifier.create(
            run(new WaitForSignalProcessor(InputFakes.provider(null)), Map.of("signal", "a")))
        .verifyErrorSatisfies(e -> assertThat(e).hasMessageContaining("No InputGateway"));
  }

  @Test
  void validate_refusesBadConfig() {
    final var processor = new WaitForSignalProcessor(InputFakes.provider(null));
    assertThatThrownBy(() -> processor.validateConfig(Map.of()).block())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> processor.validateConfig(Map.of("signal", "a".repeat(129))).block())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> processor.validateConfig(Map.of("signal", "a", "timeoutSeconds", 0)).block())
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(processor.validateConfig(Map.of("signal", "a")).block()).isNull();
  }

  @Test
  void defaultTimeoutOutlastsTheWait_andTheTypeIsRegistered() {
    final var processor = new WaitForSignalProcessor(InputFakes.provider(null));
    assertThat(processor.getDefaultTimeout(Map.of("signal", "a", "timeoutSeconds", 10)))
        .isEqualTo(Duration.ofSeconds(40));
    assertThat(processor.getType()).isEqualTo("WAIT_FOR_SIGNAL");
    assertThat(processor.requiresControlPlane()).isTrue();
    assertThat(processor.getConfigSchema())
        .extracting("name")
        .containsExactly("signal", "timeoutSeconds", "onTimeout", "onReject");
    assertThat(processor.describe().description()).isNotBlank();
  }

  @Test
  void onControlSignal_isNotUsedAnyMore() {
    final var processor = new WaitForSignalProcessor(InputFakes.provider(null));
    assertThat(processor.onControlSignal(input).block()).isNull();
  }

  @Test
  void answerIsRememberedWhenTheSameMessageResumes() {
    final ListStore store = new ListStore();
    final Gateway first = InputFakes.answering("approve", "ok");
    new WaitForSignalProcessor(store, InputFakes.provider(first));
    run(new WaitForSignalProcessor(store, InputFakes.provider(first)), Map.of("signal", "a"))
        .blockLast();
    assertThat(store.kept).hasSize(1);

    final Gateway second = InputFakes.answering("reject", "late");
    StepVerifier.create(
            run(
                new WaitForSignalProcessor(store, InputFakes.provider(second)),
                Map.of("signal", "a")))
        .assertNext(out -> assertThat(out.metadata()).containsEntry("signal.decision", "approve"))
        .verifyComplete();
    assertThat(second.asked).isEmpty();
  }

  @Test
  void failingStoreNeverFailsTheWait() {
    final ListStore store = new ListStore();
    store.failing = true;
    final var processor =
        new WaitForSignalProcessor(
            store, InputFakes.provider(InputFakes.answering("approve", "x")));
    StepVerifier.create(run(processor, Map.of("signal", "a"))).expectNextCount(1).verifyComplete();
  }

  @Test
  void recordsOfOtherMessagesOrNamesAreNotReplayed() {
    final ListStore store = new ListStore();
    store.kept.add(DefaultMessage.create(UUID.randomUUID(), "not a record"));
    store.kept.add(
        DefaultMessage.create(UUID.randomUUID(), Map.of("messageId", "other", "name", "a")));
    store.kept.add(
        DefaultMessage.create(UUID.randomUUID(), Map.of("messageId", input.id(), "name", "b")));
    final Gateway gateway = InputFakes.answering("approve", null);
    run(new WaitForSignalProcessor(store, InputFakes.provider(gateway)), Map.of("signal", "a"))
        .blockLast();
    assertThat(gateway.asked).hasSize(1);
  }

  @Test
  void blankSignalAndTextTimeoutAreRefused() {
    final var processor = new WaitForSignalProcessor(InputFakes.provider(null));
    assertThatThrownBy(() -> processor.validateConfig(Map.of("signal", " ")).block())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> processor.validateConfig(Map.of("signal", "a", "timeoutSeconds", "soon")).block())
        .isInstanceOf(IllegalArgumentException.class);
  }
}
