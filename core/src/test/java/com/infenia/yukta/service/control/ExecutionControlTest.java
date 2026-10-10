// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.CommentRequired",
  "PMD.CommentDefaultAccessModifier",
  "PMD.TooManyMethods"
})
@NoArgsConstructor
class ExecutionControlTest {

  static final Message<?> TEST_MESSAGE = mock(Message.class);

  @Test
  void testPayloadImmutability() {
    final Map<String, Object> originalPayload = Map.of("data", "value");
    final ExecutionControl ctrl =
        new ExecutionControl(
            "s1",
            "w1",
            "e1",
            null,
            originalPayload,
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());

    assertThat(ctrl.payload()).isUnmodifiable();
    assertThat(ctrl.payload()).containsEntry("data", "value");
  }

  @Test
  void testCompactConstructorPayloadImmutability() {
    final Map<String, Object> payload = Map.of("a", "b", "c", "d");
    final ExecutionControl ctrl =
        new ExecutionControl(
            "s1",
            "w1",
            "e1",
            null,
            payload,
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());

    assertThat(ctrl.payload()).containsAllEntriesOf(payload);
  }

  @Test
  void testApplyPreProcessingControlsNoNodeControls() {
    final ExecutionControl control =
        new ExecutionControl(
            "s",
            "w",
            "e",
            null,
            Map.of(),
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());

    final var flux = control.applyPreProcessingControls("unknown", Flux.empty());
    assertThat(flux).isNotNull();
  }

  @Test
  void testApplyPreProcessingControlsWithSafeStop() {
    final ExecutionControl control =
        new ExecutionControl(
            "s",
            "w",
            "e",
            null,
            Map.of(),
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of("node1", Sinks.one()),
            Map.of(),
            Map.of());

    final Sinks.One<Void> safeSink = control.nodeSafeStopSinks().get("node1");
    safeSink.emitEmpty(Sinks.EmitFailureHandler.FAIL_FAST);

    final var flux = control.applyPreProcessingControls("node1", Flux.empty());
    assertThat(flux).isNotNull();
  }

  @Test
  void testApplyPreProcessingControlsWithSkipFlagPresent() {
    final AtomicBoolean skipFlag = new AtomicBoolean(false);
    final ExecutionControl control =
        new ExecutionControl(
            "s",
            "w",
            "e",
            null,
            Map.of(),
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of("node1", skipFlag));

    final var result =
        control.applyPreProcessingControls("node1", Flux.just(TEST_MESSAGE)).blockLast();
    assertThat(result).isNotNull();
  }

  @Test
  void testApplyPreProcessingControlsWithSkipFlagTrue() {
    final AtomicBoolean skipFlag = new AtomicBoolean(true);
    final ExecutionControl control =
        new ExecutionControl(
            "s",
            "w",
            "e",
            null,
            Map.of(),
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of("node1", skipFlag));

    final var result =
        control.applyPreProcessingControls("node1", Flux.just(TEST_MESSAGE)).blockLast();
    assertThat(result).isNotNull();
  }

  @Test
  void testApplyPostProcessingControlsNoNodeControls() {
    final ExecutionControl control =
        new ExecutionControl(
            "s",
            "w",
            "e",
            null,
            Map.of(),
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());

    final var flux = control.applyPostProcessingControls("unknown", Flux.empty());
    assertThat(flux).isNotNull();
  }

  @Test
  void testApplyPostProcessingControlsWithNodePauseValve() {
    final ExecutionControl control =
        new ExecutionControl(
            "s",
            "w",
            "e",
            null,
            Map.of(),
            Sinks.one(),
            null,
            Map.of(),
            Map.of(),
            Map.of("node1", new ReactiveControlValve()),
            Map.of());

    final var result =
        control.applyPostProcessingControls("node1", Flux.just(TEST_MESSAGE)).blockLast();
    assertThat(result).isNotNull();
  }

  @Test
  void testApplyPostProcessingControlsWithGlobalPauseValve() {
    final ExecutionControl control =
        new ExecutionControl(
            "s",
            "w",
            "e",
            null,
            Map.of(),
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());

    final var result =
        control.applyPostProcessingControls("any", Flux.just(TEST_MESSAGE)).blockLast();
    assertThat(result).isNotNull();
  }

  @Test
  void testApplyPostProcessingControlsWithImmediateStop() {
    final ExecutionControl control =
        new ExecutionControl(
            "s",
            "w",
            "e",
            null,
            Map.of(),
            Sinks.one(),
            null,
            Map.of("node1", Sinks.one()),
            Map.of(),
            Map.of(),
            Map.of());

    final var flux = control.applyPostProcessingControls("node1", Flux.empty());
    assertThat(flux).isNotNull();
  }

  @Test
  void testApplyPostProcessingControlsWithNodePauseValveAndMessage() {
    final ExecutionControl control =
        new ExecutionControl(
            "s",
            "w",
            "e",
            null,
            Map.of(),
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of("node1", new ReactiveControlValve()),
            Map.of());

    final var result =
        control.applyPostProcessingControls("node1", Flux.just(TEST_MESSAGE)).blockLast();
    assertThat(result).isNotNull();
  }

