// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.archive;

import com.infenia.yukta.config.YuktaProperties;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Keeps the most recent executions restartable, running or finished.
 *
 * <p>Holding an execution here is what lets a failed or finished run be restarted: its graph and
 * payload are kept, and the owner of the recorded node outputs ({@code NodeCheckpointStore}) is
 * told to drop them when the execution falls out of the archive.
 */
@Component
@SuppressWarnings("PMD.AvoidSynchronizedStatement")
public class ExecutionArchive {

  /** Default number of executions kept. */
  private static final int DEFAULT_RETAINED = 10;

  /** Archived executions by id. */
  private final Map<String, ArchivedExecution> byId = new ConcurrentHashMap<>();

  /** Execution ids, oldest first. */
  private final Deque<String> order = new ArrayDeque<>();

  /** Number of executions kept. */
  private final int retained;

  /**
   * Creates an archive with the configured size.
   *
   * @param properties the {@code yukta.restart} settings: executions kept
   */
  @Autowired
  public ExecutionArchive(final YuktaProperties properties) {
    this(properties.restart().retainedExecutions());
  }

  /**
   * Creates an archive of the given size.
   *
   * @param retained number of executions kept
   */
  public ExecutionArchive(final int retained) {
    this.retained = Math.max(1, retained);
  }

  /** Creates an archive with the default size. */
  public ExecutionArchive() {
    this(DEFAULT_RETAINED);
  }

  /**
   * Remembers an execution.
   *
   * @param execution the execution
   * @return the ids of executions that fell out of the archive, whose recorded outputs can go
   */
  public List<String> put(final ArchivedExecution execution) {
    synchronized (order) {
      if (byId.put(execution.executionId(), execution) == null) {
        order.addLast(execution.executionId());
      }
      final List<String> evicted = new java.util.ArrayList<>();
      while (order.size() > retained) {
        final String oldest = order.removeFirst();
        byId.remove(oldest);
        evicted.add(oldest);
      }
      return evicted;
    }
  }

  /**
   * Looks an execution up.
   *
   * @param executionId the execution
   * @return the execution, or empty if it was never archived or has been evicted
   */
  public Optional<ArchivedExecution> find(final String executionId) {
    return Optional.ofNullable(byId.get(executionId));
  }
}
