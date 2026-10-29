// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.core.LogLevel;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

@SuppressWarnings({"PMD.ShortMethodName", "PMD.UseConcurrentHashMap"})
class ExecutionSupervisorTest {

  private static final class Observer implements ExecutionSupervisor {
    @Override
    public String id() {
      return "observer";
    }

    @Override
    public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
      return Flux.empty();
    }
  }

  @Test
  void defaults_orderIsZero_andEveryCommandIsAllowed() {
    final ExecutionSupervisor supervisor = new Observer();
    final ControlRequest request =
        new ControlRequest(new ExecutionControlCommand.PauseWorkflowCommand("e"), null);

    assertThat(supervisor.order()).isZero();
    assertThat(supervisor.review(request).block()).isEqualTo(Decision.ALLOW);
  }

  @Test
  void decision_denyCarriesTheReason() {
    assertThat(Decision.ALLOW.allowed()).isTrue();
    assertThat(Decision.deny("why").allowed()).isFalse();
    assertThat(Decision.deny("why").reason()).isEqualTo("why");
  }

  @Test
  void nodeStatusChanged_acceptsNullMetadataValues() {
    final Map<String, Object> metadata = new HashMap<>();
    metadata.put("missing", null);
    final var event =
        new ExecutionEvent.NodeStatusChanged(
            "e", "wf", "n", "m", "SUCCESS", metadata, Instant.now());

    assertThat(event.metadata()).containsEntry("missing", null);
  }

  @Test
  void nodeStatusChanged_keepsAnImmutableCopyOfTheMetadata() {
    final Map<String, Object> metadata = new HashMap<>(Map.of("k", "v"));
    final var event =
        new ExecutionEvent.NodeStatusChanged(
            "e", "wf", "n", "m", "FAILURE", metadata, Instant.now());
    metadata.put("later", 1);

    assertThat(event.metadata()).containsOnlyKeys("k");
    final var withoutMetadata =
        new ExecutionEvent.NodeStatusChanged("e", "wf", "n", "m", "FAILURE", null, Instant.now());
    assertThat(withoutMetadata.metadata()).isEmpty();
  }

  @Test
  void events_exposeTheirExecutionAndTime() {
    final Instant now = Instant.now();
    final List<ExecutionEvent> events =
        List.of(
            new ExecutionEvent.WorkflowStatusChanged("e", "wf", "SUCCESS", now),
            new ExecutionEvent.ControlOutcome(
                "e", "PauseWorkflowCommand", "rules", true, null, now),
            new ExecutionEvent.LogLine("e", "n", "line", LogLevel.INFO, now));

    assertThat(events).allSatisfy(event -> assertThat(event.executionId()).isEqualTo("e"));
    assertThat(events).allSatisfy(event -> assertThat(event.timestamp()).isEqualTo(now));
  }

  @Test
  void eventsMissed_belongsToNoExecution() {
    final var missed = new ExecutionEvent.EventsMissed(3, Instant.now());

    assertThat(missed.executionId()).isEmpty();
    assertThat(missed.count()).isEqualTo(3);
  }

  @Test
  void actorKey_isStable() {
    assertThat(ControlActor.CONTEXT_KEY).isEqualTo("yukta.control.actor");
  }
}
