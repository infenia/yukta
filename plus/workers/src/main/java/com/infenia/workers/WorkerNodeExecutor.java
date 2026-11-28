// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.plugin.core.PluginLogSink;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.exception.RemoteFailureException;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.service.worker.RemoteNodeExecutor;
import com.infenia.yukta.service.worker.WorkerInfo;
import com.infenia.yukta.service.worker.WorkerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.SenderContext;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import lombok.extern.slf4j.Slf4j;
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
@SuppressWarnings({
  "PMD.CouplingBetweenObjects",
  "PMD.ExcessiveImports",
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals"
}) // picks, sends, traces, maps; the same key names in the context and the trace
@Slf4j
public class WorkerNodeExecutor implements RemoteNodeExecutor {

  /** Type of a message as plain JSON values. */
  private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

  /** The live workers. */
  private final WorkerRegistry registry;

  /** The way into each worker. */
  private final WorkerConnections connections;

  /** Converts messages to and from plain JSON values. */
  private final ObjectMapper mapper;

  /** Finds the open observation of a node (run, node), the parent of an attempt's span. */
  private final BiFunction<String, String, Observation> parentOf;

  /** Opens the attempt's observation, which writes the trace headers into START. */
  private final ObservationRegistry observations;

  /** Times the attempts and their dispatch. */
  private final MeterRegistry meters;

  /** Keeps a worker that keeps failing out of the picks for a while. */
  private final WorkerHealth health;

  /** Attempts this server has sent to each worker and not seen end yet. */
  private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

  /**
   * Creates the executor.
   *
   * @param registry the live workers
   * @param connections the way into each worker
   * @param mapper converts messages to and from plain JSON values
   * @param parentOf finds the open observation of a node from its run and node id, or null
   * @param observations opens the attempt's observation (a no-op registry traces nothing)
   * @param meters times the attempts ({@code yukta.worker.attempts{module,outcome}}) and the wait
   *     from the pick to the worker's first answer ({@code yukta.worker.dispatch{module}})
   */
  public WorkerNodeExecutor(
      final WorkerRegistry registry,
      final WorkerConnections connections,
      final ObjectMapper mapper,
      final BiFunction<String, String, Observation> parentOf,
      final ObservationRegistry observations,
      final MeterRegistry meters) {
    this(
        registry,
        connections,
        mapper,
        parentOf,
        observations,
        meters,
        new WorkerHealth(System::nanoTime));
  }

  /** Creates the executor with its own record of worker health (tests pass one with a clock). */
  /* default */ WorkerNodeExecutor(
      final WorkerRegistry registry,
      final WorkerConnections connections,
      final ObjectMapper mapper,
      final BiFunction<String, String, Observation> parentOf,
      final ObservationRegistry observations,
      final MeterRegistry meters,
      final WorkerHealth health) {
    this.registry = registry;
    this.connections = connections;
    this.mapper = mapper;
    this.parentOf = parentOf;
    this.observations = observations;
    this.meters = meters;
    this.health = health;
  }

  @Override
  public Flux<Message<?>> run(
      final String pluginType,
      final String pool,
      final Flux<Message<?>> input,
      final Map<String, Object> config) {
    return Flux.deferContextual(ctx -> {
      final long picked = System.nanoTime();
      final Optional<Chosen> chosen = pick(pool, pluginType);
      if (chosen.isEmpty()) {
        attempts(pluginType, "no_worker").record(Duration.ZERO);
        return Flux.error(
            new WorkflowExecutionException(
                "No connected worker in pool '" + pool + "' can run " + pluginType + " right now"));
      }
      final String workerId = chosen.get().info().workerId();
      final AtomicInteger load = load(workerId);
      final Runnable heartbeat =
          ctx.hasKey(WorkflowContext.HEARTBEAT_KEY)
              ? ctx.get(WorkflowContext.HEARTBEAT_KEY)
              : () -> {};
      final PluginLogSink logSink =
          ctx.getOrDefault(WorkflowContext.LOG_SINK_KEY, PluginLogSink.NOOP);
      final String executionId = ctx.getOrDefault("executionId", "unknown");
      final String nodeId = ctx.getOrDefault("nodeId", "unknown");
      final Map<String, String> trace = new ConcurrentHashMap<>();
      final AtomicBoolean answered = new AtomicBoolean();
      final AtomicReference<String> outcome = new AtomicReference<>("cancelled");
      final Observation call = call(workerId, executionId, nodeId, pluginType, trace).start();
      final Flux<NodeFrame> out =
          Flux.concat(
              Mono.just(NodeFrame.start(executionId, nodeId, pluginType, config, trace)),
              input.map(this::frame),
              Mono.just(NodeFrame.complete()));
      return chosen
          .get()
          .requester()
          .route("node.run")
          .data(out)
          .retrieveFlux(NodeFrame.class)
          .doOnNext(_ -> {
            if (answered.compareAndSet(false, true)) {
              dispatch(pluginType).record(Duration.ofNanos(System.nanoTime() - picked));
            }
          })
          .<Message<?>>handle((frame, sink) -> answer(workerId, frame, heartbeat, logSink, sink))
          .onErrorMap(
              WorkerNodeExecutor::isTransport,
              error -> {
                outcome.set("lost");
                return lost(workerId, error);
              })
          .doOnComplete(() -> {
            outcome.set("completed");
            health.succeeded(workerId, pluginType);
          })
          .doOnError(error -> {
            outcome.compareAndSet("cancelled", "failed");
            call.error(error);
            // bad input and hard stops are the run's doing, not the worker's
            if (("lost".equals(outcome.get()) || error instanceof RemoteFailureException)
                && health.failed(workerId, pluginType)) {
              quarantined(workerId, pluginType);
            }
          })
          .doFinally(_ -> {
            call.stop();
            load.decrementAndGet();
            attempts(pluginType, outcome.get())
                .record(Duration.ofNanos(System.nanoTime() - picked));
          });
    });
  }

