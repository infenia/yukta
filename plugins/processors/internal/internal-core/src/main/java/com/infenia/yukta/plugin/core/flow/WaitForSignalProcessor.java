// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.SignalWaits;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Holds each message until a named signal arrives, for example a human approval or a decision from
 * an agent, then lets it through tagged with {@code signal.name}, {@code signal.decision} and
 * {@code signal.data}.
 *
 * <p>The signal is sent with the {@code SignalNodeCommand} (REST {@code
 * /api/executions/{id}/nodes/{node}/signal}, MCP {@code signal_node}); it is accepted only while
 * the node is waiting. A {@code reject} decision fails the node or drops the message, and a wait
 * that is not answered in time fails the node or lets the message through as {@code timeout}.
 *
 * <p>The wait itself is in memory, but an answer is also recorded in the run's checkpoint store
 * (under the node id {@code signal:<node>}, keyed by the id of the message and the signal name), so
 * a run that is resumed after a restart does not ask again for a message that was already answered.
 * That needs a file or PostgreSQL checkpoint store and a message whose id survives the replay (an
 * output of a node that finished); a message that is produced again waits again. A timeout is not
 * recorded.
 */
@Component
@SuppressWarnings("PMD.OnlyOneReturn")
public final class WaitForSignalProcessor implements ProcessorPlugin {

  /** Action that fails the message. */
  private static final String FAIL = "FAIL";

  /** Config key: name of the signal to wait for. */
  private static final String SIGNAL = "signal";

  /** Config key: longest wait in seconds. */
  private static final String TIMEOUT_SECONDS = "timeoutSeconds";

  /** Config key: what a timeout does. */
  private static final String ON_TIMEOUT = "onTimeout";

  /** Config key: what a {@code reject} decision does. */
  private static final String ON_REJECT = "onReject";

  /** Wait used when none is configured. */
  private static final long DEFAULT_TIMEOUT_SECONDS = 3600;

  /** Decision that stops the message. */
  private static final String REJECT = "reject";

  /**
   * Value of the {@code onTimeout} / {@code onReject} options that let the work go on or drop it.
   */
  private static final String CONTINUE = "CONTINUE";

  /** Answer to a signal that a waiting node took. */
  public static final String ACCEPTED = SignalWaits.ACCEPTED;

  /** Answer to a signal when the node is not waiting for it. */
  public static final String NOT_WAITING = SignalWaits.NOT_WAITING;

  /** The waits, shared with the other plugins so one list shows them all. */
  private final SignalWaits waits;

  /** Where answers are kept for a resume, or null when the run keeps none. */
  @Nullable private final NodeCheckpointStore answers;

  /** Waits on the instance the engine's plugins share, without remembering answers. */
  public WaitForSignalProcessor() {
    this(SignalWaits.shared(), null);
  }

  /**
   * Spring constructor.
   *
   * @param checkpoints the checkpoint store, where answers are remembered for a resume
   */
  @Autowired
  public WaitForSignalProcessor(final NodeCheckpointStore checkpoints) {
    this(SignalWaits.shared(), checkpoints);
  }

  /* default */ WaitForSignalProcessor(final SignalWaits waits) {
    this(waits, null);
  }

