// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.type.TriggerPlugin;
import com.infenia.yukta.service.plugin.PluginRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

@NoArgsConstructor
@SuppressWarnings({"unchecked", "PMD.AvoidDuplicateLiterals"})
class NodeRunnerTest {

  private final TriggerPlugin trigger = mock(TriggerPlugin.class);
  private final RecordingHandler traced = new RecordingHandler();
  private final NodeRunner runner = buildRunner();

  private NodeRunner buildRunner() {
    when(trigger.getType()).thenReturn("TRIGGER");
    return new NodeRunner(
        new PluginRegistry(
            List.of(
                new FakePlugin("UPPER", new AtomicInteger()),
                new FakePlugin("BOOM", new AtomicInteger()),
                trigger)),
        Set.of("UPPER", "BOOM", "TRIGGER"),
        new ObjectMapper(),
        traced.registry(),
        1);
  }

  private NodeFrame answerTo(final Flux<NodeFrame> frames) {
    return runner.run(frames).blockFirst();
  }

  private static void assertRefused(final NodeFrame answer, final String why) {
    assertThat(answer.kind()).isEqualTo(NodeFrame.Kind.ERROR);
    assertThat(answer.errorClass()).isEqualTo(IllegalArgumentException.class.getName());
    assertThat(answer.errorMessage()).contains(why);
  }

  @Test
  void firstFrameNotStart_isRefused() {
    assertRefused(answerTo(Flux.just(NodeFrame.complete())), "must be START");
    assertRefused(answerTo(Flux.empty()), "must be START");
  }

  @Test
  void unagreedOrNonProcessorType_isRefused() {
    assertRefused(
        answerTo(Flux.just(NodeFrame.start("e", "n", "PROCESS_EXECUTOR", Map.of(), Map.of()))),
        "does not run");
    assertRefused(
        answerTo(Flux.just(NodeFrame.start("e", "n", "TRIGGER", Map.of(), Map.of()))),
        "not a processor");
  }

  @Test
  void attempt_continuesTheTraceFromStart_andEndsWithIt() {
    final NodeFrame start =
        NodeFrame.start(
            "e", "n", "UPPER", Map.of(), Map.of("traceparent", RecordingHandler.TRACEPARENT));

    StepVerifier.create(runner.run(Flux.just(start, NodeFrame.complete())))
        .assertNext(frame -> assertThat(frame.kind()).isEqualTo(NodeFrame.Kind.COMPLETE))
        .verifyComplete();

    assertThat(traced.started).hasSize(1);
    final ReceiverContext<Object> received = (ReceiverContext<Object>) traced.started.getFirst();
    assertThat(received.getName()).isEqualTo("yukta.worker.attempt");
    assertThat(received.getGetter().get(received.getCarrier(), "traceparent"))
        .isEqualTo(RecordingHandler.TRACEPARENT);
    assertThat(received.getGetter().get(received.getCarrier(), "tracestate")).isNull();
    assertThat(traced.stopped).containsExactly(received);
    assertThat(traced.failed).isEmpty();
  }

  @Test
  void failedAttempt_isRecordedOnItsObservation() {
    StepVerifier.create(
            runner.run(
                Flux.just(
                    NodeFrame.start("e", "n", "BOOM", Map.of(), Map.of()), NodeFrame.complete())))
        .assertNext(frame -> assertThat(frame.kind()).isEqualTo(NodeFrame.Kind.ERROR))
        .verifyComplete();

    assertThat(traced.failed).hasSize(1);
    assertThat(traced.failed.getFirst().getError()).hasMessage("boom");
  }

  @Test
  void attemptBeyondCapacity_isRefusedRetryably_untilOneSlotIsFree() {
    final NodeFrame start = NodeFrame.start("e", "n", "UPPER", Map.of(), Map.of());
    final reactor.core.Disposable first =
        runner.run(Flux.concat(Flux.just(start), Flux.never())).subscribe();
    assertThat(runner.running()).isEqualTo(1);

    final NodeFrame refused = answerTo(Flux.just(start, NodeFrame.complete()));
    assertThat(refused.kind()).isEqualTo(NodeFrame.Kind.ERROR);
    assertThat(refused.errorClass()).isEqualTo(IllegalStateException.class.getName());
    assertThat(refused.errorMessage()).contains("full");
    assertThat(runner.running()).isEqualTo(1);

    first.dispose();
    assertThat(runner.running()).isZero();
    assertThat(answerTo(Flux.just(start, NodeFrame.complete())).kind())
        .isEqualTo(NodeFrame.Kind.COMPLETE);
  }

  @Test
  void startWithoutConfig_runsWithEmptyOne() {
    final NodeFrame start =
        new NodeFrame(NodeFrame.Kind.START, "e", "n", "UPPER", null, null, null, null, null);

    StepVerifier.create(runner.run(Flux.just(start, NodeFrame.complete())))
        .assertNext(frame -> assertThat(frame.kind()).isEqualTo(NodeFrame.Kind.COMPLETE))
        .verifyComplete();
  }

  @Test
  void types_listsFailureAndCauseClasses_stopsOnCycle() {
    final IllegalStateException first = new IllegalStateException("a");
    final java.io.IOException second = new java.io.IOException("b", first);
    first.initCause(second);

    assertThat(NodeRunner.types(first))
        .contains(
            IllegalStateException.class.getName(),
            RuntimeException.class.getName(),
            java.io.IOException.class.getName())
        .doesNotHaveDuplicates();
  }
}
