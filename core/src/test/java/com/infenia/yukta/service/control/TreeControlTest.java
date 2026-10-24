// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class TreeControlTest {

  private final TaskTrackerService tracker = mock(TaskTrackerService.class);
  private final TreeControl control = new TreeControl(tracker);
  private final List<String> commanded = new CopyOnWriteArrayList<>();

  private static WorkflowExecutionSummary run(final String id, final String status) {
    return new WorkflowExecutionSummary(
        id, "wf", status, LocalDateTime.now(), null, "top", "top", null);
  }

  private Mono<Void> record(final String executionId) {
    commanded.add(executionId);
    return "broken".equals(executionId)
        ? Mono.error(new IllegalStateException("refused"))
        : Mono.empty();
  }

  @Test
  void withoutTree_onlyTheRunGetsTheCommand() {
    StepVerifier.create(control.apply("top", false, this::record)).verifyComplete();

    assertThat(commanded).containsExactly("top");
    verifyNoInteractions(tracker);
  }

  @Test
  void withTree_theRunThenEveryUnfinishedRunBelowGetsIt_andRefusalsBelowAreSkipped() {
    when(tracker.getTree("top"))
        .thenReturn(
            List.of(
                run("top", "RUNNING"),
                run("child", "RUNNING"),
                run("broken", "PAUSED"),
                run("queued", "QUEUED"),
                run("pending", "PENDING"),
                run("done", "SUCCESS")));

    StepVerifier.create(control.apply("top", true, this::record)).verifyComplete();

    assertThat(commanded).containsExactly("top", "child", "broken", "queued", "pending");
  }

  @Test
  void withTree_aRefusalOfTheRunItselfIsTheResult() {
    StepVerifier.create(control.apply("broken", true, this::record)).verifyErrorMessage("refused");
    verifyNoInteractions(tracker);
  }
}
