// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.workflow;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.StopMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * Turns the terminal payload {@code {"commands": [{"type": ..., ...}]}} of a supervisor workflow
 * into commands. It reads a fixed list of types by hand, never generic polymorphic deserialisation,
 * so a workflow can only ask for what is listed here: some types always, the others only when the
 * profile's {@code allowedCommands} names them. Everything else is dropped and counted.
 */
@SuppressWarnings("PMD.UseConcurrentHashMap")
@UtilityClass
public class SupervisorResultMapper {

  /** Types any supervisor workflow may send. */
  private static final Set<String> ALWAYS =
      Set.of(
          "AnswerInput",
          "EscalateInput",
          "PauseWorkflow",
          "ResumeWorkflow",
          "PauseNode",
          "ResumeNode",
          "SetPriority");

  /**
   * What was read from one run.
   *
   * @param commands the commands, at most the cap
   * @param dropped how many entries were not mapped (unknown type, not granted, malformed, over the
   *     cap)
   */
  public record Mapped(List<ExecutionControlCommand> commands, int dropped) {

    /** Keeps an immutable copy. */
    public Mapped {
      commands = List.copyOf(commands);
    }
  }

  /**
   * Maps the payloads a run ended with.
   *
   * @param results the terminal payloads; the first map with a {@code commands} list is used
   * @param allowed the profile's {@code allowedCommands} (simple class names such as {@code
   *     StopWorkflowCommand}), or {@code null} for no restriction
   * @param executionId the run the event belongs to, used when an entry names none
   * @param max the most commands taken from one run
   * @return the commands and the number dropped
   */
  public static Mapped map(
      final List<Object> results,
      final @Nullable Set<String> allowed,
      final String executionId,
      final int max) {
    final List<ExecutionControlCommand> out = new ArrayList<>();
    int dropped = 0;
    for (final Object entry : entries(results)) {
      final ExecutionControlCommand command =
          out.size() >= max ? null : read(entry, allowed, executionId);
      if (command == null) {
        dropped++;
      } else {
        out.add(command);
      }
    }
    return new Mapped(out, dropped);
  }

  private static List<?> entries(final List<Object> results) {
    for (final Object result : results) {
      if (result instanceof Map<?, ?> map && map.get("commands") instanceof List<?> list) {
        return list;
      }
    }
    return List.of();
  }

  private static @Nullable ExecutionControlCommand read(
      final Object entry, final @Nullable Set<String> allowed, final String executionId) {
    if (!(entry instanceof Map<?, ?> raw) || !(raw.get("type") instanceof String type)) {
      return null;
    }
    final String name = type.endsWith("Command") ? type.substring(0, type.length() - 7) : type;
    if (!ALWAYS.contains(name) && (allowed == null || !allowed.contains(name + "Command"))) {
      return null;
    }
    final Map<String, Object> m = new HashMap<>();
    raw.forEach((k, v) -> m.put(String.valueOf(k), v));
    try {
      return build(name, m, m.get("executionId") instanceof String e ? e : executionId);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  @SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.AvoidDuplicateLiterals"})
  private static @Nullable ExecutionControlCommand build(
      final String name, final Map<String, Object> m, final String run) {
    return switch (name) {
      case "AnswerInput" ->
          new ExecutionControlCommand.AnswerInputCommand(
              run,
              text(m, "requestId"),
              m.get("expectedVersion") instanceof Number v ? v.longValue() : null,
              text(m, "decision"),
              optional(m, "text"),
              m.get("data") instanceof Map<?, ?> d ? copy(d) : Map.of(),
              optional(m, "rationale"),
              m.get("confidence") instanceof Number c ? c.doubleValue() : null);
      case "EscalateInput" ->
          new ExecutionControlCommand.EscalateInputCommand(
              run, text(m, "requestId"), text(m, "reason"), optional(m, "advice"));
      case "PauseWorkflow" -> new ExecutionControlCommand.PauseWorkflowCommand(run);
      case "ResumeWorkflow" -> new ExecutionControlCommand.ResumeWorkflowCommand(run);
      case "PauseNode" -> new ExecutionControlCommand.PauseNodeCommand(run, text(m, "nodeId"));
      case "ResumeNode" -> new ExecutionControlCommand.ResumeNodeCommand(run, text(m, "nodeId"));
      case "SetPriority" ->
          new ExecutionControlCommand.SetPriorityCommand(run, number(m, "priority"));
      case "StopWorkflow" ->
          new ExecutionControlCommand.StopWorkflowCommand(
              run,
              optional(m, "reason"),
              m.get("mode") == null ? null : StopMode.valueOf(text(m, "mode")));
      case "StopNode" ->
          new ExecutionControlCommand.StopNodeCommand(
              run,
              text(m, "nodeId"),
              Boolean.TRUE.equals(m.get("immediate")),
              optional(m, "reason"));
      case "SkipNode" ->
          new ExecutionControlCommand.SkipNodeCommand(
              run, text(m, "nodeId"), !Boolean.FALSE.equals(m.get("skip")));
      case "Restart" ->
          new ExecutionControlCommand.RestartCommand(run, UUID.randomUUID().toString());
      case "RestartFromNode" ->
          new ExecutionControlCommand.RestartFromNodeCommand(
              run, text(m, "fromNodeId"), UUID.randomUUID().toString());
      case "SetLimits" ->
          new ExecutionControlCommand.SetLimitsCommand(
              number(m, "maxConcurrent"), number(m, "maxPerWorkflow"));
      case "SetBreakpoint" ->
          new ExecutionControlCommand.SetBreakpointCommand(
              run, text(m, "nodeId"), optional(m, "field"), optional(m, "equalsValue"));
      default -> null;
    };
  }

  private static String text(final Map<String, Object> m, final String key) {
    if (m.get(key) instanceof String s && !s.isBlank()) {
      return s;
    }
    throw new IllegalArgumentException("missing " + key);
  }

  private static @Nullable String optional(final Map<String, Object> m, final String key) {
    return m.get(key) instanceof String s ? s : null;
  }

  private static int number(final Map<String, Object> m, final String key) {
    if (m.get(key) instanceof Number n) {
      return n.intValue();
    }
    throw new IllegalArgumentException("missing " + key);
  }

  private static Map<String, Object> copy(final Map<?, ?> source) {
    final Map<String, Object> out = new HashMap<>();
    source.forEach((k, v) -> out.put(String.valueOf(k), v));
    return out;
  }
}
