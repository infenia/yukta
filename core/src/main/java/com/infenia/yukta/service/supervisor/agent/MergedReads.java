// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.agent;

import com.infenia.yukta.plugin.control.engine.EngineReads;
import com.infenia.yukta.plugin.input.InputRequest;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * What the agent of a supervisor may read when the supervisor has several attachments: the union of
 * what each attachment's engine reads. A supervisor with one attachment reads through that
 * attachment directly, never through this class.
 */
final class MergedReads implements EngineReads {

  private final List<EngineReads> parts;

  /* default */ MergedReads(final List<EngineReads> parts) {
    this.parts = List.copyOf(parts);
  }

  @Override
  public Flux<RunView> executions(final @Nullable String status, final int limit) {
    return Flux.fromIterable(parts)
        .flatMap(part -> part.executions(status, limit))
        .distinct(RunView::executionId)
        .sort(
            Comparator.comparing(
                RunView::startTime, Comparator.nullsLast(Comparator.reverseOrder())))
        .take(limit);
  }

  @Override
  public Flux<InputRequest> openInputs(final int limit) {
    return Flux.fromIterable(parts)
        .flatMap(part -> part.openInputs(limit))
        .distinct(InputRequest::id)
        .take(limit);
  }

  @Override
  public Flux<String> held() {
    return Flux.fromIterable(parts).flatMap(EngineReads::held).distinct();
  }

  @Override
  public Mono<RunDetail> execution(final String executionId) {
    return Flux.fromIterable(parts).concatMap(part -> part.execution(executionId)).next();
  }

  // ponytail: the first attachment that knows the workflow answers, so two attachments on
  // different nodes of one workflow show the nodes of only the first; merge the nodes if needed
  @Override
  public Mono<WorkflowView> workflow(final String sessionId, final String workflowId) {
    return Flux.fromIterable(parts).concatMap(part -> part.workflow(sessionId, workflowId)).next();
  }

  @Override
  public Flux<String> logs(final String executionId, final int tail) {
    return Flux.fromIterable(parts)
        .concatMap(part -> part.logs(executionId, tail).collectList())
        .filter(lines -> !lines.isEmpty())
        .next()
        .flatMapMany(Flux::fromIterable);
  }

  @Override
  public Mono<SchedulerView> scheduler() {
    return Flux.fromIterable(parts).concatMap(EngineReads::scheduler).next();
  }
}