  /**
   * The observation of one attempt on a worker, a child of the node's: once started, a tracer's
   * propagating handler has written the headers the worker continues the trace from into {@code
   * trace}.
   */
  private Observation call(
      final String workerId,
      final String executionId,
      final String nodeId,
      final String pluginType,
      final Map<String, String> trace) {
    final SenderContext<Map<String, String>> sent = new SenderContext<>(Map::put);
    sent.setCarrier(trace);
    sent.setRemoteServiceName(workerId);
    return Observation.createNotStarted("yukta.worker.call", () -> sent, observations)
        .parentObservation(parentOf.apply(executionId, nodeId))
        .contextualName("worker " + nodeId)
        .lowCardinalityKeyValue("module", pluginType)
        .highCardinalityKeyValue("executionId", executionId)
        .highCardinalityKeyValue("nodeId", nodeId)
        .highCardinalityKeyValue("workerId", workerId);
  }

  private Timer attempts(final String pluginType, final String outcome) {
    return Timer.builder("yukta.worker.attempts")
        .description("Node attempts sent to workers, from the pick to the end, by outcome")
        .tag("module", pluginType)
        .tag("outcome", outcome)
        .register(meters);
  }

  private void quarantined(final String workerId, final String pluginType) {
    log.atWarn()
        .addKeyValue("workerId", workerId)
        .addKeyValue("module", pluginType)
        .log(
            "Worker quarantined for {}: its last {} attempts failed while other workers ran {}",
            WorkerHealth.COOL_DOWN,
            WorkerHealth.FAILURES,
            pluginType);
    meters.counter("yukta.worker.quarantined", "module", pluginType).increment();
  }

  private Timer dispatch(final String pluginType) {
    return Timer.builder("yukta.worker.dispatch")
        .description("From picking a worker to its first answer on the attempt")
        .tag("module", pluginType)
        .register(meters);
  }

  /** A worker that may run the attempt, with the way into it. */
  /* default */ record Chosen(WorkerInfo info, RSocketRequester requester) {}

  /**
   * Picks the connected worker of the pool with the most free slots that may run the type, and
   * takes one of its slots: two attempts picked at the same moment cannot both take the last one,
   * the one that loses moves on to the next worker. The caller gives the slot back.
   */
  /* default */ Optional<Chosen> pick(final String pool, final String pluginType) {
    return registry.list().stream()
        .filter(info -> info.pools().contains(pool) && info.pluginTypes().contains(pluginType))
        .filter(info -> !info.draining() && !health.quarantined(info.workerId()))
        .flatMap(info ->
            connections
                .find(info.workerId())
                .map(requester -> new Chosen(info, requester))
                .stream())
        .sorted(
            Comparator.comparingInt(
                    (Chosen chosen) -> free(chosen.info(), load(chosen.info().workerId()).get()))
                .reversed()
                .thenComparing(chosen -> chosen.info().workerId()))
        .filter(chosen -> reserve(chosen.info()))
        .findFirst();
  }

  /** Takes a free slot of the worker, unless another attempt has just taken the last one. */
  private boolean reserve(final WorkerInfo info) {
    final AtomicInteger load = load(info.workerId());
    boolean taken = false;
    int sent = load.get();
    while (!taken && free(info, sent) > 0) {
      taken = load.compareAndSet(sent, sent + 1);
      sent = load.get();
    }
    return taken;
  }

  private AtomicInteger load(final String workerId) {
    return inFlight.computeIfAbsent(workerId, _ -> new AtomicInteger());
  }

  /** Free slots, counting both what the worker reported and what this server has sent it. */
  private static int free(final WorkerInfo info, final int sent) {
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
      final PluginLogSink logSink,
      final reactor.core.publisher.SynchronousSink<Message<?>> sink) {
    switch (frame.kind()) {
      case MESSAGE -> sink.next(mapper.convertValue(frame.message(), DefaultMessage.class));
      case HEARTBEAT -> heartbeat.run();
      case LOG -> log(frame, logSink);
      case COMPLETE -> sink.complete();
      case ERROR -> sink.error(frame.toFailure(workerId));
      default -> sink.error(new WorkflowExecutionException("Unexpected frame from " + workerId));
    }
  }

  /** Hands a worker's live log line to the node's sink, as a local plugin would have. */
  private static void log(final NodeFrame frame, final PluginLogSink logSink) {
    final Map<String, Object> sent = frame.message();
    final Map<String, Object> fields = sent != null ? sent : Map.of();
    final String level = String.valueOf(fields.getOrDefault("level", ""));
    logSink.line(
        String.valueOf(fields.getOrDefault("stream", "stdout")),
        String.valueOf(fields.getOrDefault("line", "")),
        // a level this build does not know is rated by the engine
        java.util.Arrays.stream(LogLevel.values())
            .filter(known -> known.name().equals(level))
            .findFirst()
            .orElse(null));
  }
}
