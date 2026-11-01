// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.result;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * What reaches the terminal nodes of each execution, as a stream a client can follow live and
 * replay later. The live stream is complete; only the replay buffer is bounded ({@code
 * yukta.results.retained-events} per execution), and the end events say when it dropped anything.
 */
@Service
public class ExecutionResultService {

  /** Result streams by execution. */
  private final Cache<String, ResultChannel> channels;

  /** Events kept per execution for subscribers that connect late. */
  private final int retainedEvents;

  /**
   * Creates the service.
   *
   * @param retainedEvents events kept per execution for replay
   * @param retention how long an execution's results stay available after they were last used
   * @param maxExecutions how many finished executions are kept at most (live ones are never
   *     evicted)
   */
  public ExecutionResultService(
      @Value("${yukta.results.retained-events:1000}") final int retainedEvents,
      @Value("${yukta.results.retention:1h}") final Duration retention,
      @Value("${yukta.results.max-executions:200}") final int maxExecutions) {
    this.retainedEvents = Math.max(1, retainedEvents);
    this.channels =
        Caffeine.newBuilder()
            .expireAfterAccess(retention)
            // A live run weighs 0, so size eviction only ever takes finished runs.
            .maximumWeight(maxExecutions)
            .weigher((String id, ResultChannel channel) -> channel.finished() ? 1 : 0)
            .build();
  }

  /**
   * Records a message that reached a terminal node.
   *
   * @param executionId the execution
   * @param nodeId the terminal node
   * @param payload the message payload
   */
  public void publish(final String executionId, final String nodeId, final Object payload) {
    channel(executionId).emit(ResultEvent.Kind.RESULT, nodeId, payload, null);
  }

  /**
   * Records that a terminal node finished.
   *
   * @param executionId the execution
   * @param nodeId the terminal node
   * @param status the node's final status
   */
  public void nodeEnd(final String executionId, final String nodeId, final String status) {
    channel(executionId).emit(ResultEvent.Kind.NODE_END, nodeId, null, status);
  }

  /**
   * Records that the execution finished and ends its stream.
   *
   * @param executionId the execution
   * @param status the execution's final status
   */
  public void complete(final String executionId, final String status) {
    final ResultChannel channel = channel(executionId);
    channel.finish(status);
    channels.put(executionId, channel); // re-weigh: now it counts toward the cap
  }

  /**
   * Follows an execution's results: what is kept first, then live events, ending with the
   * execution's end event.
   *
   * @param executionId the execution
   * @return the events
   */
  public Flux<ResultEvent> stream(final String executionId) {
    return Flux.defer(() -> channel(executionId).events());
  }

  /**
   * Reads the results recorded so far, with the cursor to continue from.
   *
   * @param executionId the execution
   * @param afterCursor the {@code nextCursor} of an earlier read, or 0 for everything kept
   * @return the results; empty and incomplete if nothing was recorded
   */
  public ExecutionResults read(final String executionId, final long afterCursor) {
    final long from = Math.max(0, afterCursor);
    final ResultChannel channel = channels.getIfPresent(executionId);
    final List<ResultEvent> events = channel == null ? List.of() : channel.since(from);
    final boolean complete =
        !events.isEmpty() && events.getLast().kind() == ResultEvent.Kind.EXECUTION_END;
    return new ExecutionResults(
        executionId,
        complete,
        complete ? events.getLast().status() : null,
        events.stream().anyMatch(ResultEvent::truncated),
        events.isEmpty() ? from : events.getLast().sequence() + 1,
        events);
  }

  /**
   * Tells whether any result data exists for an execution.
   *
   * @param executionId the execution
   * @return true if results were recorded and not yet evicted
   */
  public boolean has(final String executionId) {
    return channels.getIfPresent(executionId) != null;
  }

  private ResultChannel channel(final String executionId) {
    return channels.get(executionId, _ -> new ResultChannel(retainedEvents));
  }
}
