// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.supervisor.rules.RuleSet.Answer;
import com.infenia.yukta.supervisor.rules.RuleSet.Escalate;
import com.infenia.yukta.supervisor.rules.RuleSet.Then;
import com.infenia.yukta.supervisor.rules.RuleSet.When;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;

/**
 * A rule that reacts to events with a command, or with an answer to or an escalation of the input
 * request an event carries.
 */
@Slf4j
@SuppressWarnings("PMD.TooManyMethods")
public final class EventRule {

  /** Window used when a counting rule gives none. */
  private static final int DEFAULT_WINDOW_SECONDS = 60;

  /** Prefix of a {@code from} that reads the event's metadata. */
  private static final String METADATA_PREFIX = "metadata.";

  /** A {@code from} that restarts the whole run. */
  private static final String START = "START";

  /** The events that carry an input request, the only ones an answer or escalation can act on. */
  private static final Set<String> INPUT_EVENTS = Set.of("InputRequested", "InputEscalated");

  /** Stands in for the ids while a rule is checked at load time. */
  private static final String PROBE = "probe";

  /** Confidence of a rule's answer that does not give one: a rule is sure. */
  private static final Double RULE_CONFIDENCE = 1.0;

  /** The rule's id. */
  private final String identifier;

  /** What triggers it. */
  private final When when;

  /** What it does. */
  private final Then then;

  /** The parsed condition, or {@code null}. */
  private final Expression condition;

  /**
   * Validates and prepares a rule, so mistakes surface when the rules are loaded.
   *
   * @param ruleId the rule's id
   * @param when what triggers it
   * @param then what it does
   * @throws IllegalArgumentException if the condition, the command, the answer or the escalation is
   *     invalid
   */
  public EventRule(final String ruleId, final When when, final Then then) {
    this.identifier = ruleId;
    this.when = when;
    this.then = then;
    this.condition = when.condition() == null ? null : Conditions.parse(ruleId, when.condition());
    if (actsOnInput()) {
      checkInputAction();
    } else {
      commandFor(then, PROBE, "node", "new", Map.of());
    }
  }

  /**
   * Whether the rule answers input requests, which needs {@code canAnswer} on its supervisor.
   *
   * @return true for an {@code answer} rule
   */
  public boolean answers() {
    return then.answer() != null;
  }

  /** True for an {@code answer} or {@code escalate} rule. */
  private boolean actsOnInput() {
    return then.answer() != null || then.escalate() != null;
  }

  private void requireOneAction() {
    if (then.command() != null || (then.answer() != null && then.escalate() != null)) {
      throw new IllegalArgumentException(
          "Rule '" + identifier + "' must have exactly one of command, answer and escalate");
    }
  }

  /** Builds the input command once with stand-in ids, so its limits are checked at load time. */
  private void checkInputAction() {
    requireOneAction();
    if (when.event() != null && !INPUT_EVENTS.contains(when.event())) {
      throw new IllegalArgumentException(
          "Rule '"
              + identifier
              + "' answers or escalates, so it must react to InputRequested or InputEscalated");
    }
    try {
      inputCommand(PROBE, PROBE, 0);
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Rule '" + identifier + "' has an invalid answer or escalation: " + e.getMessage(), e);
    }
  }

  /**
   * The rule's id.
   *
   * @return the id
   */
  public String ruleId() {
    return identifier;
  }

  /**
   * How many matching events trigger the rule.
   *
   * @return the count; 1 when the rule does not count
   */
  public int count() {
    final Integer count = when.count();
    return count == null ? 1 : count;
  }

  /**
   * The window in which the counted events must occur.
   *
   * @return the window
   */
  public Duration window() {
    final Integer seconds = when.withinSeconds();
    return Duration.ofSeconds(seconds == null ? DEFAULT_WINDOW_SECONDS : seconds);
  }

  /**
   * Whether an event is one the rule reacts to.
   *
   * @param event the event
   * @param attempt how many restarts led to the event's run, readable as {@code attempt}
   * @return true if every condition of the rule holds
   */
  public boolean matches(final ExecutionEvent event, final int attempt) {
    final Map<String, Object> fields = Fields.eventFields(event);
    fields.put(Fields.ATTEMPT, attempt);
    final String kind = when.event();
    final String name = event.getClass().getSimpleName();
    return (kind == null || kind.equals(name))
        && (!actsOnInput() || INPUT_EVENTS.contains(name))
        && same(when.status(), fields.get(Fields.STATUS))
        && same(when.module(), fields.get(Fields.MODULE))
        && same(when.nodeId(), fields.get(Fields.NODE_ID))
        && (fields.get(Fields.NODE_ID) != null || !targetsNode())
        && same(when.workflowId(), fields.get(Fields.WORKFLOW_ID))
        && conditionHolds(fields);
  }

  /**
   * The command to send for an event that matched.
   *
   * @param event the event
   * @param newExecutionId the id for the run a restart starts
   * @return the command, addressed to the event's execution (and node, for node commands)
   */
  public ExecutionControlCommand command(final ExecutionEvent event, final String newExecutionId) {
    final InputRequest request = requestOf(event);
    if (actsOnInput() && request != null) {
      return inputCommand(request.executionId(), request.id(), request.version());
    }
    final Map<String, Object> fields = Fields.eventFields(event);
    final Object nodeId = fields.get(Fields.NODE_ID);
    return commandFor(
        then,
        event.executionId(),
        nodeId == null ? null : nodeId.toString(),
        newExecutionId,
        fields);
  }

