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
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Makes supervisors that hold commands until someone approves them. The settings are compiled to
 * one approval rule per command of the rule supervisor. The supervisor reviews and emits no
 * commands.
 */
@Component
@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName", // TYPE and type()
  "PMD.UseConcurrentHashMap", // local maps that keep the order of the rules file
  "PMD.AvoidInstantiatingObjectsInLoops",
  "PMD.AvoidDuplicateLiterals"
})
public class ApprovalGateFactory implements SupervisorFactory {

  /** The supervisor type. */
  public static final String TYPE = "approval-gate";

  /** The commands a gate may hold, by the name a rule uses. */
  private static final List<String> COMMANDS =
      List.of(
          "StopWorkflowCommand",
          "RestartCommand",
          "RestartFromNodeCommand",
          "SkipNodeCommand",
          "StopNodeCommand",
          "SetLimitsCommand");

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public ExecutionSupervisor create(final Supervisor supervisor) {
    return new RuleSupervisor(
        RuleSupervisor.read(rules(supervisor.settings())),
        Clock.systemUTC(),
        SupervisorCapabilities.from(supervisor, defaultAllowedCommands()));
  }

  @Override
  public Set<String> defaultAllowedCommands() {
    return Set.of();
  }

  /** One approval rule per command, in the shape of a rules file. */
  /* default */ static Map<String, Object> rules(final Map<String, Object> settings) {
    final List<?> commands = Settings.list(settings, "commands");
    if (commands == null) {
      throw new IllegalArgumentException("commands is required: name the commands to hold");
    }
    final List<?> selectors = Settings.list(settings, "nodes");
    final String nodes = selectors == null ? null : Selectors.condition(selectors);
    final List<Map<String, Object>> rules = new ArrayList<>();
    for (final Object command : commands) {
      if (!(command instanceof final String name) || !COMMANDS.contains(name)) {
        throw new IllegalArgumentException("commands must be among " + COMMANDS);
      }
      final Map<String, Object> gate = new LinkedHashMap<>();
      gate.put("command", name);
      if (nodes != null) {
        gate.put("if", nodes);
      }
      put(gate, "risk", Settings.text(settings, "risk"));
      put(gate, "route", Settings.text(settings, "route"));
      put(gate, "reason", Settings.text(settings, "reason"));
      put(gate, "ttl", Settings.optionalPositive(settings, "ttl"));
      rules.add(Map.of("id", TYPE + "-" + name, "requireApproval", gate));
    }
    return Map.of("rules", rules);
  }

  private static void put(final Map<String, Object> map, final String key, final Object value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  @Override
  public SupervisorDescriptor describe() {
    return new SupervisorDescriptor(
        "Approval gate",
        "Holds the chosen commands until a person approves them, optionally only for some nodes. It"
            + " never sends commands of its own.",
        "Use it to put a human in front of dangerous actions such as stopping a workflow,"
            + " restarting it or changing the limits.",
        SupervisorCategory.GATE,
        Optional.of(new UiDesign("amber", "approval", "Approval gate", 140, 80)),
        Map.of(
            "commands",
            List.of("StopWorkflowCommand", "RestartCommand"),
            "risk",
            "HIGH",
            "reason",
            "A person has to approve this"));
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        new ConfigField(
            "commands", Type.LIST, true, null, COMMANDS, "The commands to hold for approval"),
        ConfigField.optional(
            "nodes", Type.LIST, null, "Hold only commands for these nodes: node ids or <prefix>.*"),
        ConfigField.choice(
            "risk", false, "HIGH", "Who may approve: HIGH only a person", "LOW", "MEDIUM", "HIGH"),
        ConfigField.optional("route", Type.STRING, null, "The route of the approval request"),
        ConfigField.optional(
            "ttl", Type.NUMBER, null, "Seconds the request waits; the server default when absent"),
        ConfigField.optional("reason", Type.STRING, null, "Shown to whoever approves"));
  }
}
