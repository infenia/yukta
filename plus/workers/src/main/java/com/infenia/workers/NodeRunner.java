// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.service.plugin.PluginRegistry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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

  /** Finds the plugins of this server. */
  private final PluginRegistry plugins;

  /** The plugin types this worker agreed to run. */
  private final Set<String> allowed;

  /** Converts messages to and from plain JSON values. */
  private final ObjectMapper mapper;

  /** Attempts running now. */
  private final AtomicInteger active = new AtomicInteger();

  /**
   * Creates the runner.
   *
   * @param plugins finds the plugins of this server
   * @param allowed the plugin types this worker agreed to run
   * @param mapper converts messages to and from plain JSON values
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // the mapper is a shared Spring bean
  public NodeRunner(
      final PluginRegistry plugins, final Set<String> allowed, final ObjectMapper mapper) {
    this.plugins = plugins;
    this.allowed = Set.copyOf(allowed);
    this.mapper = mapper;
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
        Flux.just(NodeFrame.error(IllegalArgumentException.class.getName(), problem.orElse("")));
    if (problem.isEmpty()) {
      active.incrementAndGet();
      final ProcessorPlugin plugin = (ProcessorPlugin) plugins.get(start.pluginType());
      final Map<String, Object> config = start.config() != null ? start.config() : Map.of();
      final Flux<Message<?>> input =
          all.skip(1)
              .takeWhile(frame -> frame.kind() == NodeFrame.Kind.MESSAGE)
              .map(frame -> mapper.convertValue(frame.message(), DefaultMessage.class));
      final Sinks.Many<NodeFrame> beats = Sinks.many().unicast().onBackpressureBuffer();
      final Runnable heartbeat = () -> beats.tryEmitNext(NodeFrame.heartbeat());
      final Flux<NodeFrame> work =
          plugin
              .initialize(config)
              .then(Mono.defer(() -> plugin.prepare(config)))
              .thenMany(Flux.defer(() -> plugin.process(input, config)))
              .map(message -> NodeFrame.message(mapper.convertValue(message, JSON_OBJECT)))
              .concatWith(Mono.just(NodeFrame.complete()))
              .onErrorResume(
                  error ->
                      Flux.just(
                          NodeFrame.error(
                              error.getClass().getName(), String.valueOf(error.getMessage()))))
              .contextWrite(
                  ctx ->
                      ctx.put("executionId", String.valueOf(start.executionId()))
                          .put("nodeId", String.valueOf(start.nodeId()))
                          .put(WorkflowContext.HEARTBEAT_KEY, heartbeat));
      result =
          work.mergeWith(beats.asFlux())
              .takeUntil(
                  frame ->
                      frame.kind() == NodeFrame.Kind.COMPLETE
                          || frame.kind() == NodeFrame.Kind.ERROR)
              .doFinally(_ -> active.decrementAndGet());
    }
    return result;
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
}