  /**
   * Whether the rule restarts the run.
   *
   * @return true for {@code Restart} and {@code RestartFromNode}
   */
  public boolean restarts() {
    return commandName().startsWith("restart");
  }

  /** A node command has nothing to address when the event carries no node. */
  private boolean targetsNode() {
    final String name = commandName();
    return name.endsWith("node") && !name.startsWith("restart");
  }

  private String commandName() {
    final String command = then.command();
    return command == null ? "" : command.toLowerCase(Locale.ROOT);
  }

  private static @Nullable InputRequest requestOf(final ExecutionEvent event) {
    return switch (event) {
      case ExecutionEvent.InputRequested requested -> requested.request();
      case ExecutionEvent.InputEscalated escalated -> escalated.request();
      default -> null;
    };
  }

  /**
   * The answer or escalation for a request. The answer names the version the rule saw, so it is
   * refused if the request changed in between; its rationale starts with the rule's id, for the
   * audit.
   */
  private ExecutionControlCommand inputCommand(
      final String executionId, final String requestId, final long version) {
    final Answer answer = then.answer();
    final ExecutionControlCommand command;
    if (answer == null) {
      final Escalate escalate = Objects.requireNonNull(then.escalate(), "escalate");
      final String reason = escalate.reason();
      command =
          new ExecutionControlCommand.EscalateInputCommand(
              executionId,
              requestId,
              reason == null || reason.isBlank() ? "escalated by rule " + identifier : reason,
              escalate.advice());
    } else {
      final Double confidence = answer.confidence();
      final String rationale = answer.rationale();
      command =
          new ExecutionControlCommand.AnswerInputCommand(
              executionId,
              requestId,
              version,
              answer.decision(),
              answer.text(),
              Map.of(),
              "rule " + identifier + (rationale == null ? "" : ": " + rationale),
              Objects.requireNonNullElse(confidence, RULE_CONFIDENCE));
    }
    return command;
  }

  private boolean conditionHolds(final Map<String, Object> fields) {
    boolean holds = true;
    if (condition != null) {
      try {
        holds = Conditions.holds(condition, fields);
      } catch (final EvaluationException e) {
        log.atDebug()
            .setCause(e)
            .addKeyValue("rule", identifier)
            .log("Condition could not be evaluated");
        holds = false;
      }
    }
    return holds;
  }

  private static boolean same(final String wanted, final Object actual) {
    return wanted == null || wanted.equals(actual);
  }

  @SuppressWarnings("PMD.CyclomaticComplexity")
  private static ExecutionControlCommand commandFor(
      final Then then,
      final String executionId,
      final String nodeId,
      final String newExecutionId,
      final Map<String, Object> fields) {
    final String command = then.command();
    final String name = command == null ? "" : command.toLowerCase(Locale.ROOT);
    final String reason = then.reason() == null ? "Stopped by rule" : then.reason();
    return switch (name) {
      case "pauseworkflow" -> new ExecutionControlCommand.PauseWorkflowCommand(executionId);
      case "resumeworkflow" -> new ExecutionControlCommand.ResumeWorkflowCommand(executionId);
      case "stopworkflow" ->
          new ExecutionControlCommand.StopWorkflowCommand(
              executionId, reason, StopMode.valueOf(then.mode() == null ? "DRAIN" : then.mode()));
      case "pausenode" -> new ExecutionControlCommand.PauseNodeCommand(executionId, nodeId);
      case "resumenode" -> new ExecutionControlCommand.ResumeNodeCommand(executionId, nodeId);
      case "stopnode" ->
          new ExecutionControlCommand.StopNodeCommand(
              executionId, nodeId, Boolean.TRUE.equals(then.immediate()), reason);
      case "restart" -> new ExecutionControlCommand.RestartCommand(executionId, newExecutionId);
      case "restartfromnode" -> restart(then, executionId, nodeId, newExecutionId, fields);
      default ->
          throw new IllegalArgumentException("Unknown command in rule: '" + then.command() + "'");
    };
  }

  private static ExecutionControlCommand restart(
      final Then then,
      final String executionId,
      final String nodeId,
      final String newExecutionId,
      final Map<String, Object> fields) {
    final String from = then.from();
    final String target =
        from == null
            ? nodeId
            : from.startsWith(METADATA_PREFIX)
                ? metadataValue(fields, from.substring(METADATA_PREFIX.length()))
                : from;
    return target == null || START.equalsIgnoreCase(target)
        ? new ExecutionControlCommand.RestartCommand(executionId, newExecutionId)
        : new ExecutionControlCommand.RestartFromNodeCommand(executionId, target, newExecutionId);
  }

  private static String metadataValue(final Map<String, Object> fields, final String key) {
    final Object value = fields.get("metadata") instanceof Map<?, ?> map ? map.get(key) : null;
    return value == null ? null : value.toString();
  }
}
