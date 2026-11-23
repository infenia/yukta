// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopWorkflowCommand;
import org.junit.jupiter.api.Test;

@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.UnnecessaryFullyQualifiedName",
  "PMD.TooManyMethods",
  "PMD.UnusedLocalVariable",
  "PMD.UseConcurrentHashMap"
})
class ExecutionControlCommandTest {

  @Test
  void stopWorkflowDefaultsToDrain() {
    assertThat(new StopWorkflowCommand("e", "r").mode()).isEqualTo(StopMode.DRAIN);
    assertThat(new StopWorkflowCommand("e", "r", null).mode()).isEqualTo(StopMode.DRAIN);
    assertThat(new StopWorkflowCommand("e", "r", StopMode.KILL).mode()).isEqualTo(StopMode.KILL);
  }

  @Test
  void schedulerCommandsBelongToNoRun() {
    assertThat(new ExecutionControlCommand.SetLimitsCommand(2, 1).executionId())
        .isEqualTo(ExecutionControlCommand.SCHEDULER);
    assertThat(new ExecutionControlCommand.SetPriorityCommand("e", 3).executionId()).isEqualTo("e");
  }

  @Test
  void answerInputIsValidatedAndCopied() {
    final java.util.Map<String, Object> data = new java.util.HashMap<>();
    data.put("a", null);
    final ExecutionControlCommand.AnswerInputCommand answer =
        new ExecutionControlCommand.AnswerInputCommand(
            "e", "r", 3L, "approve", "fine", data, "policy", 0.9);
    data.put("b", 1);
    assertThat(answer.data()).containsOnlyKeys("a");
    assertThat(answer.executionId()).isEqualTo("e");
    assertThat(
            new ExecutionControlCommand.AnswerInputCommand(
                    "e", "r", null, "approve", null, null, null, null)
                .data())
        .isEmpty();
    assertThatThrownBy(() ->
            new ExecutionControlCommand.AnswerInputCommand(
                "e", " ", null, "approve", null, null, null, null))
        .hasMessage("requestId must not be blank");
    assertThatThrownBy(() ->
            new ExecutionControlCommand.AnswerInputCommand(
                " ", "r", null, "approve", null, null, null, null))
        .hasMessage("executionId must not be blank");
    assertThatThrownBy(() ->
            new ExecutionControlCommand.AnswerInputCommand(
                "e", "r", -1L, "approve", null, null, null, null))
        .hasMessage("expectedVersion must not be negative");
    assertThatThrownBy(() ->
            new ExecutionControlCommand.AnswerInputCommand(
                "e", "r", null, " ", null, null, null, null))
        .hasMessage("decision must not be blank");
    assertThatThrownBy(() ->
            new ExecutionControlCommand.AnswerInputCommand(
                "e", "r", null, "a", "x".repeat(9000), null, null, null))
        .hasMessage("text is longer than 8192 bytes");
    assertThatThrownBy(() ->
            new ExecutionControlCommand.AnswerInputCommand(
                "e", "r", null, "a", null, java.util.Map.of("k", "x".repeat(17_000)), null, null))
        .hasMessage("data is longer than 16384 bytes");
    assertThatThrownBy(() ->
            new ExecutionControlCommand.AnswerInputCommand(
                "e", "r", null, "a", null, null, "x".repeat(9000), null))
        .hasMessage("rationale is longer than 8192 bytes");
    assertThatThrownBy(() ->
            new ExecutionControlCommand.AnswerInputCommand(
                "e", "r", null, "a", null, null, null, 1.5))
        .hasMessage("confidence must be between 0 and 1");
  }

