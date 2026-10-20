// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.messaging.rsocket.RSocketRequester;

/**
 * The RSocket connection of each registered worker, so the control plane can call back into it.
 * RSocket connections are two-way: the worker connected out, and the control plane sends node
 * attempts down the same connection.
 */
public class WorkerConnections {

  /** Connections by worker id. */
  private final Map<String, RSocketRequester> connections = new ConcurrentHashMap<>();

  /** Creates an empty set. */
  public WorkerConnections() {
    super();
  }

  /**
   * Remembers the connection a worker registered on.
   *
   * @param workerId the worker
   * @param requester the way back into the worker
   */
  public void put(final String workerId, final RSocketRequester requester) {
    connections.put(workerId, requester);
  }

  /**
   * Forgets a connection, but only if it is still the given one (a worker that reconnected has a
   * new one that must stay).
   *
   * @param workerId the worker
   * @param requester the connection that closed
   */
  public void remove(final String workerId, final RSocketRequester requester) {
    connections.remove(workerId, requester);
  }

  /**
   * Finds the connection of a worker.
   *
   * @param workerId the worker
   * @return the way into the worker, or empty if it is not connected
   */
  public Optional<RSocketRequester> find(final String workerId) {
    return Optional.ofNullable(connections.get(workerId));
  }
}
