// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.util;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the model and tool calls of each run have used so far, so a node can stop a run that loops
 * on a model. One ledger ({@link #shared()}) serves every plugin, so a budget spans {@code
 * LLM_CALL} and {@code MCP_TOOL_CALL}; a budget with an {@code agent} counts that agent apart from
 * the rest of the run. The newest {@value #MAX_RUNS} keys are remembered; an older one starts again
 * from zero.
 */
@SuppressFBWarnings(
    value = "MS_EXPOSE_REP",
    justification = "shared() is a convenience; tests and plugins may still own an instance")
public final class RunBudgets {

  /** Option: most tokens. */
  private static final String MAX_TOKENS = "maxTokens";

  /** Option: most calls. */
  private static final String MAX_CALLS = "maxCalls";

  /** Option: names the agent whose use is counted apart from the rest of the run. */
  private static final String AGENT = "agent";

  /** Ledger shared by every plugin of the process. */
  private static final RunBudgets LEDGER = new RunBudgets();

  /** Option: most cost. */
  private static final String MAX_COST = "maxCost";

  /** Most runs remembered. */
  public static final int MAX_RUNS = 10_000;

  /**
   * Use by run, least recently used first, so a run that is still calling is never the one dropped;
   * the synchronized wrapper makes {@code merge} atomic.
   */
  private final Map<String, Spent> byRun =
      Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(final Map.Entry<String, Spent> eldest) {
              return size() > MAX_RUNS;
            }
          });

  /** What a run that has not called yet has used. */
  private static final Spent NOTHING = new Spent(0, 0, 0);

  /** Tokens and cost one run has used. */
  private record Spent(long tokens, double cost, long calls) {}

  /**
   * The ledger every plugin uses by default.
   *
   * @return the shared ledger
   */
  public static RunBudgets shared() {
    return LEDGER;
  }

  /**
   * The key a budget counts under: the run, or the run and the agent the budget names.
   *
   * @param runId the run
   * @param budget the {@code budget} option, or null
   * @return the key to pass to {@link #exceeded} and the add methods
   */
  public static String key(final String runId, final Map<?, ?> budget) {
    return budget != null && budget.get(AGENT) instanceof final String agent && !agent.isBlank()
        ? runId + "\0" + agent
        : runId;
  }

  /** Creates an empty ledger. */
  public RunBudgets() {
    super();
  }

  /**
   * Checks a run against its limits before another call.
   *
   * @param runId the run
   * @param maxTokens most tokens (input plus output) the run may use, or null for no limit
   * @param maxCost most cost the run may reach, or null for no limit
   * @return why the run may not call again, or empty if it may
   */
  public Optional<String> exceeded(final String runId, final Long maxTokens, final Double maxCost) {
    return exceeded(runId, maxTokens, maxCost, null);
  }

  /**
   * Checks a run against its limits before another call.
   *
   * @param runId the run
   * @param maxTokens most tokens (input plus output) the run may use, or null for no limit
   * @param maxCost most cost the run may reach, or null for no limit
   * @param maxCalls most calls the run may make, or null for no limit
   * @return why the run may not call again, or empty if it may
   */
  public Optional<String> exceeded(
      final String runId, final Long maxTokens, final Double maxCost, final Long maxCalls) {
    final Spent spent = byRun.getOrDefault(runId, NOTHING);
    final String why =
        maxTokens != null && spent.tokens() >= maxTokens
            ? "token budget of " + maxTokens + " used (" + spent.tokens() + ")"
            : maxCost != null && spent.cost() >= maxCost
                ? "cost budget of " + maxCost + " used (" + spent.cost() + ")"
                : maxCalls != null && spent.calls() >= maxCalls
                    ? "call budget of " + maxCalls + " used (" + spent.calls() + ")"
                    : null;
    return Optional.ofNullable(why);
  }

  /**
   * Checks a run against the {@code budget} option of a node.
   *
   * @param runId the run
   * @param budget the option: a map with {@code maxTokens} and/or {@code maxCost}
   * @return why the run may not call again, or empty if it may
   */
  public Optional<String> exceeded(final String runId, final Map<?, ?> budget) {
    return exceeded(
        runId,
        budget.get(MAX_TOKENS) == null
            ? null
            : (long) Math.ceil(limit(budget, MAX_TOKENS).doubleValue()),
        budget.get(MAX_COST) == null ? null : limit(budget, MAX_COST).doubleValue(),
        budget.get(MAX_CALLS) == null
            ? null
            : (long) Math.ceil(limit(budget, MAX_CALLS).doubleValue()));
  }

  /**
   * Checks a node's configuration.
   *
   * @param config the node configuration
   * @throws IllegalArgumentException if the budget is not usable
   */
  public static void validate(final Map<String, Object> config) {
    if (config.get("budget") instanceof final Map<?, ?> budget) {
      for (final String key : List.of(MAX_TOKENS, MAX_COST, MAX_CALLS)) {
        if (budget.get(key) != null) {
          limit(budget, key);
        }
      }
      validateHardStop(budget);
      if (budget.get(MAX_COST) != null && !(config.get("prices") instanceof Map<?, ?>)) {
        throw new IllegalArgumentException("budget.maxCost needs prices to measure cost");
      }
    }
  }

  private static void validateHardStop(final Map<?, ?> budget) {
    if (budget.get(AGENT) != null && !(budget.get(AGENT) instanceof String)) {
      throw new IllegalArgumentException("budget.agent must be text");
    }
    if (budget.get("hardStop") != null && !(budget.get("hardStop") instanceof Boolean)) {
      throw new IllegalArgumentException("budget.hardStop must be true or false");
    }
  }

  private static Number limit(final Map<?, ?> budget, final String key) {
    if (budget.get(key) instanceof final Number number && number.doubleValue() > 0) {
      return number;
    }
    throw new IllegalArgumentException("budget." + key + " must be a positive number");
  }

  /**
   * Adds what a call used.
   *
   * @param runId the run
   * @param tokens input plus output tokens
   * @param cost cost of the call, 0 if prices are not configured
   */
  public void add(final String runId, final long tokens, final double cost) {
    byRun.merge(
        runId,
        new Spent(tokens, cost, 0),
        (old, added) ->
            new Spent(old.tokens() + added.tokens(), old.cost() + added.cost(), old.calls()));
  }

  /**
   * Counts one call, whatever it cost.
   *
   * @param runId the run
   */
  public void addCall(final String runId) {
    byRun.merge(
        runId,
        new Spent(0, 0, 1),
        (old, added) -> new Spent(old.tokens(), old.cost(), old.calls() + 1));
  }
}
