// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * The rule documents of the retired guard types ({@code failure-guard}, {@code approval-gate},
 * {@code stall-watchdog}), so a stored guard becomes a {@code rules} supervisor with the same
 * effect (decision D8: guards are presets of the rules engine).
 */
@SuppressWarnings({"PMD.AvoidInstantiatingObjectsInLoops", "PMD.UseConcurrentHashMap"})
@UtilityClass
public class LegacyGuards {

  /** The retired failure guard. */
  public static final String FAILURE_GUARD = "failure-guard";

  /** The retired approval gate. */
  public static final String APPROVAL_GATE = "approval-gate";

  /** The retired stall watchdog. */
  public static final String STALL_WATCHDOG = "stall-watchdog";

  /** The commands an approval gate may hold. */
  private static final Set<String> GATED =
      Set.of(
          "StopWorkflowCommand",
          "RestartCommand",
          "RestartFromNodeCommand",
          "SetLimitsCommand",
          "StopNodeCommand",
          "SkipNodeCommand");

  /** The longest period a stall watchdog checks at. */
  private static final int MAX_SECONDS = 86_400;

  /**
   * A rule document and what it cannot keep of the old settings.
   *
   * @param rules the rule document, version 1
   * @param notes what changed in meaning; empty when nothing did
   */
  public record Converted(Map<String, Object> rules, List<String> notes) {

    /** Keeps immutable copies. */
    public Converted {
      rules = Map.copyOf(rules);
      notes = List.copyOf(notes);
    }
  }

  /**
   * The rule document of a failure guard: an action once nodes failed a number of times within a
   * window, optionally only some nodes or failures whose error contains some text.
   *
   * @param settings the guard's settings ({@code failures}, {@code withinSeconds}, {@code nodes},
   *     {@code errorContains}, {@code action})
   * @return the rule document
   */
  public static Converted failureGuard(final Map<String, Object> settings) {
    final String action = command(LegacySettings.text(settings, "action"), "PauseWorkflow");
    final List<String> conditions = new ArrayList<>();
    final List<?> nodes = LegacySettings.list(settings, "nodes");
    if (nodes != null) {
      conditions.add(LegacySelectors.condition(nodes));
    }
    final String contains = LegacySettings.text(settings, "errorContains");
    if (contains != null) {
      conditions.add(
          "metadata != null and metadata['error'] != null and metadata['error'] matches "
              + LegacySelectors.literal("(?s).*" + Pattern.quote(contains) + ".*"));
    }
    final Map<String, Object> when = new LinkedHashMap<>();
    when.put("event", "NodeStatusChanged");
    when.put("status", "FAILURE");
    when.put("count", LegacySettings.positive(settings, "failures", 3));
    when.put("withinSeconds", LegacySettings.positive(settings, "withinSeconds", 60));
    if (!conditions.isEmpty()) {
      when.put("if", String.join(" and ", conditions));
    }
    return new Converted(
        document(Map.of("id", FAILURE_GUARD, "when", when, "then", Map.of("command", action))),
        List.of());
  }

  /**
   * The rule document of an approval gate: one approval rule per command it holds.
   *
   * @param settings the gate's settings ({@code commands}, {@code nodes}, {@code risk}, {@code
   *     route}, {@code reason}, {@code ttl})
   * @return the rule document
   */
  public static Converted approvalGate(final Map<String, Object> settings) {
    final List<?> commands = LegacySettings.list(settings, "commands");
    if (commands == null) {
      throw new IllegalArgumentException("commands is required: name the commands to hold");
    }
    final List<?> selectors = LegacySettings.list(settings, "nodes");
    final String nodes = selectors == null ? null : LegacySelectors.condition(selectors);
    final List<Map<String, Object>> rules = new ArrayList<>();
    for (final Object command : commands) {
      if (!(command instanceof final String name) || !GATED.contains(name)) {
        throw new IllegalArgumentException("commands must be among " + GATED);
      }
      final Map<String, Object> gate = new LinkedHashMap<>();
      gate.put("command", name);
      put(gate, "if", nodes);
      put(gate, "risk", LegacySettings.text(settings, "risk"));
      put(gate, "route", LegacySettings.text(settings, "route"));
      put(gate, "reason", LegacySettings.text(settings, "reason"));
      put(gate, "ttl", LegacySettings.optionalPositive(settings, "ttl"));
      rules.add(Map.of("id", APPROVAL_GATE + "-" + name, "requireApproval", gate));
    }
    return new Converted(Map.of("schemaVersion", 1, "rules", rules), List.of());
  }

  /**
   * The rule document of a stall watchdog. The rules engine sees runs, not running nodes, so the
   * watchdog becomes a timed rule that pauses a running workflow when nothing happened in it for
   * the longest of the times it watched; the notes say what that changes.
   *
   * @param settings the watchdog's settings ({@code maxRunningSeconds}, {@code maxSilenceSeconds},
   *     {@code checkEverySeconds}, {@code nodes}, {@code action}, {@code once})
   * @return the rule document and the notes
   */
  public static Converted stallWatchdog(final Map<String, Object> settings) {
    final Integer running = LegacySettings.optionalPositive(settings, "maxRunningSeconds");
    final Integer silence = LegacySettings.optionalPositive(settings, "maxSilenceSeconds");
    if (running == null && silence == null) {
      throw new IllegalArgumentException("maxRunningSeconds or maxSilenceSeconds is required");
    }
    final int every =
        Math.min(LegacySettings.positive(settings, "checkEverySeconds", 10), MAX_SECONDS);
    final int idle = Math.max(running == null ? 0 : running, silence == null ? 0 : silence);
    final List<String> notes = new ArrayList<>();
    notes.add(
        "it watches whole runs: a running workflow with no event for "
            + idle
            + " s is paused (needs PauseWorkflowCommand in initiative)");
    if (LegacySettings.list(settings, "nodes") != null) {
      notes.add("its node selectors no longer narrow it; assign it to those nodes instead");
    }
    final String action = LegacySettings.text(settings, "action");
    if (action != null && !"PAUSE_WORKFLOW".equalsIgnoreCase(action)) {
      notes.add("its action " + action + " becomes PauseWorkflow");
    }
    return new Converted(
        document(
            Map.of(
                "id",
                STALL_WATCHDOG,
                "when",
                Map.of("every", every + "s", "status", "RUNNING", "if", "idleSeconds > " + idle),
                "then",
                Map.of(
                    "command",
                    "PauseWorkflow",
                    "reason",
                    "no progress for more than " + idle + " seconds"))),
        notes);
  }

  private static Map<String, Object> document(final Map<String, Object> rule) {
    return Map.of("schemaVersion", 1, "rules", List.of(rule));
  }

  /** The rule command of an old action name such as {@code PAUSE_NODE}. */
  private static String command(final String action, final String fallback) {
    if (action == null) {
      return fallback;
    }
    return switch (action.toUpperCase(Locale.ROOT)) {
      case "PAUSE_WORKFLOW" -> "PauseWorkflow";
      case "STOP_WORKFLOW" -> "StopWorkflow";
      case "PAUSE_NODE" -> "PauseNode";
      default -> throw new IllegalArgumentException("unknown action " + action);
    };
  }

  private static void put(final Map<String, Object> map, final String key, final Object value) {
    if (value != null) {
      map.put(key, value);
    }
  }
}
