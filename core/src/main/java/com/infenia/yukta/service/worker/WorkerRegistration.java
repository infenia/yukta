// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.worker;

import java.util.Set;

/**
 * What a worker tells the control plane when it connects.
 *
 * @param workerId the worker's own id; a worker that registers again with the same id replaces its
 *     earlier entry
 * @param pools the pools it serves
 * @param pluginTypes the plugin types it is allowed to run
 * @param capacity how many node attempts it runs at once
 */
public record WorkerRegistration(
    String workerId, Set<String> pools, Set<String> pluginTypes, int capacity) {

  /** Copies the sets, so the registration cannot be changed from outside. */
  public WorkerRegistration {
    pools = Set.copyOf(pools);
    pluginTypes = Set.copyOf(pluginTypes);
  }
}
