// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.worker;

import java.time.Instant;
import java.util.Set;

/**
 * A worker that is registered with the control plane.
 *
 * @param workerId the worker's own id
 * @param pools the pools it serves (for example {@code default}, {@code gpu})
 * @param pluginTypes the plugin types it is allowed to run
 * @param capacity how many node attempts it runs at once
 * @param usedSlots how many it runs now, as of its last heartbeat
 * @param draining true while it finishes what it runs and takes nothing new (it is stopping)
 * @param registeredAt when it registered
 * @param leaseUntil when it is dropped unless it sends another heartbeat
 */
public record WorkerInfo(
    String workerId,
    Set<String> pools,
    Set<String> pluginTypes,
    int capacity,
    int usedSlots,
    boolean draining,
    Instant registeredAt,
    Instant leaseUntil) {

  /** Copies the sets, so the entry cannot be changed from outside. */
  public WorkerInfo {
    pools = Set.copyOf(pools);
    pluginTypes = Set.copyOf(pluginTypes);
  }

  /**
   * How many more node attempts the worker can take.
   *
   * @return capacity minus used slots, never negative
   */
  public int freeSlots() {
    return Math.max(0, capacity - usedSlots);
  }
}
