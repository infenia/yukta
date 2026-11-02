// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@NoArgsConstructor
class TreeControlTest {

  private final TaskTrackerService tracker = mock(TaskTrackerService.class);
  private final TreeControl control = new TreeControl(tracker);
  private final List<String> commanded = new CopyOnWriteArrayList<>();

  private static final String TOP = "top";
  private static final String BROKEN = "broken";

  private static WorkflowExecutionSummary run(final String executionId, final TaskStatus status) {
    return new WorkflowExecutionSummary(
        executionId, "wf", status, Instant.now(), null, TOP, TOP, null);
  }

  private Mono<Void> record(final String executionId) {
    commanded.add(executionId);
    return BROKEN.equals(executionId)
        ? Mono.error(new IllegalStateException("refused"))
        : Mono.empty();
  }

  @Test
  void withoutTree_onlyTheRunGetsTheCommand() {
    StepVerifier.create(control.apply(TOP, false, this::record)).verifyComplete();

    assertThat(commanded).containsExactly(TOP);
    verifyNoInteractions(tracker);
  }

  @Test
  void withTree_theRunThenEveryUnfinishedRunBelowGetsIt_andRefusalsBelowAreSkipped() {
    when(tracker.getTree(TOP))
        .thenReturn(
            List.of(
                run(TOP, TaskStatus.RUNNING),
                run("child", TaskStatus.RUNNING),
                run(BROKEN, TaskStatus.RUNNING),
                run("queued", TaskStatus.QUEUED),
                run("pending", TaskStatus.PENDING),
                run("done", TaskStatus.SUCCESS)));

    StepVerifier.create(control.apply(TOP, true, this::record)).verifyComplete();

    assertThat(commanded).containsExactly(TOP, "child", BROKEN, "queued", "pending");
  }

  @Test
  void withTree_refusalOfTheRunItselfIsTheResult() {
    StepVerifier.create(control.apply(BROKEN, true, this::record)).verifyErrorMessage("refused");
    verifyNoInteractions(tracker);
  }
}
