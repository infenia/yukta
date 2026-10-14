// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the LLM calls of each run have used so far, so a node can stop a run that loops on a model.
 * The newest {@value #MAX_RUNS} runs are remembered; an older run starts again from zero.
 */
public final class RunBudgets {

  /** Option: most tokens. */
  private static final String MAX_TOKENS = "maxTokens";

  /** Option: most cost. */
  private static final String MAX_COST = "maxCost";

  /** Most runs remembered. */
  public static final int MAX_RUNS = 10_000;

  /** Use by run, oldest first; the synchronized wrapper makes {@code merge} atomic. */
  private final Map<String, Spent> byRun =
      Collections.synchronizedMap(
          new LinkedHashMap<>() {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(final Map.Entry<String, Spent> eldest) {
              return size() > MAX_RUNS;
            }
          });

  /** Tokens and cost one run has used. */
  private record Spent(long tokens, double cost) {}

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
    final Spent spent = byRun.get(runId);
    Optional<String> why = Optional.empty();
    if (spent != null && maxTokens != null && spent.tokens() >= maxTokens) {
      why = Optional.of("token budget of " + maxTokens + " used (" + spent.tokens() + ")");
    } else if (spent != null && maxCost != null && spent.cost() >= maxCost) {
      why = Optional.of("cost budget of " + maxCost + " used (" + spent.cost() + ")");
    }
    return why;
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
        budget.get(MAX_TOKENS) == null ? null : limit(budget, MAX_TOKENS).longValue(),
        budget.get(MAX_COST) == null ? null : limit(budget, MAX_COST).doubleValue());
  }

  /**
   * Checks a node's configuration.
   *
   * @param config the node configuration
   * @throws IllegalArgumentException if the budget is not usable
   */
  public static void validate(final Map<String, Object> config) {
    if (config.get("budget") instanceof final Map<?, ?> budget) {
      for (final String key : List.of(MAX_TOKENS, MAX_COST)) {
        if (budget.get(key) != null) {
          limit(budget, key);
        }
      }
      if (budget.get("hardStop") != null && !(budget.get("hardStop") instanceof Boolean)) {
        throw new IllegalArgumentException("budget.hardStop must be true or false");
      }
      if (budget.get(MAX_COST) != null && !(config.get("prices") instanceof Map<?, ?>)) {
        throw new IllegalArgumentException("budget.maxCost needs prices to measure cost");
      }
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
        new Spent(tokens, cost),
        (old, added) -> new Spent(old.tokens() + added.tokens(), old.cost() + added.cost()));
  }
}
