// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginLogSink;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.service.plugin.PluginRegistry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.observation.transport.ReceiverContext;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.publisher.Sinks;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The worker's side of a node attempt: runs the plugin the control plane asks for, with the input
 * it sends, and sends the output back. Only plugin types on the worker's own allow list run, so a
 * compromised control plane cannot make a worker run something it did not agree to. Cancelling the
 * stream cancels the plugin.
 */
@Controller
public class NodeRunner {

  /** Type of a message as plain JSON values. */
  private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

  /** How many causes of a failure are sent to the control plane. */
  private static final int MAX_CAUSES = 10;

  /** Longest a frame waits while another thread sends one on the same attempt. */
  private static final java.time.Duration EMIT_WAIT = java.time.Duration.ofSeconds(1);

  /** Finds the plugins of this server. */
  private final PluginRegistry plugins;

  /** The plugin types this worker agreed to run. */
  private final Set<String> allowed;

  /** Converts messages to and from plain JSON values. */
  private final ObjectMapper mapper;

  /** Attempts running now. */
  private final AtomicInteger active = new AtomicInteger();

  /** Traces each attempt as a child of the control plane's span. */
  private final ObservationRegistry observations;

  /**
   * Creates the runner.
   *
   * @param plugins finds the plugins of this server
   * @param allowed the plugin types this worker agreed to run
   * @param mapper converts messages to and from plain JSON values
   * @param observations traces each attempt (a no-op registry traces nothing)
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // the mapper and the registry are shared Spring beans
  public NodeRunner(
      final PluginRegistry plugins,
      final Set<String> allowed,
      final ObjectMapper mapper,
      final ObservationRegistry observations) {
    this.plugins = plugins;
    this.allowed = Set.copyOf(allowed);
    this.mapper = mapper;
    this.observations = observations;
  }

  /**
   * How many attempts run now; the agent reports it as the worker's used slots and waits for it to
   * reach zero when the worker drains.
   *
   * @return the number of running attempts
   */
  public int running() {
    return active.get();
  }

  /**
   * One node attempt.
   *
   * @param frames what the control plane sends: START, the input, COMPLETE
   * @return what the worker sends back: the output, heartbeats, then COMPLETE or ERROR
   */
  @MessageMapping("node.run")
  public Flux<NodeFrame> run(final Flux<NodeFrame> frames) {
    return frames.switchOnFirst(this::serve);
  }

  private Flux<NodeFrame> serve(
      final Signal<? extends NodeFrame> first, final Flux<NodeFrame> all) {
    final NodeFrame start = first.get();
    final Optional<String> problem = problem(start);
    Flux<NodeFrame> result =
        Flux.just(
            NodeFrame.error(
                IllegalArgumentException.class.getName(),
                problem.orElse(""),
                types(new IllegalArgumentException())));
    if (problem.isEmpty()) {
      active.incrementAndGet();
      final ProcessorPlugin plugin = (ProcessorPlugin) plugins.get(start.pluginType());
      final Map<String, Object> config = start.config() != null ? start.config() : Map.of();
      final Flux<Message<?>> input =
          all.skip(1)
              .takeWhile(frame -> frame.kind() == NodeFrame.Kind.MESSAGE)
              .map(frame -> mapper.convertValue(frame.message(), DefaultMessage.class));
      // heartbeats and live log lines, which a plugin may send from several threads at once
      final Sinks.Many<NodeFrame> beats = Sinks.many().unicast().onBackpressureBuffer();
      final Consumer<NodeFrame> send =
          frame -> beats.emitNext(frame, Sinks.EmitFailureHandler.busyLooping(EMIT_WAIT));
      final Runnable heartbeat = () -> send.accept(NodeFrame.heartbeat());
      final PluginLogSink logSink =
          (stream, line, level) -> send.accept(NodeFrame.log(stream, line, level));
      final Observation attempt = attempt(start);
      final Flux<NodeFrame> work =
          plugin
              .initialize(config)
              .then(Mono.defer(() -> plugin.prepare(config)))
              .thenMany(Flux.defer(() -> plugin.process(input, config)))
              .map(message -> NodeFrame.message(mapper.convertValue(message, JSON_OBJECT)))
              .concatWith(Mono.just(NodeFrame.complete()))
              .onErrorResume(error -> {
                attempt.error(error);
                return Flux.just(
                    NodeFrame.error(
                        error.getClass().getName(),
                        String.valueOf(error.getMessage()),
                        types(error)));
              })
              .contextWrite(ctx ->
                  ctx.put("executionId", String.valueOf(start.executionId()))
                      .put("nodeId", String.valueOf(start.nodeId()))
                      .put(WorkflowContext.HEARTBEAT_KEY, heartbeat)
                      .put(WorkflowContext.LOG_SINK_KEY, logSink)
                      // the plugin's own observations nest under the attempt
                      .put(ObservationThreadLocalAccessor.KEY, attempt));
      result =
          Flux.defer(
                  () -> {
                    attempt.start();
                    return work.mergeWith(beats.asFlux());
                  })
              .takeUntil(frame ->
                  frame.kind() == NodeFrame.Kind.COMPLETE || frame.kind() == NodeFrame.Kind.ERROR)
              .doFinally(_ -> {
                attempt.stop();
                active.decrementAndGet();
              });
    }
    return result;
  }

  /**
   * The attempt's observation, continuing the trace from the headers in START: a tracer's
   * propagating handler makes its span a child of the control plane's. Without headers it starts a
   * trace of its own.
   */
  private Observation attempt(final NodeFrame start) {
    final Map<String, Object> trace = start.message() != null ? start.message() : Map.of();
    final ReceiverContext<Map<String, Object>> received =
        new ReceiverContext<>((carrier, key) -> {
          final Object value = carrier.get(key);
          return value == null ? null : String.valueOf(value);
        });
    received.setCarrier(trace);
    final String nodeId = String.valueOf(start.nodeId());
    return Observation.createNotStarted("yukta.worker.attempt", () -> received, observations)
        .contextualName("attempt " + nodeId)
        .lowCardinalityKeyValue("module", String.valueOf(start.pluginType()))
        .highCardinalityKeyValue("executionId", String.valueOf(start.executionId()))
        .highCardinalityKeyValue("nodeId", nodeId);
  }

  /** Why the attempt cannot run here, or empty if it can. */
  private Optional<String> problem(final NodeFrame start) {
    return start != null && start.kind() == NodeFrame.Kind.START
        ? typeProblem(start.pluginType())
        : Optional.of("the first frame must be START");
  }

  private Optional<String> typeProblem(final String type) {
    final Optional<String> why;
    if (allowed.contains(type)) {
      why =
          plugins.get(type) instanceof ProcessorPlugin
              ? Optional.empty()
              : Optional.of(type + " is not a processor on this worker");
    } else {
      why = Optional.of("this worker does not run " + type);
    }
    return why;
  }

  /** Class names of a failure, its superclasses and its causes, so the engine can match them. */
  /* default */ static List<String> types(final Throwable error) {
    final Set<String> names = new LinkedHashSet<>();
    Throwable cause = error;
    // bounded, so a cause chain that loops back on itself cannot spin forever
    for (int depth = 0; cause != null && depth < MAX_CAUSES; depth++) {
      for (Class<?> type = cause.getClass(); type != Object.class; type = type.getSuperclass()) {
        names.add(type.getName());
      }
      cause = cause.getCause();
    }
    return List.copyOf(names);
  }
}
