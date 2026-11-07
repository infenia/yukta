// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.guards;

import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorCategory;
import com.infenia.yukta.plugin.control.SupervisorDescriptor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.ConfigField.Type;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.supervisor.guards.StallWatchdog.Action;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

/** Makes {@link StallWatchdog}s from supervisors. */
@Component
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName") // TYPE and type()
public class StallWatchdogFactory implements SupervisorFactory {

  /** The supervisor type. */
  public static final String TYPE = "stall-watchdog";

  /** Most seconds a limit or an interval may be: a day. */
  private static final int MAX_SECONDS = 86_400;

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public ExecutionSupervisor create(final Supervisor supervisor) {
    return new StallWatchdog(config(supervisor.settings()), Schedulers.parallel());
  }

  @Override
  public Set<String> defaultAllowedCommands(final Supervisor supervisor) {
    return Set.of(action(supervisor.settings()).command());
  }

  @Override
  public Set<String> defaultAllowedCommands() {
    return Set.of(
        Action.PAUSE_NODE.command(),
        Action.STOP_NODE.command(),
        Action.PAUSE_WORKFLOW.command(),
        Action.STOP_WORKFLOW.command());
  }

  private static Action action(final Map<String, Object> settings) {
    return Settings.choice(settings, "action", Action.class, Action.PAUSE_NODE);
  }

  /* default */ static StallWatchdog.Config config(final Map<String, Object> settings) {
    final Integer running = Settings.optionalPositive(settings, "maxRunningSeconds");
    final Integer silence = Settings.optionalPositive(settings, "maxSilenceSeconds");
    if (running == null && silence == null) {
      throw new IllegalArgumentException("maxRunningSeconds or maxSilenceSeconds is required");
    }
    final int every = Settings.positive(settings, "checkEverySeconds", 10);
    if (every > MAX_SECONDS) {
      throw new IllegalArgumentException("checkEverySeconds is at most " + MAX_SECONDS);
    }
    final List<?> nodes = Settings.list(settings, "nodes");
    return new StallWatchdog.Config(
        running == null ? null : Duration.ofSeconds(running),
        silence == null ? null : Duration.ofSeconds(silence),
        nodes == null ? null : selectors(nodes),
        action(settings),
        once(settings.get("once")),
        Duration.ofSeconds(every));
  }

  private static List<String> selectors(final List<?> nodes) {
    Selectors.condition(nodes); // refuses a blank or comma selector
    return nodes.stream().map(Object::toString).toList();
  }

  private static boolean once(final Object value) {
    if (value == null) {
      return true;
    }
    if (value instanceof final Boolean flag) {
      return flag;
    }
    if (value instanceof final String text
        && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) {
      return Boolean.parseBoolean(text);
    }
    throw new IllegalArgumentException("once must be true or false");
  }

  @Override
  public SupervisorDescriptor describe() {
    return new SupervisorDescriptor(
        "Stall watchdog",
        "Pauses or stops a node, or its workflow, when a node has run longer than a limit or has"
            + " written no log line for a while.",
        "Use it for nodes that can hang: a process that never ends, a call that waits for ever. It"
            + " only follows the events it sees and never acts after a gap in them.",
        SupervisorCategory.GUARD,
        Optional.of(new UiDesign("orange", "timer", "Stall watchdog", 140, 80)),
        Map.of("maxRunningSeconds", 600, "maxSilenceSeconds", 120, "action", "PAUSE_NODE"));
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.optional(
            "maxRunningSeconds",
            Type.NUMBER,
            null,
            "Longest a node may run; at least one limit is required"),
        ConfigField.optional(
            "maxSilenceSeconds",
            Type.NUMBER,
            null,
            "Longest a running node may go without a log line"),
        ConfigField.optional(
            "nodes",
            Type.LIST,
            null,
            "Only these nodes: node ids or <prefix>.*; every node when absent"),
        ConfigField.choice(
            "action",
            false,
            "PAUSE_NODE",
            "What to do",
            "PAUSE_NODE",
            "STOP_NODE",
            "PAUSE_WORKFLOW",
            "STOP_WORKFLOW"),
        ConfigField.optional(
            "once", Type.BOOLEAN, true, "Act once per run of a node; false acts at every check"),
        ConfigField.optional("checkEverySeconds", Type.NUMBER, 10, "How often to look"));
  }
}
