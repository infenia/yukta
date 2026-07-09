// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

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
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
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
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
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
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());

    final var flux =
        control.applyPreProcessingControls("unknown", reactor.core.publisher.Flux.empty());
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
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of("node1", Sinks.one()),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());

    final Sinks.One<Void> safeSink = control.nodeSafeStopSinks().get("node1");
    safeSink.emitEmpty(Sinks.EmitFailureHandler.FAIL_FAST);

    final var flux =
        control.applyPreProcessingControls("node1", reactor.core.publisher.Flux.empty());
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
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of("node1", skipFlag),
            Map.of(),
            Map.of());

    final var result =
        control
            .applyPreProcessingControls("node1", reactor.core.publisher.Flux.just(TEST_MESSAGE))
            .blockLast();
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
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of("node1", skipFlag),
            Map.of(),
            Map.of());

    final var result =
        control
            .applyPreProcessingControls("node1", reactor.core.publisher.Flux.just(TEST_MESSAGE))
            .blockLast();
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
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());

    final var flux =
        control.applyPostProcessingControls("unknown", reactor.core.publisher.Flux.empty());
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
            Sinks.one(),
            null,
            Map.of(),
            Map.of(),
            Map.of("node1", new ReactiveControlValve()),
            Map.of(),
            Map.of(),
            Map.of());

    final var result =
        control
            .applyPostProcessingControls("node1", reactor.core.publisher.Flux.just(TEST_MESSAGE))
            .blockLast();
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
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());

    final var result =
        control
            .applyPostProcessingControls("any", reactor.core.publisher.Flux.just(TEST_MESSAGE))
            .blockLast();
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
            Sinks.one(),
            null,
            Map.of("node1", Sinks.one()),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());

    final var flux =
        control.applyPostProcessingControls("node1", reactor.core.publisher.Flux.empty());
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
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of("node1", new ReactiveControlValve()),
            Map.of(),
            Map.of(),
            Map.of());

    final var result =
        control
            .applyPostProcessingControls("node1", reactor.core.publisher.Flux.just(TEST_MESSAGE))
            .blockLast();
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
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of("node1", mockValve),
            Map.of(),
            Map.of(),
            Map.of());

    final var result =
        control
            .applyPostProcessingControls("node1", reactor.core.publisher.Flux.just(TEST_MESSAGE))
            .blockLast();
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
            Sinks.one(),
            mockValve,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());

    final var result =
        control
            .applyPostProcessingControls("any", reactor.core.publisher.Flux.just(TEST_MESSAGE))
            .blockLast();
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
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());

    final var result =
        control
            .applyPostProcessingControls("any", reactor.core.publisher.Flux.just(TEST_MESSAGE))
            .blockLast();
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
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of("node1", new ReactiveControlValve()),
            Map.of(),
            Map.of(),
            Map.of());

    final var result =
        control
            .applyPostProcessingControls("node1", reactor.core.publisher.Flux.just(TEST_MESSAGE))
            .blockLast();
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
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of("node1", Sinks.one()),
            Map.of(),
            Map.of("node1", new ReactiveControlValve()),
            Map.of(),
            Map.of(),
            Map.of());

    final var flux =
        control.applyPostProcessingControls("node1", reactor.core.publisher.Flux.empty());
    assertThat(flux).isNotNull();
  }
}