  @Test
  void escalateInputIsValidated() {
    final ExecutionControlCommand.EscalateInputCommand escalate =
        new ExecutionControlCommand.EscalateInputCommand("e", "r", "unsure", "approve, maybe");
    assertThat(escalate.advice()).isEqualTo("approve, maybe");
    assertThat(new ExecutionControlCommand.EscalateInputCommand("e", "r", "unsure", null).advice())
        .isNull();
    assertThatThrownBy(() -> new ExecutionControlCommand.EscalateInputCommand("e", "r", " ", null))
        .hasMessage("reason must not be blank");
    assertThatThrownBy(() -> new ExecutionControlCommand.EscalateInputCommand("e", " ", "x", null))
        .hasMessage("requestId must not be blank");
    assertThatThrownBy(() -> new ExecutionControlCommand.EscalateInputCommand(" ", "r", "x", null))
        .hasMessage("executionId must not be blank");
    assertThatThrownBy(
            () -> new ExecutionControlCommand.EscalateInputCommand("e", "r", "x", "y".repeat(9000)))
        .hasMessage("advice is longer than 8192 bytes");
  }

  @Test
  void commandsCanBeSwitchedOverExhaustively() {
    final ExecutionControlCommand command = new ExecutionControlCommand.PauseNodeCommand("e", "n");

    final String kind =
        switch (command) {
          case ExecutionControlCommand.PauseWorkflowCommand _ -> "pause-workflow";
          case ExecutionControlCommand.ResumeWorkflowCommand _ -> "resume-workflow";
          case ExecutionControlCommand.PauseNodeCommand _ -> "pause-node";
          case ExecutionControlCommand.ResumeNodeCommand _ -> "resume-node";
          case ExecutionControlCommand.StopNodeCommand _ -> "stop-node";
          case ExecutionControlCommand.SkipNodeCommand _ -> "skip-node";
          case ExecutionControlCommand.EnableStepModeCommand _ -> "enable-step";
          case ExecutionControlCommand.DisableStepModeCommand _ -> "disable-step";
          case ExecutionControlCommand.StepNodeCommand _ -> "step";
          case StopWorkflowCommand _ -> "stop-workflow";
          case ExecutionControlCommand.RestartCommand _ -> "restart";
          case ExecutionControlCommand.RestartFromNodeCommand _ -> "restart-from-node";
          case ExecutionControlCommand.SetBreakpointCommand _ -> "set-breakpoint";
          case ExecutionControlCommand.SetLimitsCommand c -> "limits-" + c.maxConcurrent();
          case ExecutionControlCommand.SetPriorityCommand c -> "priority-" + c.priority();
          case ExecutionControlCommand.AnswerInputCommand c -> "answer-" + c.requestId();
          case ExecutionControlCommand.EscalateInputCommand c -> "escalate-" + c.requestId();
          case ExecutionControlCommand.AgentMessageCommand c -> "agent-message-" + c.text();
          case ExecutionControlCommand.AgentInterruptCommand _ -> "agent-interrupt";
          case ExecutionControlCommand.AgentEndCommand _ -> "agent-end";
          case ExecutionControlCommand.StartWorkflowCommand c -> "start-" + c.workflowId();
          case ExecutionControlCommand.RecoverCommand c -> "recover-" + c.executionId();
          case ExecutionControlCommand.DrainCommand c -> "drain-" + c.draining();
        };

    assertThat(kind).isEqualTo("pause-node");
  }

