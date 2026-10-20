// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One message on the stream between a worker and the control plane. Phase 1 only carries the
 * worker's registration and lease; node frames come with remote execution.
 *
 * @param type what the frame is
 * @param token the shared secret of the control plane (REGISTER only)
 * @param workerId the worker's id (REGISTER only)
 * @param pools the pools the worker serves (REGISTER only)
 * @param pluginTypes the plugin types the worker may run (REGISTER only)
 * @param capacity how many node attempts the worker runs at once (REGISTER only)
 * @param usedSlots how many it runs now (LEASE only)
 * @param draining true if the worker is stopping and takes no new attempts (LEASE only)
 * @param message why the control plane refused the worker (REJECT only)
 */
public record WorkerFrame(
    Type type,
    @Nullable String token,
    @Nullable String workerId,
    Set<String> pools,
    Set<String> pluginTypes,
    int capacity,
    int usedSlots,
    boolean draining,
    @Nullable String message) {

  /** Kind of frame. */
  public enum Type {
    /** Worker to control plane: the first frame; announces the worker. */
    REGISTER,
    /** Worker to control plane: renews the lease. */
    LEASE,
    /** Control plane to worker: the frame was accepted. */
    ACK,
    /** Control plane to worker: the worker is refused and must reconnect. */
    REJECT
  }

  /** Copies the sets. */
  public WorkerFrame {
    pools = pools == null ? Set.of() : Set.copyOf(pools);
    pluginTypes = pluginTypes == null ? Set.of() : Set.copyOf(pluginTypes);
  }

  /**
   * The registration a worker opens its stream with.
   *
   * @param token the control plane's shared secret
   * @param registration what the worker announces
   * @return the frame
   */
  public static WorkerFrame register(
      final String token, final com.infenia.yukta.service.worker.WorkerRegistration registration) {
    return new WorkerFrame(
        Type.REGISTER,
        token,
        registration.workerId(),
        registration.pools(),
        registration.pluginTypes(),
        registration.capacity(),
        0,
        false,
        null);
  }

  /**
   * A lease renewal.
   *
   * @param usedSlots how many node attempts the worker runs now
   * @param draining true if the worker is stopping and takes no new attempts
   * @return the frame
   */
  public static WorkerFrame lease(final int usedSlots, final boolean draining) {
    return new WorkerFrame(
        Type.LEASE, null, null, Set.of(), Set.of(), 0, usedSlots, draining, null);
  }

  /**
   * The answer to an accepted frame.
   *
   * @return the frame
   */
  public static WorkerFrame ack() {
    return new WorkerFrame(Type.ACK, null, null, Set.of(), Set.of(), 0, 0, false, null);
  }

  /**
   * The answer to a refused worker.
   *
   * @param why the reason
   * @return the frame
   */
  public static WorkerFrame reject(final String why) {
    return new WorkerFrame(Type.REJECT, null, null, Set.of(), Set.of(), 0, 0, false, why);
  }
}
