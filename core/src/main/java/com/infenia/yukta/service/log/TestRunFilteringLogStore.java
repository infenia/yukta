// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import com.infenia.yukta.service.testrun.TestRuns;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Keeps test runs out of the log: an event of a reserved workflow ID is dropped on append, so
 * history, search, recovery, supervisor replay, analytics and the audit never see one. Every other
 * call goes to the wrapped store, whichever it is.
 */
@RequiredArgsConstructor
public class TestRunFilteringLogStore implements ExecutionLogStore {

  /** The store that keeps what is not a test run. */
  private final ExecutionLogStore delegate;

  @Override
  public void append(final ExecutionLogEvent event) {
    if (!TestRuns.isTestRun(event.workflowId())) {
      delegate.append(event);
    }
  }

  @Override
  public Flux<ExecutionLogEvent> readAll() {
    return delegate.readAll();
  }

  @Override
  public Mono<Void> replaceAll(final List<ExecutionLogEvent> events) {
    return delegate.replaceAll(events);
  }

  @Override
  public Flux<ExecutionLogEvent> forExecution(final String executionId) {
    return delegate.forExecution(executionId);
  }

  @Override
  public Mono<RunHeader> header(final String executionId) {
    return delegate.header(executionId);
  }

  @Override
  public Flux<ExecutionLogEvent> ofType(final ExecutionLogEvent.Type type, final Instant since) {
    return delegate.ofType(type, since);
  }

  @Override
  public Flux<ExecutionLogEvent> newest(
      final Set<ExecutionLogEvent.Type> types, final String executionId, final int limit) {
    return delegate.newest(types, executionId, limit);
  }
}
