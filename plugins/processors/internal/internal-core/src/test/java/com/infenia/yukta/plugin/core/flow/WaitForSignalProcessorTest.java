// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** Tests for WaitForSignalProcessor. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods", "PMD.UseConcurrentHashMap"})
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

    assertThat(answer(signal("approve", null))).isEqualTo("NOT_WAITING");
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
    assertThat(processor.requiresControlPlane()).isTrue();
    assertThat(processor.describe().description()).contains("signal");
    assertThat(processor.getConfigSchema()).extracting("name").contains("signal");
  }

  @Test
  void theDefaultConstructor_usesTheSharedWaits() {
    final WaitForSignalProcessor shared = new WaitForSignalProcessor();

    assertThat(shared.describe()).isNotNull();
  }

  /** A checkpoint store that keeps every message in a list, as the file store does. */
  private static final class ListStore implements NodeCheckpointStore {
    private final List<Message<?>> kept = new ArrayList<>();
    private boolean failing;

    @Override
    public Mono<Void> save(final String executionId, final String nodeId, final Message<?> m) {
      return failing
          ? Mono.error(new IllegalStateException("disk full"))
          : Mono.fromRunnable(() -> kept.add(m));
    }

    @Override
    public Mono<Message<?>> get(final String executionId, final String nodeId) {
      return getAll(executionId, nodeId).takeLast(1).next();
    }

    @Override
    public Flux<Message<?>> getAll(final String executionId, final String nodeId) {
      return failing
          ? Flux.error(new IllegalStateException("disk gone"))
          : Flux.fromIterable(List.copyOf(kept));
    }

    @Override
    public void clear(final String executionId) {
      kept.clear();
    }
  }

  private Flux<Message<?>> runWith(
      final WaitForSignalProcessor proc, final Message<?> in, final Map<String, Object> config) {
    return proc.process(Flux.just(in), config)
        .contextWrite(Context.of("executionId", "e1", "nodeId", "n1"));
  }

  @Test
  void answerIsRememberedWhenTheSameMessageResumes() {
    final ListStore store = new ListStore();
    final WaitForSignalProcessor first =
        new WaitForSignalProcessor(new com.infenia.yukta.plugin.util.SignalWaits(), store);
    StepVerifier.create(runWith(first, input, Map.of("signal", "approval")))
        .then(() -> first.onControlSignal(signal("approve", "fine")).block())
        .expectNextCount(1)
        .verifyComplete();

    // after a restart: new waits, the same checkpoint store, the same message id
    final WaitForSignalProcessor resumed =
        new WaitForSignalProcessor(new com.infenia.yukta.plugin.util.SignalWaits(), store);
    StepVerifier.create(runWith(resumed, input, Map.of("signal", "approval")))
        .assertNext(out -> assertThat(out.metadata()).containsEntry("signal.decision", "approve"))
        .verifyComplete();

    // another message, or another signal name, still waits (and here times out)
    final Message<?> other = DefaultMessage.create(UUID.randomUUID(), "other");
    StepVerifier.create(runWith(resumed, other, Map.of("signal", "approval", "timeoutSeconds", 1)))
        .expectError(IllegalStateException.class)
        .verify(Duration.ofSeconds(5));
    StepVerifier.create(
            runWith(resumed, input, Map.of("signal", "other-name", "timeoutSeconds", 1)))
        .expectError(IllegalStateException.class)
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void timeoutIsNotRememberedAndUnreadableEntryIsIgnored() {
    final ListStore store = new ListStore();
    store.kept.add(DefaultMessage.create(UUID.randomUUID(), "not a map"));
    final WaitForSignalProcessor proc =
        new WaitForSignalProcessor(new com.infenia.yukta.plugin.util.SignalWaits(), store);

    StepVerifier.create(
            runWith(
                proc,
                input,
                Map.of("signal", "approval", "timeoutSeconds", 1, "onTimeout", "CONTINUE")))
        .assertNext(out -> assertThat(out.metadata()).containsEntry("signal.decision", "timeout"))
        .verifyComplete();

    assertThat(store.kept).hasSize(1);
  }

  @Test
  void failingStoreNeverFailsTheWait() {
    final ListStore store = new ListStore();
    store.failing = true;
    final WaitForSignalProcessor proc =
        new WaitForSignalProcessor(new com.infenia.yukta.plugin.util.SignalWaits(), store);

    StepVerifier.create(runWith(proc, input, Map.of("signal", "approval")))
        .then(() -> proc.onControlSignal(signal("approve", "ok")).block())
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void theSpringConstructorTakesTheCheckpointStore() {
    assertThat(new WaitForSignalProcessor(new ListStore())).isNotNull();
  }
}