  /* default */ WaitForSignalProcessor(
      final SignalWaits waits, @Nullable final NodeCheckpointStore answers) {
    this.waits = waits;
    this.answers = answers;
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Waits for a named signal (approval, decision) before a message goes on.",
        """
        Configure with:
        - signal: name of the signal to wait for (required).
        - timeoutSeconds: longest wait, default 3600.
        - onTimeout: FAIL (default) or CONTINUE (message goes on with signal.decision=timeout).
        - onReject: FAIL (default) or CONTINUE (message is dropped) for a 'reject' decision.
        Any other decision lets the message through; read it from metadata signal.decision to branch.
        """,
        Optional.empty());
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.required(SIGNAL, ConfigField.Type.STRING, "Name of the signal to wait for"),
        ConfigField.optional(
            TIMEOUT_SECONDS, ConfigField.Type.NUMBER, DEFAULT_TIMEOUT_SECONDS, "Longest wait"),
        ConfigField.choice(ON_TIMEOUT, false, FAIL, "Action on timeout", FAIL, CONTINUE),
        ConfigField.choice(ON_REJECT, false, FAIL, "Action on a reject decision", FAIL, CONTINUE));
  }

  @Override
  public String getType() {
    return "WAIT_FOR_SIGNAL";
  }

  @Override
  public Duration getDefaultTimeout(final Map<String, Object> config) {
    // the engine's idle timeout must outlast the wait itself
    return timeout(config).plusSeconds(30);
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(
        () -> {
          signalName(config);
          timeout(config);
        });
  }

  @Override
  public Mono<Message<?>> onControlSignal(final Message<?> signal) {
    return waits.deliver(signal);
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String name = signalName(config);
    final Duration timeout = timeout(config);
    final boolean continueOnTimeout = CONTINUE.equals(config.get(ON_TIMEOUT));
    final boolean dropOnReject = CONTINUE.equals(config.get(ON_REJECT));
    return input.concatMap(
        message ->
            Mono.deferContextual(
                ctx -> {
                  final String executionId = ctx.getOrDefault("executionId", "unknown");
                  final String nodeId = ctx.getOrDefault("nodeId", "unknown");
                  return remembered(executionId, nodeId, name, message)
                      .switchIfEmpty(
                          Mono.defer(
                              () ->
                                  waits
                                      .await(executionId, nodeId, name, timeout)
                                      .flatMap(
                                          decision ->
                                              remember(
                                                  executionId, nodeId, name, message, decision))
                                      .onErrorResume(
                                          java.util.concurrent.TimeoutException.class,
                                          e ->
                                              continueOnTimeout
                                                  ? Mono.just(
                                                      new SignalWaits.Decision("timeout", ""))
                                                  : Mono.error(
                                                      new IllegalStateException(
                                                          "No '"
                                                              + name
                                                              + "' signal within "
                                                              + timeout,
                                                          e)))))
                      .flatMap(decision -> release(message, name, decision, dropOnReject));
                }));
  }

  /** Where the answers of a node are kept in the checkpoint store. */
  private static String answerNode(final String nodeId) {
    return "signal:" + nodeId;
  }

  /** The answer given earlier (before a restart) to this message, if there is one. */
  private Mono<SignalWaits.Decision> remembered(
      final String executionId, final String nodeId, final String name, final Message<?> message) {
    if (answers == null) {
      return Mono.empty();
    }
    return answers
        .getAll(executionId, answerNode(nodeId))
        .filter(
            kept ->
                kept.payload() instanceof final Map<?, ?> body
                    && message.id().equals(body.get("messageId"))
                    && name.equals(body.get("name")))
        .next()
        .map(
            kept -> {
              final Map<?, ?> body = (Map<?, ?>) kept.payload();
              return new SignalWaits.Decision(
                  String.valueOf(body.get("decision")), String.valueOf(body.get("data")));
            })
        .onErrorResume(_ -> Mono.empty());
  }

  /** Keeps an answer for a resume; a store that fails never fails the wait. */
  private Mono<SignalWaits.Decision> remember(
      final String executionId,
      final String nodeId,
      final String name,
      final Message<?> message,
      final SignalWaits.Decision decision) {
    if (answers == null) {
      return Mono.just(decision);
    }
    final Map<String, Object> body =
        Map.of(
            "messageId",
            message.id(),
            "name",
            name,
            "decision",
            decision.decision(),
            "data",
            String.valueOf(decision.data()));
    return answers
        .save(executionId, answerNode(nodeId), DefaultMessage.create(UUID.randomUUID(), body))
        .onErrorResume(_ -> Mono.empty())
        .thenReturn(decision);
  }

  private static Mono<Message<?>> release(
      final Message<?> message,
      final String name,
      final SignalWaits.Decision decision,
      final boolean dropOnReject) {
    if (REJECT.equalsIgnoreCase(decision.decision())) {
      return dropOnReject
          ? Mono.empty()
          : Mono.error(
              new IllegalStateException("Signal '" + name + "' was rejected: " + decision.data()));
    }
    return Mono.just(
        message
            .withHeader("signal.name", name)
            .withHeader("signal.decision", decision.decision())
            .withHeader("signal.data", decision.data()));
  }

  private static String signalName(final Map<String, Object> config) {
    if (config.get(SIGNAL) instanceof String name && !name.isBlank()) {
      return name;
    }
    throw new IllegalArgumentException("signal must be a non-empty name");
  }

  private static Duration timeout(final Map<String, Object> config) {
    final Object value = config.getOrDefault(TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS);
    if (value instanceof Number seconds && seconds.longValue() > 0) {
      return Duration.ofSeconds(seconds.longValue());
    }
    throw new IllegalArgumentException("timeoutSeconds must be a positive number");
  }
}
