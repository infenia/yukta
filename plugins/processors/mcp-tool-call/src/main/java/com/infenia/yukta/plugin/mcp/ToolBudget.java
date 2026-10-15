// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.mcp;

import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.exception.RunAbortedException;
import com.infenia.yukta.plugin.util.RunBudgets;
import java.util.Map;
import java.util.Optional;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Mono;

/**
 * The {@code budget} option of {@code MCP_TOOL_CALL}: {@code maxCalls} limits the tool calls of one
 * run (and agent), counted in the ledger that {@code LLM_CALL} shares; {@code hardStop} ends the
 * whole run when it is used up instead of failing the call.
 */
@UtilityClass
class ToolBudget {

  /** Config key. */
  /* default */ static final String BUDGET = "budget";

  /** The ledger shared with the other plugins. */
  private static final RunBudgets LEDGER = RunBudgets.shared();

  /**
   * Counts the call and refuses it once the budget is used.
   *
   * @param runId the run
   * @param nodeId the node, for the counter
   * @param config the node configuration
   * @param reporter counts refusals
   * @return empty if the call may go on, or an error
   */
  /* default */ static Mono<Void> charge(
      final String runId,
      final String nodeId,
      final Map<String, Object> config,
      final PluginMetricsReporter reporter) {
    Mono<Void> result = Mono.empty();
    if (config.get(BUDGET) instanceof final Map<?, ?> budget) {
      final String key = RunBudgets.key(runId, budget);
      final Optional<String> why = LEDGER.exceeded(key, budget);
      if (why.isPresent()) {
        reporter.increment(nodeId, "mcp.budget.exceeded", "mcp");
        result =
            Mono.error(
                Boolean.TRUE.equals(budget.get("hardStop"))
                    ? new RunAbortedException("MCP budget exceeded: " + why.get())
                    : new IllegalStateException("MCP budget exceeded: " + why.get()));
      } else {
        LEDGER.addCall(key);
      }
    }
    return result;
  }

  /**
   * Whether an error ends the run, so {@code failureMode} must not absorb it.
   *
   * @param error the failure
   * @return true for a run abort
   */
  /* default */ static boolean isAbort(final Throwable error) {
    return RunAbortedException.isAbort(error);
  }

  /**
   * Checks the option.
   *
   * @param config the node configuration
   * @throws IllegalArgumentException if the budget is not usable for a tool node
   */
  /* default */ static void validate(final Map<String, Object> config) {
    if (config.get(BUDGET) instanceof final Map<?, ?> budget) {
      if (budget.containsKey("maxTokens") || budget.containsKey("maxCost")) {
        throw new IllegalArgumentException(
            "budget of MCP_TOOL_CALL supports maxCalls, agent, hardStop");
      }
      RunBudgets.validate(config);
    } else if (config.get(BUDGET) != null) {
      throw new IllegalArgumentException("budget must be a map");
    }
  }
}
