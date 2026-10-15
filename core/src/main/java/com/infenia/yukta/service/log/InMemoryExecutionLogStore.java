// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Default execution log: kept in memory, so nothing survives a restart. Set {@code
 * yukta.execution-log.dir} to keep the log on disk.
 *
 * <p>Like the file log, it is trimmed to the newest {@code retained-runs} runs after every {@code
 * compact-after} appended events; a run without an end is never dropped.
 */
@Component
@ConditionalOnExpression(
    "'${yukta.execution-log.dir:}'.isEmpty()"
        + " && !'postgres'.equals('${yukta.session.store-type:in-memory}')")
public class InMemoryExecutionLogStore implements ExecutionLogStore {

  /** Runs that survive a trim. */
  private final int retainedRuns;

  /** Appended events after which the log is trimmed. */
  private final int compactAfter;

  /** Guards {@link #events} and {@link #sinceTrim}. */
  private final ReentrantLock lock = new ReentrantLock();

  /** The events, oldest first. */
  private List<ExecutionLogEvent> events = new ArrayList<>();

  /** Events appended since the last trim. */
  private int sinceTrim;

  /** Creates a store with the default limits. */
  public InMemoryExecutionLogStore() {
    this(500, 10_000);
  }

  /**
   * Creates a store.
   *
   * @param retainedRuns runs kept when the log is trimmed
   * @param compactAfter appended events after which the log is trimmed
   */
  @Autowired
  public InMemoryExecutionLogStore(
      @Value("${yukta.execution-log.retained-runs:500}") final int retainedRuns,
      @Value("${yukta.execution-log.compact-after:10000}") final int compactAfter) {
    this.retainedRuns = Math.max(1, retainedRuns);
    this.compactAfter = Math.max(1, compactAfter);
  }

  @Override
  public void append(final ExecutionLogEvent event) {
    lock.lock();
    try {
      events.add(event);
      sinceTrim++;
      if (sinceTrim >= compactAfter) {
        sinceTrim = 0;
        events = new ArrayList<>(LogRetention.trim(events, retainedRuns));
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public List<ExecutionLogEvent> readAll() {
    lock.lock();
    try {
      return List.copyOf(events);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void replaceAll(final List<ExecutionLogEvent> kept) {
    lock.lock();
    try {
      events = new ArrayList<>(kept);
      sinceTrim = 0;
    } finally {
      lock.unlock();
    }
  }
}
