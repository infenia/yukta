// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;

/**
 * The names of the supervisor contract: what a decision engine may read and do. The page {@code
 * docs/running/supervisor-contract.mdx} lists the same names; a test pins them. Changes are
 * additive within a major {@link #VERSION}.
 *
 * @since 0.1.0
 */
@UtilityClass
public class SupervisorContract {

  /** The major version of the contract. */
  public static final int VERSION = 1;

  /** Response header on the agent API that carries {@link #VERSION}. */
  public static final String VERSION_HEADER = "Yukta-Supervisor-Contract";

  /** The most executions a selector expands to. */
  public static final int SELECTOR_CAP = 200;

  /** Default for {@code maxInitiativePerMinute}. */
  public static final int DEFAULT_INITIATIVE_PER_MINUTE = 10;

  /** What an engine may read; always limited to what its supervisor owns. */
  public static final List<String> READ_OPERATIONS =
      List.of(
          "self",
          "events",
          "snapshot",
          "executions",
          "execution",
          "workflow",
          "inputs",
          "logs",
          "scheduler");

  /** What an engine may do; every one is checked against ownership, permissions and limits. */
  public static final List<String> ACT_OPERATIONS =
      List.of(
          "run.start",
          "run.pause",
          "run.resume",
          "run.resumeHeld",
          "run.stop",
          "run.restart",
          "run.restartFromNode",
          "run.priority",
          "node.pause",
          "node.resume",
          "node.stop",
          "node.skip",
          "node.retry",
          "node.stepMode",
          "node.breakpoint",
          "input.answer",
          "input.escalate",
          "server.setLimits",
          "server.drain",
          "notify",
          "delegate");

  /** What an engine receives. */
  public static final List<String> SIGNAL_KINDS =
      List.of("Event", "Tick", "Review", "Delegated", "Consulted", "Started");

  /** What an engine returns. */
  public static final List<String> DIRECTIVE_KINDS =
      List.of("Command", "ReviewVerdict", "DelegationAnswer", "Delegate", "Notify", "NoOpinion");

  /** Why a directive was sent; the first three take an id after the colon. */
  public static final List<String> CAUSES = List.of("event:", "answers:", "tick", "initiative");

  /** The result of one directive against one target. */
  public static final List<String> OUTCOMES =
      List.of("APPLIED", "NOT_FOUND", "NOT_OWNED", "PRECONDITION_FAILED", "ALREADY_IN_STATE");

  /**
   * All operation names by group, in a stable order.
   *
   * @return group name to names: read, act, signals, directives, causes, outcomes
   */
  public static Map<String, List<String>> operations() {
    return Collections.unmodifiableMap(
        Stream.of(
                Map.entry("read", READ_OPERATIONS),
                Map.entry("act", ACT_OPERATIONS),
                Map.entry("signals", SIGNAL_KINDS),
                Map.entry("directives", DIRECTIVE_KINDS),
                Map.entry("causes", CAUSES),
                Map.entry("outcomes", OUTCOMES))
            .collect(
                LinkedHashMap<String, List<String>>::new,
                (all, group) -> all.put(group.getKey(), group.getValue()),
                Map::putAll));
  }
}