  @Test
  void testApplyPostProcessingControlsNodePauseValveErrorHandling() {
    final ReactiveControlValve mockValve = mock(ReactiveControlValve.class);
    when(mockValve.allowPassage())
        .thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("Valve error")));

    final ExecutionControl control =
        new ExecutionControl(
            "s",
            "w",
            "e",
            null,
            Map.of(),
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of("node1", mockValve),
            Map.of());

    final var result =
        control.applyPostProcessingControls("node1", Flux.just(TEST_MESSAGE)).blockLast();
    assertThat(result).isNotNull();
  }

  @Test
  void testApplyPostProcessingControlsGlobalPauseValveErrorHandling() {
    final ReactiveControlValve mockValve = mock(ReactiveControlValve.class);
    when(mockValve.allowPassage())
        .thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("Valve error")));

    final ExecutionControl control =
        new ExecutionControl(
            "s",
            "w",
            "e",
            null,
            Map.of(),
            Sinks.one(),
            mockValve,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());

    final var result =
        control.applyPostProcessingControls("any", Flux.just(TEST_MESSAGE)).blockLast();
    assertThat(result).isNotNull();
  }

  @Test
  void testApplyPostProcessingControlsWithGlobalPauseValveAndMessage() {
    final ExecutionControl control =
        new ExecutionControl(
            "s",
            "w",
            "e",
            null,
            Map.of(),
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());

    final var result =
        control.applyPostProcessingControls("any", Flux.just(TEST_MESSAGE)).blockLast();
    assertThat(result).isNotNull();
  }

  @Test
  void testApplyPostProcessingControlsWithBothValvesAndMessage() {
    final ExecutionControl control =
        new ExecutionControl(
            "s",
            "w",
            "e",
            null,
            Map.of(),
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of("node1", new ReactiveControlValve()),
            Map.of());

    final var result =
        control.applyPostProcessingControls("node1", Flux.just(TEST_MESSAGE)).blockLast();
    assertThat(result).isNotNull();
  }

  @Test
  void testApplyPostProcessingControlsAllThreeBranchesPresent() {
    final ExecutionControl control =
        new ExecutionControl(
            "s",
            "w",
            "e",
            null,
            Map.of(),
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of("node1", Sinks.one()),
            Map.of(),
            Map.of("node1", new ReactiveControlValve()),
            Map.of());

    final var flux = control.applyPostProcessingControls("node1", Flux.empty());
    assertThat(flux).isNotNull();
  }

  @Test
  void stepMode_letsExactlyOneMessagePerStepThrough() {
    final ReactiveControlValve valve = new ReactiveControlValve();
    final ExecutionControl ctrl =
        new ExecutionControl(
            "s",
            "w",
            "e",
            null,
            Map.of(),
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of("n1", valve),
            Map.of());
    valve.enableStepMode();

    StepVerifier.create(
            ctrl.applyPostProcessingControls("n1", Flux.just(TEST_MESSAGE, TEST_MESSAGE)))
        .expectSubscription()
        .expectNoEvent(Duration.ofMillis(100))
        .then(valve::step)
        .expectNext(TEST_MESSAGE)
        .expectNoEvent(Duration.ofMillis(100))
        .then(valve::step)
        .expectNext(TEST_MESSAGE)
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  void payloadWithNullValueIsAccepted() {
    final Map<String, Object> payload = new java.util.HashMap<>();
    payload.put("a", null);
    payload.put("b", 1);

    final ExecutionControl ctrl =
        new ExecutionControl(
            "s",
            "w",
            "e",
            null,
            payload,
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());

    assertThat(ctrl.payload()).containsEntry("b", 1).containsKey("a");
    assertThat(ctrl.payload()).isUnmodifiable();
  }

  private static ExecutionControl controlWith(
      final ReactiveControlValve global, final Map<String, ReactiveControlValve> nodeValves) {
    return new ExecutionControl(
        "s",
        "w",
        "e",
        null,
        Map.of(),
        Sinks.one(),
        global,
        Map.of(),
        Map.of(),
        nodeValves,
        Map.of());
  }

  @Test
  void isPaused_reflectsNodeValveAndGlobalValve() {
    final ReactiveControlValve global = new ReactiveControlValve();
    final ReactiveControlValve node = new ReactiveControlValve();
    final ExecutionControl ctrl = controlWith(global, Map.of("n1", node));

    assertThat(ctrl.isPaused("n1")).isFalse();
    assertThat(ctrl.isRunPaused()).isFalse();

    node.pause();
    assertThat(ctrl.isPaused("n1")).isTrue();
    assertThat(ctrl.isRunPaused()).isFalse();
    assertThat(ctrl.isPaused("other")).isFalse();

    node.resume();
    global.pause();
    assertThat(ctrl.isRunPaused()).isTrue();
    assertThat(ctrl.isPaused("n1")).isTrue();
    assertThat(ctrl.isPaused("other")).isTrue();
  }

  @Test
  void isPaused_withoutGlobalValveIsNotPaused() {
    final ExecutionControl ctrl = controlWith(null, Map.of());

    assertThat(ctrl.isRunPaused()).isFalse();
    assertThat(ctrl.isPaused("n1")).isFalse();
  }
}
