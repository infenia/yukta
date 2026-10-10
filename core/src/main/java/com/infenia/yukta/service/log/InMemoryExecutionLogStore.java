// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.NoArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Default execution log: kept in memory, so nothing survives a restart. Set {@code
 * yukta.execution-log.dir} to keep the log on disk.
 */
@Component
@NoArgsConstructor
@ConditionalOnExpression("'${yukta.execution-log.dir:}'.isEmpty()")
public class InMemoryExecutionLogStore implements ExecutionLogStore {

  /** The events, oldest first. */
  private final List<ExecutionLogEvent> events = new CopyOnWriteArrayList<>();

  @Override
  public void append(final ExecutionLogEvent event) {
    events.add(event);
  }

  @Override
  public List<ExecutionLogEvent> readAll() {
    return List.copyOf(events);
  }

  @Override
  public void replaceAll(final List<ExecutionLogEvent> kept) {
    events.clear();
    events.addAll(kept);
  }
}
