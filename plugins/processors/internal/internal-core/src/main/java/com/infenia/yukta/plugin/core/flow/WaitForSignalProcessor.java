// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputAsk;
import com.infenia.yukta.plugin.input.InputGateway;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.input.InputKeys;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.TimeoutAction;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Holds each message until a named signal arrives, for example a human approval or a decision from
 * an agent, then lets it through tagged with {@code signal.name}, {@code signal.decision} and
 * {@code signal.data}.
 *
 * <p>The wait is an {@code InputRequest} of kind {@code APPROVAL} and risk {@code HIGH} on the
 * {@code InputGateway}, so it is listed with the pending inputs and, as a HIGH risk request, only a
 * person can answer it (the input answer command over REST, MCP, CLI or UI; the older {@code
 * SignalNodeCommand} reaches it through a bridge). A {@code reject} decision fails the node or
 * drops the message, and a wait that is not answered in time fails the node or lets the message
 * through as {@code timeout}. Without an {@code InputGateway} bean the node fails with a clear
 * message.
 *
 * <p>An answer is also recorded in the run's checkpoint store (under the node id {@code
 * signal:<node>}, keyed by the id of the message and the signal name), so a run that is resumed
 * after a restart does not ask again for a message that was already answered. That needs a file or
 * PostgreSQL checkpoint store and a message whose id survives the replay (an output of a node that
 * finished); a message that is produced again waits again. A timeout is not recorded.
 */
@Component
@SuppressWarnings("PMD.TooManyMethods")
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

  /** Longest signal name: it is part of the request key. */
  private static final int NAME_MAX_CHARS = 128;

  /** Label of this plugin on its requests. */
  private static final String REQUESTER = "WAIT_FOR_SIGNAL";

  /** Where the input gateway is found, at use time. */
  private final ObjectProvider<InputGateway> gateways;

  /** Where answers are kept for a resume, or null when the run keeps none. */
  @Nullable private final NodeCheckpointStore answers;

  /**
   * Spring constructor.
   *
   * @param checkpoints the checkpoint store, where answers are remembered for a resume
   * @param gateways the input gateway, which holds the question and takes the answer
   */
  @Autowired
  @SuppressFBWarnings("EI_EXPOSE_REP2") // the checkpoint store is a shared Spring bean
  public WaitForSignalProcessor(
      final NodeCheckpointStore checkpoints, final ObjectProvider<InputGateway> gateways) {
    this.gateways = gateways;
    this.answers = checkpoints;
  }

  /* default */ WaitForSignalProcessor(final ObjectProvider<InputGateway> gateways) {
    this.gateways = gateways;
    this.answers = null;
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Waits for a named signal (approval, decision) before a message goes on.",
        """
              Configure with:
              - signal: name of the signal to wait for (required).
              - timeoutSeconds: longest wait, default 3600. Must not exceed yukta.input.max-ttl (default 24h):
        a longer wait is refused at runtime with an input limit error.
              - onTimeout: FAIL (default) or CONTINUE (message goes on with signal.decision=timeout).
              - onReject: FAIL (default) or CONTINUE (message is dropped) for a 'reject' decision.
              Any other decision lets the message through; read it from metadata signal.decision to branch.
              The wait is an input request (kind APPROVAL, risk HIGH): it is listed with the pending
              inputs and answered with the input answer command (REST, MCP, CLI, UI) by a person, as
              HIGH risk requests are human-only. The older signal command still answers it for now.
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
  public boolean requiresControlPlane() {
    return true;
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
                                  InputAsk.ask(
                                          gateways::getIfAvailable,
                                          spec(ctx, message, name, timeout))
                                      .map(WaitForSignalProcessor::decision)
                                      .flatMap(
                                          decision ->
                                              remember(
                                                  executionId, nodeId, name, message, decision))
                                      .onErrorResume(
                                          InputAsk::isTimeout,
                                          e ->
                                              continueOnTimeout
                                                  ? Mono.just(new Decision("timeout", ""))
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

  /** The request: an approval nobody can answer with a wrong word, held by name for the message. */
  private static InputRequestSpec spec(
      final reactor.util.context.ContextView ctx,
      final Message<?> message,
      final String name,
      final Duration timeout) {
    final InputIdentity who = InputIdentity.from(ctx);
    final String shown = InputAsk.clean(name, NAME_MAX_CHARS);
    return new InputRequestSpec(
        InputKeys.of(who.sessionId(), who.workflowId(), who.nodeId(), message.id(), name),
        InputKind.APPROVAL,
        "Waiting for signal '"
            + shown
            + "'. Answer approve to let the message go on, reject to"
            + " stop it, or any other decision to branch on it.",
        Map.of(InputAsk.SIGNAL_CONTEXT_KEY, name, "messageId", message.id()),
        List.of(),
        null,
        InputRisk.HIGH,
        Audience.CURRENT_TIER,
        timeout,
        TimeoutAction.FAIL,
        null,
        null,
        REQUESTER);
  }

  private static Decision decision(final InputResponse response) {
    return new Decision(response.decision(), Objects.requireNonNullElse(response.text(), ""));
  }

  /** What a signal answered: the decision and its text. */
  private record Decision(String decision, String data) {}

  /** Where the answers of a node are kept in the checkpoint store. */
  private static String answerNode(final String nodeId) {
    return "signal:" + nodeId;
  }

  /** The answer given earlier (before a restart) to this message, if there is one. */
  private Mono<Decision> remembered(
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
              return new Decision(
                  String.valueOf(body.get("decision")), String.valueOf(body.get("data")));
            })
        .onErrorResume(_ -> Mono.empty());
  }

  /** Keeps an answer for a resume; a store that fails never fails the wait. */
  private Mono<Decision> remember(
      final String executionId,
      final String nodeId,
      final String name,
      final Message<?> message,
      final Decision decision) {
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
      final Decision decision,
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
    if (config.get(SIGNAL) instanceof String name
        && !name.isBlank()
        && name.length() <= NAME_MAX_CHARS) {
      return name;
    }
    throw new IllegalArgumentException(
        "signal must be a non-empty name of at most " + NAME_MAX_CHARS + " characters");
  }

  private static Duration timeout(final Map<String, Object> config) {
    final Object value = config.getOrDefault(TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS);
    if (value instanceof Number seconds && seconds.longValue() > 0) {
      return Duration.ofSeconds(seconds.longValue());
    }
    throw new IllegalArgumentException("timeoutSeconds must be a positive number");
  }
}
