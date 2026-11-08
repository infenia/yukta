// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator;

import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.store.InMemoryNodeCheckpointStore;

/**
 * Short forms of {@link ExecutionRecovery} for tests: the shapes its extra constructors used to
 * offer.
 */
public final class ExecutionRecoveryFixture {

  private ExecutionRecoveryFixture() {}

  /** A ExecutionRecovery with the default for what the parameters leave out. */
  public static ExecutionRecovery create(
      final ExecutionLogStore store, final TaskTrackerService tracker, final int retainedRuns) {
    return new ExecutionRecovery(store, tracker, retainedRuns, new InMemoryNodeCheckpointStore());
  }
}
