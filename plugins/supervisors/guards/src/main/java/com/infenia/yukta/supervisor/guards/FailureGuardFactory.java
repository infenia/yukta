// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.guards;

import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorCategory;
import com.infenia.yukta.plugin.control.SupervisorDescriptor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.ConfigField.Type;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.supervisor.rules.RuleSupervisor;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Makes supervisors that stop or pause after repeated node failures. The settings are compiled to
 * one rule of the rule supervisor: {@code failures} failures within {@code withinSeconds}, of the
 * nodes {@code nodes} (any when absent) and whose {@code error} metadata contains {@code
 * errorContains} (any when absent), then the {@code action}.
 */
@Component
@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName", // TYPE and type()
  "PMD.UseConcurrentHashMap", // local maps that keep the order of the rules file
  "PMD.AvoidDuplicateLiterals"
})
public class FailureGuardFactory implements SupervisorFactory {

  /** The supervisor type. */
  public static final String TYPE = "failure-guard";

  /** What the guard does once the failures are counted. */
  public enum Action {
    /** Pause the workflow. */
    PAUSE_WORKFLOW("PauseWorkflow", "PauseWorkflowCommand"),
    /** Drain and stop the workflow. */
    STOP_WORKFLOW("StopWorkflow", "StopWorkflowCommand"),
    /** Pause the node that failed. */
    PAUSE_NODE("PauseNode", "PauseNodeCommand");

    /** The command name a rule uses. */
    private final String rule;

    /** The command type the supervisor is allowed to send. */
    private final String command;

    Action(final String rule, final String command) {
      this.rule = rule;
      this.command = command;
    }
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public ExecutionSupervisor create(final Supervisor supervisor) {
    final Action action = action(supervisor.settings());
    return new RuleSupervisor(
        RuleSupervisor.read(rules(supervisor.settings(), action)),
        Clock.systemUTC(),
        SupervisorCapabilities.from(supervisor, Set.of(action.command)));
  }

  @Override
  public Set<String> defaultAllowedCommands(final Supervisor supervisor) {
    return Set.of(action(supervisor.settings()).command);
  }

  @Override
  public Set<String> defaultAllowedCommands() {
    // the action is a setting, so the type as a whole may send any of the three
    return Set.of(
        Action.PAUSE_WORKFLOW.command, Action.STOP_WORKFLOW.command, Action.PAUSE_NODE.command);
  }

  private static Action action(final Map<String, Object> settings) {
    return Settings.choice(settings, "action", Action.class, Action.PAUSE_WORKFLOW);
  }

  /** The one rule the settings stand for, in the shape of a rules file. */
  /* default */ static Map<String, Object> rules(
      final Map<String, Object> settings, final Action action) {
    final int failures = Settings.positive(settings, "failures", 3);
    final int within = Settings.positive(settings, "withinSeconds", 60);
    final List<String> conditions = new ArrayList<>();
    final List<?> nodes = Settings.list(settings, "nodes");
    if (nodes != null) {
      conditions.add(Selectors.condition(nodes));
    }
    final String contains = Settings.text(settings, "errorContains");
    if (contains != null) {
      conditions.add(
          "metadata != null and metadata['error'] != null and metadata['error'] matches "
              + Selectors.literal("(?s).*" + Pattern.quote(contains) + ".*"));
    }
    final Map<String, Object> when = new LinkedHashMap<>();
    when.put("event", "NodeStatusChanged");
    when.put("status", "FAILURE");
    when.put("count", failures);
    when.put("withinSeconds", within);
    if (!conditions.isEmpty()) {
      when.put("if", String.join(" and ", conditions));
    }
    return Map.of(
        "rules", List.of(Map.of("id", TYPE, "when", when, "then", Map.of("command", action.rule))));
  }

  @Override
  public SupervisorDescriptor describe() {
    return new SupervisorDescriptor(
        "Failure guard",
        "Pauses or stops a workflow, or pauses the failing node, once nodes have failed a set"
            + " number of times within a time window.",
        "Use it to stop a run that keeps failing instead of letting it retry for ever. Limit it to"
            + " some nodes, or to failures whose error contains some text.",
        SupervisorCategory.GUARD,
        Optional.of(new UiDesign("red", "shield", "Failure guard", 140, 80)),
        Map.of("failures", 3, "withinSeconds", 60, "action", "PAUSE_WORKFLOW"));
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.optional("failures", Type.NUMBER, 3, "How many failures trigger the action"),
        ConfigField.optional(
            "withinSeconds", Type.NUMBER, 60, "The window in which they are counted, per run"),
        ConfigField.optional(
            "nodes", Type.LIST, null, "Only failures of these nodes count: node ids or <prefix>.*"),
        ConfigField.optional(
            "errorContains",
            Type.STRING,
            null,
            "Only failures whose error metadata contains this text count"),
        ConfigField.choice(
            "action",
            false,
            "PAUSE_WORKFLOW",
            "What to do",
            "PAUSE_WORKFLOW",
            "STOP_WORKFLOW",
            "PAUSE_NODE"));
  }
}