  @Test
  void agentCommandsNeedTheirIdsAndMessagesNeedText() {
    assertThatThrownBy(
            () -> new ExecutionControlCommand.AgentMessageCommand("e", "n", "c", " ", false))
        .hasMessage("text must not be blank");
    assertThatThrownBy(
            () -> new ExecutionControlCommand.AgentMessageCommand("e", "n", "", "t", false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ExecutionControlCommand.AgentInterruptCommand("e", " ", "c"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ExecutionControlCommand.AgentEndCommand("", "n", "c", null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new ExecutionControlCommand.AgentEndCommand("e", "n", "c", null, "y".repeat(9000)))
        .hasMessage("reason is longer than 8192 bytes");
    assertThat(
            new ExecutionControlCommand.AgentEndCommand("e", "n", "c", "failure", "why").nodeId())
        .isEqualTo("n");
  }

  @Test
  void agentCommandsAreTargetedAtTheirNode() {
    assertThatThrownBy(
            () -> new ExecutionControlCommand.AgentMessageCommand("e", "n", "c", null, false))
        .hasMessage("text must not be blank");
    assertThat(new ExecutionControlCommand.AgentInterruptCommand("e", "n", "c").nodeId())
        .isEqualTo("n");
    assertThat(new ExecutionControlCommand.AgentMessageCommand("e", "n", "c", "t", true).nodeId())
        .isEqualTo("n");
  }

  @Test
  void everyCommandCarriesItsExecutionId() {
    final java.util.List<ExecutionControlCommand> all =
        java.util.List.of(
            new ExecutionControlCommand.PauseWorkflowCommand("e"),
            new ExecutionControlCommand.ResumeWorkflowCommand("e"),
            new ExecutionControlCommand.PauseNodeCommand("e", "n"),
            new ExecutionControlCommand.ResumeNodeCommand("e", "n"),
            new ExecutionControlCommand.StopNodeCommand("e", "n", true, null),
            new ExecutionControlCommand.SkipNodeCommand("e", "n", true),
            new ExecutionControlCommand.EnableStepModeCommand("e", "n"),
            new ExecutionControlCommand.DisableStepModeCommand("e", "n"),
            new ExecutionControlCommand.StepNodeCommand("e", "n"),
            new StopWorkflowCommand("e", null),
            new ExecutionControlCommand.RestartCommand("e", "e2"),
            new ExecutionControlCommand.RestartFromNodeCommand("e", "n", "e2"),
            new ExecutionControlCommand.SetBreakpointCommand("e", "n", "payload.x", "1"));

    assertThat(all).extracting(ExecutionControlCommand::executionId).containsOnly("e");
  }

  @Test
  void recoverNamesTheAbortedRunAndDrainBelongsToNoRun() {
    assertThat(new ExecutionControlCommand.RecoverCommand("old").executionId()).isEqualTo("old");
    final var drain = new ExecutionControlCommand.DrainCommand(true);
    assertThat(drain.executionId()).isEqualTo(ExecutionControlCommand.SCHEDULER);
    assertThat(drain.draining()).isTrue();
  }

  @Test
  void startBelongsToNoRunYet() {
    final var start = new ExecutionControlCommand.StartWorkflowCommand("s", "w", null, null);
    assertThat(start.executionId()).isEqualTo(ExecutionControlCommand.START);
    assertThat(start.nodeId()).isNull();
  }

  @Test
  void startPayloadIsCopiedReadOnlyAndMayHoldNullValues() {
    final java.util.Map<String, Object> payload = new java.util.HashMap<>();
    payload.put("a", null);
    final ExecutionControlCommand.StartWorkflowCommand start =
        new ExecutionControlCommand.StartWorkflowCommand("s", "w", payload, "k");
    payload.put("b", 1);

    assertThat(start.payload()).containsOnlyKeys("a");
    assertThat(start.payload()).containsEntry("a", null);
    assertThatThrownBy(() -> start.payload().put("c", 2))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(start.idempotencyKey()).isEqualTo("k");
    assertThat(new ExecutionControlCommand.StartWorkflowCommand("s", "w", null, null).payload())
        .isEmpty();
  }

  @Test
  void setBreakpoint_acceptsPayloadAndMetadataFields_andRejectsOthers() {
    assertThat(new ExecutionControlCommand.SetBreakpointCommand("e", "n", "payload", "x").field())
        .isEqualTo("payload");
    assertThat(new ExecutionControlCommand.SetBreakpointCommand("e", "n", "payload.a.b", "x"))
        .isNotNull();
    assertThat(new ExecutionControlCommand.SetBreakpointCommand("e", "n", "metadata.k", "x"))
        .isNotNull();
    assertThat(new ExecutionControlCommand.SetBreakpointCommand("e", "n", null, null).field())
        .isNull();
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new ExecutionControlCommand.SetBreakpointCommand("e", "n", "payloadX", "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Breakpoint field");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new ExecutionControlCommand.SetBreakpointCommand("e", "n", "payload", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("value to compare");
  }
}
