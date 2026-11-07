// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.guards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.supervisor.guards.StallWatchdog.Action;
import com.infenia.yukta.supervisor.guards.StallWatchdog.Config;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

/** The stall watchdog, on virtual time. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class StallWatchdogTest {

  private static final Duration CHECK = Duration.ofSeconds(10);

  private final Sinks.Many<ExecutionEvent> events = Sinks.many().unicast().onBackpressureBuffer();

  private static ExecutionEvent status(final String node, final String status) {
    return new ExecutionEvent.NodeStatusChanged(
        "e1", "s", "w", node, "PROCESS_EXECUTOR", status, Map.of(), Instant.EPOCH);
  }

  private static ExecutionEvent log(final String node) {
    return new ExecutionEvent.LogLine("e1", "s", "w", node, "tick", LogLevel.INFO, Instant.EPOCH);
  }

  private static Config config(
      final Duration running, final Duration silence, final Action action, final boolean once) {
    return new Config(running, silence, null, action, once, CHECK);
  }

  private StepVerifier.FirstStep<ExecutionControlCommand> watch(final Config config) {
    return StepVerifier.withVirtualTime(
        () -> new StallWatchdog(config, VirtualTimeScheduler.get()).supervise(events.asFlux()));
  }

  private void emit(final ExecutionEvent event) {
    events.tryEmitNext(event);
  }

  @Test
  void stopsNodeThatRunsTooLongOnce() {
    watch(config(Duration.ofSeconds(60), null, Action.STOP_NODE, true))
        .then(() -> emit(status("n", "RUNNING")))
        .expectNoEvent(Duration.ofSeconds(50))
        .thenAwait(Duration.ofSeconds(10))
        .assertNext(
            c -> {
              assertThat(c).isInstanceOf(StopNodeCommand.class);
              assertThat(((StopNodeCommand) c).nodeId()).isEqualTo("n");
              assertThat(((StopNodeCommand) c).reason()).contains("running longer than 60s");
            })
        .expectNoEvent(Duration.ofMinutes(5))
        .thenCancel()
        .verify();
  }

  @Test
  void actsAtEveryCheckWhenOnceIsOff() {
    watch(config(Duration.ofSeconds(10), null, Action.PAUSE_NODE, false))
        .then(() -> emit(status("n", "RUNNING")))
        .thenAwait(Duration.ofSeconds(10))
        .expectNextCount(1)
        .thenAwait(Duration.ofSeconds(10))
        .expectNextCount(1)
        .thenCancel()
        .verify();
  }

  @Test
  void logLineKeepsNodeFromGoingSilent() {
    watch(config(null, Duration.ofSeconds(30), Action.PAUSE_NODE, true))
        .then(() -> emit(status("n", "RUNNING")))
        .thenAwait(Duration.ofSeconds(20))
        .then(() -> emit(log("n")))
        .expectNoEvent(Duration.ofSeconds(20))
        .thenAwait(Duration.ofSeconds(20))
        .assertNext(c -> assertThat(c).isEqualTo(new PauseNodeCommand("e1", "n")))
        .thenCancel()
        .verify();
  }

  @Test
  void nodeThatEndsIsLeftAlone() {
    watch(config(Duration.ofSeconds(30), null, Action.PAUSE_WORKFLOW, true))
        .then(() -> emit(status("n", "RUNNING")))
        .thenAwait(Duration.ofSeconds(10))
        .then(() -> emit(status("n", "SUCCESS")))
        .expectNoEvent(Duration.ofMinutes(2))
        .thenCancel()
        .verify();
  }

  @Test
  void queuedAndPendingStatusesDoNotEndTheWatch() {
    watch(config(Duration.ofSeconds(30), null, Action.PAUSE_WORKFLOW, true))
        .then(() -> emit(status("n", "RUNNING")))
        .then(() -> emit(status("n", "QUEUED")))
        .then(() -> emit(status("n", "PENDING")))
        .then(() -> emit(status("n", "RUNNING")))
        .thenAwait(Duration.ofSeconds(30))
        .assertNext(c -> assertThat(c).isEqualTo(new PauseWorkflowCommand("e1")))
        .thenCancel()
        .verify();
  }

  @Test
  void stopsTheWorkflowWhenToldTo() {
    watch(config(Duration.ofSeconds(10), null, Action.STOP_WORKFLOW, true))
        .then(() -> emit(status("n", "RUNNING")))
        .thenAwait(Duration.ofSeconds(10))
        .assertNext(c -> assertThat(c).isInstanceOf(StopWorkflowCommand.class))
        .thenCancel()
        .verify();
  }

  @Test
  void afterGapItForgetsEverythingAndDoesNotAct() {
    watch(config(Duration.ofSeconds(30), null, Action.PAUSE_NODE, true))
        .then(() -> emit(status("n", "RUNNING")))
        .thenAwait(Duration.ofSeconds(20))
        .then(() -> emit(new ExecutionEvent.EventsMissed(5, Instant.EPOCH)))
        .expectNoEvent(Duration.ofMinutes(5))
        .thenCancel()
        .verify();
  }

  @Test
  void onlySelectedNodesAreWatched() {
    final Config selected =
        new Config(
            Duration.ofSeconds(10),
            null,
            List.of("build.*", "deploy"),
            Action.PAUSE_NODE,
            true,
            CHECK);

    watch(selected)
        .then(() -> emit(status("other", "RUNNING")))
        .then(() -> emit(status("builder", "RUNNING")))
        .then(() -> emit(log("other")))
        .expectNoEvent(Duration.ofSeconds(60))
        .then(() -> emit(status("build.test", "RUNNING")))
        .thenAwait(Duration.ofSeconds(10))
        .assertNext(c -> assertThat(((PauseNodeCommand) c).nodeId()).isEqualTo("build.test"))
        .thenCancel()
        .verify();
  }

  @Test
  void stateIsBoundedAndTheOldestNodeIsDropped() {
    final StepVerifier.Step<ExecutionControlCommand> step =
        watch(config(Duration.ofSeconds(10), null, Action.PAUSE_NODE, true))
            .then(
                () -> {
                  for (int i = 0; i <= StallWatchdog.MAX_NODES; i++) {
                    emit(status("n" + i, "RUNNING"));
                  }
                })
            .thenAwait(Duration.ofSeconds(10));
    step.expectNextCount(StallWatchdog.MAX_NODES).thenCancel().verify();
  }

  @Test
  void endsWithItsEvents() {
    final StepVerifier.FirstStep<ExecutionControlCommand> verifier =
        watch(config(Duration.ofSeconds(10), null, Action.PAUSE_NODE, true));

    verifier.then(events::tryEmitComplete).expectComplete().verify();
  }

  @Test
  void settingsNeedLimitAndBadValuesAreRefused() {
    assertThatThrownBy(() -> StallWatchdogFactory.config(Map.of()))
        .hasMessageContaining("maxRunningSeconds or maxSilenceSeconds");
    assertThatThrownBy(() -> StallWatchdogFactory.config(Map.of("maxRunningSeconds", 0)))
        .hasMessageContaining("maxRunningSeconds");
    assertThatThrownBy(
            () ->
                StallWatchdogFactory.config(
                    Map.of("maxRunningSeconds", 5, "checkEverySeconds", 90_000)))
        .hasMessageContaining("checkEverySeconds");
    assertThatThrownBy(
            () -> StallWatchdogFactory.config(Map.of("maxRunningSeconds", 5, "once", "maybe")))
        .hasMessageContaining("once");
    assertThatThrownBy(() -> StallWatchdogFactory.config(Map.of("maxRunningSeconds", 5, "once", 5)))
        .hasMessageContaining("once");
    assertThatThrownBy(
            () -> StallWatchdogFactory.config(Map.of("maxRunningSeconds", 5, "action", "EXPLODE")))
        .hasMessageContaining("action");
    assertThatThrownBy(
            () ->
                StallWatchdogFactory.config(
                    Map.of("maxRunningSeconds", 5, "nodes", List.of("a,b"))))
        .hasMessageContaining("nodes");
  }

  @Test
  void settingsAreReadWithTheirDefaults() {
    final Config config = StallWatchdogFactory.config(Map.of("maxSilenceSeconds", 30));
    assertThat(config.maxRunning()).isNull();
    assertThat(config.maxSilence()).isEqualTo(Duration.ofSeconds(30));
    assertThat(config.action()).isEqualTo(Action.PAUSE_NODE);
    assertThat(config.once()).isTrue();
    assertThat(config.checkEvery()).isEqualTo(Duration.ofSeconds(10));
    assertThat(config.nodes()).isNull();

    final Config full =
        StallWatchdogFactory.config(
            Map.of(
                "maxRunningSeconds",
                60,
                "nodes",
                List.of("a"),
                "once",
                "false",
                "action",
                "stop_node",
                "checkEverySeconds",
                5));
    assertThat(full.once()).isFalse();
    assertThat(full.nodes()).containsExactly("a");
    assertThat(StallWatchdogFactory.config(Map.of("maxRunningSeconds", 1, "once", true)).once())
        .isTrue();
    assertThat(StallWatchdogFactory.config(Map.of("maxRunningSeconds", 1, "once", "TRUE")).once())
        .isTrue();
  }

  @Test
  void catalogEntryAndDefaultCommands() {
    final StallWatchdogFactory factory = new StallWatchdogFactory();
    final var descriptor = factory.describe();

    assertThat(descriptor.displayName()).isEqualTo("Stall watchdog");
    assertThat(descriptor.category().name()).isEqualTo("GUARD");
    assertThat(factory.getConfigSchema())
        .extracting(ConfigField::name)
        .containsExactly(
            "maxRunningSeconds",
            "maxSilenceSeconds",
            "nodes",
            "action",
            "once",
            "checkEverySeconds");
    factory.validate(new Supervisor("w", factory.type(), descriptor.exampleSettings(), false));
    assertThat(
            factory
                .create(new Supervisor("w", "stall-watchdog", descriptor.exampleSettings(), false))
                .id())
        .isEqualTo("stall-watchdog");
    assertThat(
            factory.defaultAllowedCommands(
                new Supervisor("w", "stall-watchdog", Map.of("action", "STOP_NODE"), false)))
        .containsExactly("StopNodeCommand");
    assertThat(factory.defaultAllowedCommands())
        .containsExactlyInAnyOrder(
            "PauseNodeCommand", "StopNodeCommand", "PauseWorkflowCommand", "StopWorkflowCommand");
  }

  @Test
  void eventsThatTellItNothingAreIgnored() {
    watch(config(Duration.ofSeconds(30), null, Action.PAUSE_NODE, true))
        .then(
            () ->
                emit(
                    new ExecutionEvent.WorkflowStatusChanged(
                        "e1", "s", "w", "RUNNING", Instant.EPOCH)))
        .expectNoEvent(Duration.ofMinutes(2))
        .thenCancel()
        .verify();
  }
}
