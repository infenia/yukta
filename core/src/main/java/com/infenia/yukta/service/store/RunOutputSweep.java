// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.store;

import java.util.Set;

/**
 * A durable checkpoint store that can drop the output of runs that are no longer in the execution
 * log. {@code ExecutionRecovery} calls it at startup and on takeover, when no run of this server is
 * going yet.
 */
@FunctionalInterface
public interface RunOutputSweep {

  /**
   * Removes the output of every run that is not in {@code kept}: what an earlier process left for
   * runs that are no longer in the execution log would otherwise stay for good.
   *
   * @param kept the ids of the runs whose output is still wanted
   */
  void removeNotIn(Set<String> kept);
}
