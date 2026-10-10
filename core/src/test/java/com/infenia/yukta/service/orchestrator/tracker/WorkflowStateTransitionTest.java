// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Random event orders must never produce an illegal node transition. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidInstantiatingObjectsInLoops"
})
class WorkflowStateTransitionTest {

  private static final int SEQUENCES = 2_000;
  private static final int EVENTS = 12;

  private static int rank(final String status) {
    return switch (status) {
      case "PENDING" -> 0;
      case "QUEUED" -> 1;
      case "RUNNING" -> 2;
      default -> 3;
    };
  }

  @Test
  void randomEventOrders_neverReopenAnEndedNode_orStepBack() {
    final Random random = new Random(42);
    final TaskStatus[] all = TaskStatus.values();
    for (int run = 0; run < SEQUENCES; run++) {
      final WorkflowState state =
          new WorkflowState("e", "s", "w", "RUNNING", List.of("n"), LocalDateTime.now());
      final List<String> applied = new ArrayList<>();
      TaskProgress before = state.getTasks().getFirst();
      for (int i = 0; i < EVENTS; i++) {
        final String next = all[random.nextInt(all.length)].name();
        state.updateTask("n", "m", next, Map.of());
        final TaskProgress after = state.getTasks().getFirst();
        applied.add(next);

        assertThat(rank(after.status()))
            .as("rank after %s", applied)
            .isGreaterThanOrEqualTo(rank(before.status()));
        if (TaskStatus.isTerminalName(before.status())) {
          assertThat(after.status()).as("ended node after %s", applied).isEqualTo(before.status());
          assertThat(after.endTime()).isEqualTo(before.endTime());
        }
        if (before.startTime() != null) {
          assertThat(after.startTime()).isEqualTo(before.startTime());
        }
        assertThat(after.endTime() != null)
            .as("end time iff terminal after %s", applied)
            .isEqualTo(TaskStatus.isTerminalName(after.status()));
        before = after;
      }
    }
  }

  @Test
  void endedNode_keepsItsStatus_whenLateEventArrives() {
    final WorkflowState state =
        new WorkflowState("e", "s", "w", "RUNNING", List.of("n"), LocalDateTime.now());

    state.updateTask("n", "m", "RUNNING", Map.of());
    state.updateTask("n", "m", "SUCCESS", Map.of());
    state.updateTask("n", "m", "CANCELLED", Map.of());
    state.updateTask("n", "m", "RUNNING", Map.of());

    assertThat(state.getTasks().getFirst().status()).isEqualTo("SUCCESS");
  }

  @Test
  void theSameStatusAgain_stillMergesMetadata() {
    final WorkflowState state =
        new WorkflowState("e", "s", "w", "RUNNING", List.of("n"), LocalDateTime.now());

    state.updateTask("n", "m", "RUNNING", Map.of("a", 1));
    state.updateTask("n", "m", "RUNNING", Map.of("b", 2));

    assertThat(state.getTasks().getFirst().metadata()).containsEntry("a", 1).containsEntry("b", 2);
  }

  @Test
  void updateWithoutMetadata_keepsTheExistingMetadata() {
    final WorkflowState state =
        new WorkflowState("e", "s", "w", "RUNNING", List.of("n"), LocalDateTime.now());
    state.updateTask("n", "m", "RUNNING", Map.of("k", "v"));
    state.updateTask("n", "m", "RUNNING", null);

    assertThat(state.getTasks().getFirst().metadata()).containsEntry("k", "v");
  }
}
