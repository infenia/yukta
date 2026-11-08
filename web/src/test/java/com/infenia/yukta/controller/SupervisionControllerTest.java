// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.review.ControlReviewer;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Who carries a command, for a node, a run, a run found by its execution and the server. */
@NoArgsConstructor
class SupervisionControllerTest {

  private final ControlReviewer reviewer = mock(ControlReviewer.class);
  private final ControlBusGateway controlBus = mock(ControlBusGateway.class);
  private final com.infenia.yukta.service.supervisor.OwnershipMap ownership =
      mock(com.infenia.yukta.service.supervisor.OwnershipMap.class);
  private final SupervisionController controller =
      new SupervisionController(reviewer, controlBus, ownership);

  private static ControlReviewer.Capability allowed() {
    return new ControlReviewer.Capability(true, "default", null);
  }

  @Test
  void nodeAsksForItsNodeCommands() {
    when(reviewer.capability(
            org.mockito.ArgumentMatchers.eq("s"),
            org.mockito.ArgumentMatchers.eq("w"),
            org.mockito.ArgumentMatchers.eq("build.a"),
            org.mockito.ArgumentMatchers.any(ExecutionControlCommand.PauseNodeCommand.class)))
        .thenReturn(new ControlReviewer.Capability(false, "guard", "guard controls this."));
    when(reviewer.capability(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.argThat(
                c -> !(c instanceof ExecutionControlCommand.PauseNodeCommand))))
        .thenReturn(allowed());

    StepVerifier.create(controller.supervision("s", "w", null, "build.a"))
        .assertNext(
            r -> {
              assertThat(r.data()).containsKeys("pauseNode", "resumeNode", "stopNode", "skipNode");
              assertThat(r.data().get("pauseNode").via()).isEqualTo("guard");
              assertThat(r.data().get("pauseNode").allowed()).isFalse();
              assertThat(r.data().get("resumeNode").allowed()).isTrue();
              assertThat(r.data()).doesNotContainKey("pause");
            })
        .verifyComplete();
  }

  @Test
  void runFoundByItsExecutionAsksForRunCommandsOfItsWorkflow() {
    when(controlBus.getCurrentProgress("e1"))
        .thenReturn(
            com.infenia.yukta.testing.Fixtures.workflowProgress(
                "e1",
                "s1",
                "wf",
                com.infenia.yukta.model.execution.TaskStatus.RUNNING,
                java.util.List.of(),
                java.time.Instant.now(),
                null,
                null));
    when(reviewer.capability(
            org.mockito.ArgumentMatchers.eq("s1"),
            org.mockito.ArgumentMatchers.eq("wf"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(allowed());

    StepVerifier.create(controller.supervision(null, null, "e1", null))
        .assertNext(
            r ->
                assertThat(r.data())
                    .containsKeys(
                        "start", "pause", "resume", "stop", "restart", "priority", "recover"))
        .verifyComplete();
  }

  @Test
  void noTargetAsksForTheServersCommands() {
    when(reviewer.capability(
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(new ControlReviewer.Capability(false, "default", "off"));

    StepVerifier.create(controller.supervision(null, null, null, null))
        .assertNext(
            r -> {
              assertThat(r.data()).containsOnlyKeys("drain", "setLimits");
              assertThat(r.data().get("drain").reason()).isEqualTo("off");
            })
        .verifyComplete();
  }

  @Test
  void unknownExecutionFallsBackToTheServerAndWorkflowAloneToTheRun() {
    when(reviewer.capability(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(allowed());

    StepVerifier.create(controller.supervision(null, null, "gone", null))
        .assertNext(r -> assertThat(r.data()).containsOnlyKeys("drain", "setLimits"))
        .verifyComplete();
    StepVerifier.create(controller.supervision(null, "wf", null, null))
        .assertNext(r -> assertThat(r.data()).containsKeys("pause", "resume", "stop"))
        .verifyComplete();
  }

  @Test
  void ownershipComesFromTheMap() {
    final var owned =
        new com.infenia.yukta.service.supervisor.OwnershipMap.Ownership(
            "s", "w", true, "default", java.util.Map.of("build.a", "guard"));
    when(ownership.ownersOf("s", "w")).thenReturn(reactor.core.publisher.Mono.just(owned));

    StepVerifier.create(controller.ownership("s", "w"))
        .assertNext(r -> assertThat(r.data()).isEqualTo(owned))
        .verifyComplete();
  }
}
