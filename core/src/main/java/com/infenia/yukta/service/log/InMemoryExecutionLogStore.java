// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import java.util.ArrayList;
import java.util.List;
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

  /** The events, oldest first; guarded by {@code this}. */
  private List<ExecutionLogEvent> events = new ArrayList<>();

  /** Events appended since the last trim; guarded by {@code this}. */
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
  public synchronized void append(final ExecutionLogEvent event) {
    events.add(event);
    if (++sinceTrim >= compactAfter) {
      sinceTrim = 0;
      events = new ArrayList<>(LogRetention.trim(events, retainedRuns));
    }
  }

  @Override
  public synchronized List<ExecutionLogEvent> readAll() {
    return List.copyOf(events);
  }

  @Override
  public synchronized void replaceAll(final List<ExecutionLogEvent> kept) {
    events = new ArrayList<>(kept);
    sinceTrim = 0;
  }
}
