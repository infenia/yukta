// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.workers;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.service.worker.RemoteNodeExecutor;
import com.infenia.yukta.service.worker.WorkerInfo;
import com.infenia.yukta.service.worker.WorkerRegistry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.messaging.rsocket.RSocketRequester;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs node attempts on workers over their RSocket connection: one {@code requestChannel} per
 * attempt, with the node's input going down and its output coming back, so back-pressure and
 * cancellation work end to end. A worker is chosen from the pool: it must be connected, allow the
 * plugin type and have a free slot; the one with the most free slots wins.
 */
public class WorkerNodeExecutor implements RemoteNodeExecutor {

  /** Type of a message as plain JSON values. */
  private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

  /** Class name whose failures keep their meaning: bad input is not retried. */
  private static final String BAD_INPUT = IllegalArgumentException.class.getName();

  /** The live workers. */
  private final WorkerRegistry registry;

  /** The way into each worker. */
  private final WorkerConnections connections;

  /** Converts messages to and from plain JSON values. */
  private final ObjectMapper mapper;

  /** Attempts this server has sent to each worker and not seen end yet. */
  private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

  /**
   * Creates the executor.
   *
   * @param registry the live workers
   * @param connections the way into each worker
   * @param mapper converts messages to and from plain JSON values
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // all three are shared Spring beans
  public WorkerNodeExecutor(
      final WorkerRegistry registry,
      final WorkerConnections connections,
      final ObjectMapper mapper) {
    this.registry = registry;
    this.connections = connections;
    this.mapper = mapper;
  }

  @Override
  public Flux<Message<?>> run(
      final String pluginType,
      final String pool,
      final Flux<Message<?>> input,
      final Map<String, Object> config) {
    return Flux.deferContextual(
        ctx -> {
          final Optional<Chosen> chosen = pick(pool, pluginType);
          if (chosen.isEmpty()) {
            return Flux.error(
                new WorkflowExecutionException(
                    "No connected worker in pool '"
                        + pool
                        + "' can run "
                        + pluginType
                        + " right now"));
          }
          final String workerId = chosen.get().info().workerId();
          final AtomicInteger load = inFlight.computeIfAbsent(workerId, _ -> new AtomicInteger());
          load.incrementAndGet();
          final Runnable heartbeat =
              ctx.hasKey(WorkflowContext.HEARTBEAT_KEY)
                  ? ctx.get(WorkflowContext.HEARTBEAT_KEY)
                  : () -> {};
          final Flux<NodeFrame> out =
              Flux.concat(
                  Mono.just(
                      NodeFrame.start(
                          ctx.getOrDefault("executionId", "unknown"),
                          ctx.getOrDefault("nodeId", "unknown"),
                          pluginType,
                          config)),
                  input.map(this::frame),
                  Mono.just(NodeFrame.complete()));
          return chosen
              .get()
              .requester()
              .route("node.run")
              .data(out)
              .retrieveFlux(NodeFrame.class)
              .<Message<?>>handle((frame, sink) -> answer(workerId, frame, heartbeat, sink))
              .onErrorMap(WorkerNodeExecutor::isTransport, error -> lost(workerId, error))
              .doFinally(_ -> load.decrementAndGet());
        });
  }

  /** A worker that may run the attempt, with the way into it. */
  /* default */ record Chosen(WorkerInfo info, RSocketRequester requester) {}

  /** Picks the connected worker of the pool with the most free slots that may run the type. */
  /* default */ Optional<Chosen> pick(final String pool, final String pluginType) {
    return registry.list().stream()
        .filter(info -> info.pools().contains(pool) && info.pluginTypes().contains(pluginType))
        .filter(info -> free(info) > 0)
        .flatMap(
            info ->
                connections
                    .find(info.workerId())
                    .map(requester -> new Chosen(info, requester))
                    .stream())
        .max(
            Comparator.comparingInt((Chosen chosen) -> free(chosen.info()))
                .thenComparing(chosen -> chosen.info().workerId(), Comparator.reverseOrder()));
  }

  /** Free slots, counting both what the worker reported and what this server has sent it. */
  private int free(final WorkerInfo info) {
    final int sent = inFlight.getOrDefault(info.workerId(), new AtomicInteger()).get();
    return info.capacity() - Math.max(info.usedSlots(), sent);
  }

  /**
   * Whether an error came from the connection (the worker went away, or answered with a transport
   * error) and not from the plugin: those are reported as {@link WorkflowExecutionException}s the
   * engine can retry, and a retry picks a worker again, so it may land on another one.
   */
  private static boolean isTransport(final Throwable error) {
    return !(error instanceof WorkflowExecutionException)
        && !(error instanceof IllegalArgumentException);
  }

  private static WorkflowExecutionException lost(final String workerId, final Throwable error) {
    return new WorkflowExecutionException(
        "Lost worker " + workerId + " during the attempt: " + error, error);
  }

  private NodeFrame frame(final Message<?> message) {
    return NodeFrame.message(mapper.convertValue(message, JSON_OBJECT));
  }

  /* default */ void answer(
      final String workerId,
      final NodeFrame frame,
      final Runnable heartbeat,
      final reactor.core.publisher.SynchronousSink<Message<?>> sink) {
    switch (frame.kind()) {
      case MESSAGE -> sink.next(mapper.convertValue(frame.message(), DefaultMessage.class));
      case HEARTBEAT -> heartbeat.run();
      case COMPLETE -> sink.complete();
      case ERROR -> sink.error(failure(workerId, frame));
      default -> sink.error(new WorkflowExecutionException("Unexpected frame from " + workerId));
    }
  }

  private static RuntimeException failure(final String workerId, final NodeFrame frame) {
    return BAD_INPUT.equals(frame.errorClass())
        ? new IllegalArgumentException(frame.errorMessage())
        : new WorkflowExecutionException(
            "Worker " + workerId + " failed: " + frame.errorClass() + ": " + frame.errorMessage());
  }
}
