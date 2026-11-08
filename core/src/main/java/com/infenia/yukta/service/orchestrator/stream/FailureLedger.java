// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import com.infenia.yukta.model.workflow.NodePolicy.FailureBudget;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * The failures of one node in one run, checked against its {@link FailureBudget}.
 *
 * <p>Keeps the times of the failures inside the sliding window, so memory is bounded by {@code
 * maxFailures + 1}.
 */
@SuppressWarnings("PMD.AvoidSynchronizedStatement")
public final class FailureLedger {

  /** What the node tolerates. */
  private final FailureBudget budget;

  /** Source of nanoTime values; a test moves it. */
  private final LongSupplier nanoClock;

  /** Times (nanoTime) of the failures inside the window, oldest first. */
  private final Deque<Long> failures = new ArrayDeque<>();

  /**
   * Creates an empty ledger.
   *
   * @param budget what the node tolerates
   */
  public FailureLedger(final FailureBudget budget) {
    this(budget, System::nanoTime);
  }

  /* default */ FailureLedger(final FailureBudget budget, final LongSupplier nanoClock) {
    this.budget = budget;
    this.nanoClock = nanoClock;
  }

  /**
   * Records a failure.
   *
   * @return true if the node has now failed more often than its budget allows
   */
  public boolean record() {
    final long now = nanoClock.getAsLong();
    final long window = budget.window().toNanos();
    synchronized (failures) {
      while (!failures.isEmpty() && now - failures.peekFirst() > window) {
        failures.removeFirst();
      }
      failures.addLast(now);
      return failures.size() > budget.maxFailures();
    }
  }
}
