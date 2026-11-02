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
 *   {"id": "auto-approve-staging",
 *    "when": {"event": "InputRequested", "if": "risk == 'LOW'"},
 *    "then": {"answer": {"decision": "approve", "rationale": "policy 7"}}},
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
   *     ControlOutcome}, {@code LogLine}, {@code EventsMissed} or one of the input request events
   *     ({@code InputRequested}, {@code InputEscalated}, {@code InputAnswered}, {@code
   *     InputExpired}, {@code InputCancelled})
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
   * What a rule does when it fires: send a command, or answer or escalate the input request of the
   * event. Exactly one of {@code command}, {@code answer} and {@code escalate} is set.
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
   * @param answer answers the input request of an {@code InputRequested} or {@code InputEscalated}
   *     event
   * @param escalate hands the input request of an {@code InputRequested} or {@code InputEscalated}
   *     event up, with advice
   */
  public record Then(
      @Nullable String command,
      @Nullable String mode,
      @Nullable String reason,
      @Nullable Boolean immediate,
      @Nullable String from,
      @Nullable Answer answer,
      @Nullable Escalate escalate) {}

  /**
   * The answer a rule gives to an input request. Whether it is accepted is decided by the server:
   * the supervisor's profile needs {@code canAnswer} and a {@code maxRisk} that reaches the
   * request's risk, and a request of risk {@code HIGH} is answered by people only.
   *
   * @param decision the decision; one of the request's option ids for {@code APPROVAL} with options
   *     and {@code CHOICE}
   * @param text free text for {@code TEXT} requests
   * @param rationale why; recorded with the answer after {@code rule <id>: }
   * @param confidence from 0 to 1; default 1
   */
  public record Answer(
      String decision,
      @Nullable String text,
      @Nullable String rationale,
      @Nullable Double confidence) {}

  /**
   * How a rule hands an input request up to whoever answers next.
   *
   * @param reason why; default {@code escalated by rule <id>}
   * @param advice a recommendation the next responder sees
   */
  public record Escalate(@Nullable String reason, @Nullable String advice) {}

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
