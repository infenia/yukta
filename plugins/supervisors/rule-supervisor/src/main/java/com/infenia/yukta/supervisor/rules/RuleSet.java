// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Rules read from a JSON file.
 *
 * <pre>{@code
 * {"rules": [
 *   {"id": "pause-on-repeated-failure",
 *    "when": {"event": "NodeStatusChanged", "status": "FAILURE", "module": "HTTP_REQUEST",
 *             "count": 3, "withinSeconds": 60},
 *    "then": {"command": "PauseWorkflow"}},
 *   {"id": "no-kill",
 *    "deny": {"command": "StopWorkflowCommand", "if": "mode == 'KILL'", "reason": "use DRAIN"}},
 *   {"id": "prod-stop-needs-approval",
 *    "requireApproval": {"command": "StopWorkflow", "if": "workflowId == 'prod-deploy'",
 *                        "risk": "HIGH", "reason": "production stop"}}
 * ]}
 * }</pre>
 *
 * @param rules the rules, in order
 */
public record RuleSet(List<Rule> rules) {

  /** Keeps an immutable copy of the rules. */
  public RuleSet {
    rules = rules == null ? List.of() : List.copyOf(rules);
  }

  /**
   * One rule: a reaction to events ({@code when}/{@code then}) or a veto on commands ({@code
   * deny}).
   *
   * @param id names the rule in logs
   * @param when the events that trigger it
   * @param then what to do when triggered
   * @param deny the commands to refuse
   * @param requireApproval the commands that run only once someone approves them
   */
  public record Rule(
      String id,
      @Nullable When when,
      @Nullable Then then,
      @Nullable Deny deny,
      @Nullable RequireApproval requireApproval) {

    /**
     * A rule without an approval gate.
     *
     * @param id names the rule in logs
     * @param when the events that trigger it
     * @param then what to do when triggered
     * @param deny the commands to refuse
     */
    public Rule(
        final String id,
        final @Nullable When when,
        final @Nullable Then then,
        final @Nullable Deny deny) {
      this(id, when, then, deny, null);
    }
  }

  /**
   * Events that trigger a rule. Every field that is set must match.
   *
   * @param event the event kind: {@code NodeStatusChanged}, {@code WorkflowStatusChanged}, {@code
   *     ControlOutcome} or {@code LogLine}
   * @param status the status, for status events
   * @param module the node's plugin type, for node events
   * @param nodeId the node, for node and log events
   * @param workflowId the workflow, for status events
   * @param condition a SpEL expression over the event's fields (for example {@code metadata['code']
   *     == 500}); property names are the fields of the event
   * @param count how many matching events trigger the rule; default 1
   * @param withinSeconds the window in which {@code count} events must occur; counted per execution
   */
  public record When(
      @Nullable String event,
      @Nullable String status,
      @Nullable String module,
      @Nullable String nodeId,
      @Nullable String workflowId,
      @JsonProperty("if") @Nullable String condition,
      @Nullable Integer count,
      @Nullable Integer withinSeconds) {}

  /**
   * The command a rule sends to the execution that triggered it.
   *
   * @param command {@code PauseWorkflow}, {@code ResumeWorkflow}, {@code StopWorkflow}, {@code
   *     PauseNode}, {@code ResumeNode}, {@code StopNode}, {@code Restart} or {@code
   *     RestartFromNode}. Node commands address the node that triggered the rule. A restart gets a
   *     new execution id and, for a rule's {@code if}, {@code attempt} counts the restarts that led
   *     to the run (0 for the first).
   * @param mode for {@code StopWorkflow}: {@code DRAIN} (default), {@code CANCEL} or {@code KILL}
   * @param reason recorded with a stop
   * @param immediate for {@code StopNode}: interrupt instead of draining
   * @param from for {@code RestartFromNode}: the node to restart from, as a literal or {@code
   *     metadata.<key>} of the event; {@code START} (or nothing found) restarts the whole run
   */
  public record Then(
      String command,
      @Nullable String mode,
      @Nullable String reason,
      @Nullable Boolean immediate,
      @Nullable String from) {}

  /**
   * Commands a rule refuses.
   *
   * @param command the command name, with or without the {@code Command} suffix, for example {@code
   *     StopWorkflow}
   * @param condition a SpEL expression over the command's fields ({@code executionId}, {@code
   *     nodeId}, {@code mode}, {@code reason}, {@code immediate}, {@code actor}, {@code sessionId},
   *     {@code workflowId}); absent means always
   * @param reason shown to the caller
   */
  public record Deny(
      String command, @JsonProperty("if") @Nullable String condition, @Nullable String reason) {}

  /**
   * Commands a rule lets run only once someone approves them: the command is parked and an {@code
   * APPROVAL} input request is raised. A deny rule that also matches wins.
   *
   * @param command the command name, as for {@link Deny}
   * @param condition a SpEL expression over the command's fields, as for {@link Deny}; absent means
   *     always
   * @param risk who may approve: {@code LOW}, {@code MEDIUM} or {@code HIGH} (default; a human
   *     only)
   * @param route the route of the request, or absent for the default route
   * @param reason shown to whoever approves
   */
  public record RequireApproval(
      String command,
      @JsonProperty("if") @Nullable String condition,
      @Nullable String risk,
      @Nullable String route,
      @Nullable String reason) {}
}
