// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.supervisor.rules.RuleSet.Then;
import com.infenia.yukta.supervisor.rules.RuleSet.When;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;

/** A rule that reacts to events with a command. */
@Slf4j
public final class EventRule {

  /** Window used when a counting rule gives none. */
  private static final int DEFAULT_WINDOW_SECONDS = 60;

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
   * @throws IllegalArgumentException if the condition or the command is invalid
   */
  public EventRule(final String ruleId, final When when, final Then then) {
    this.identifier = ruleId;
    this.when = when;
    this.then = then;
    this.condition = when.condition() == null ? null : Conditions.parse(ruleId, when.condition());
    commandFor(then, "execution", "node");
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
   * @return true if every condition of the rule holds
   */
  public boolean matches(final ExecutionEvent event) {
    final Map<String, Object> fields = Fields.eventFields(event);
    final String kind = when.event();
    return (kind == null || kind.equals(event.getClass().getSimpleName()))
        && same(when.status(), fields.get(Fields.STATUS))
        && same(when.module(), fields.get(Fields.MODULE))
        && same(when.nodeId(), fields.get(Fields.NODE_ID))
        && same(when.workflowId(), fields.get(Fields.WORKFLOW_ID))
        && conditionHolds(fields);
  }

  /**
   * The command to send for an event that matched.
   *
   * @param event the event
   * @return the command, addressed to the event's execution (and node, for node commands)
   */
  public ExecutionControlCommand command(final ExecutionEvent event) {
    final Object nodeId = Fields.eventFields(event).get(Fields.NODE_ID);
    return commandFor(then, event.executionId(), nodeId == null ? null : nodeId.toString());
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
      final Then then, final String executionId, final String nodeId) {
    final String name = then.command() == null ? "" : then.command().toLowerCase(Locale.ROOT);
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
      default ->
          throw new IllegalArgumentException("Unknown command in rule: '" + then.command() + "'");
    };
  }
}
