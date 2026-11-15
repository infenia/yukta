// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.agent.AgentCapability;
import com.infenia.yukta.plugin.agent.AgentConversationGateway;
import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.plugin.agent.AgentConversationSpec;
import com.infenia.yukta.plugin.agent.AgentProtocol;
import com.infenia.yukta.plugin.agentsession.adapter.AgentAdapter;
import com.infenia.yukta.plugin.agentsession.adapter.AgentRequests;
import com.infenia.yukta.plugin.agentsession.adapter.AgentRun;
import com.infenia.yukta.plugin.agentsession.adapter.JsonlAdapter;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.PluginLogSink;
import com.infenia.yukta.plugin.core.PluginTier;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.input.InputGateway;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.process.ProcessExecutionSpec;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.ConfigValidation;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs an AI agent harness (Claude Code, Codex, ...) on a prompt and emits how it ended on the port
 * {@code success}, {@code failure} or {@code incomplete}. Which harness is configuration: a preset
 * says how to start it and read its output. The process starts through {@link
 * ProcessExecutorGateway}, so the operator's command allow-list applies, and cancelling the run
 * kills it.
 */
@Slf4j
@SuppressWarnings({
  "PMD.CouplingBetweenObjects",
  "PMD.TooManyMethods",
  "PMD.ExcessiveImports",
  "PMD.UseConcurrentHashMap"
})
public final class AgentSessionPlugin implements ProcessorPlugin {

  private static final String TYPE = "AGENT_SESSION";

  private static final long DEFAULT_BEAT_SECONDS = 10;
  private static final Runnable NO_BEAT = () -> {};
  private static final String FAILURE = "failure";
  private static final String STATUS = "status";
  private static final Duration BUSY_POLL = Duration.ofMillis(250);
  private static final int KEPT_LINES = 50;
  private static final long KEPT_BYTES = 64 * 1024L;

  private final VariableResolver resolver;
  private final AgentSessionConfig settings;
  private final AgentSessionStore sessions;
  private final ObjectProvider<AgentConversationGateway> conversations;

  /** The adapters by protocol; a preset of another protocol is refused when the node is checked. */
  private final Map<AgentProtocol, AgentAdapter> adapters = new EnumMap<>(AgentProtocol.class);

  /** What a message resolved to for one turn. */
  /* default */ record Turn(
      String prompt, String workingDir, String model, Map<String, String> env) {}

  /**
   * Creates the plugin.
   *
   * @param gateway starts the process
   * @param resolver resolves config expressions
   * @param harnesses the presets
   * @param sessions remembers sessions between turns
   * @param inputs the input request system, for approvals
   * @param conversations records the conversations; none when {@code yukta.agents.enabled} is false
   * @param json the JSON mapper
   */
  public AgentSessionPlugin(
      final ProcessExecutorGateway gateway,
      final VariableResolver resolver,
      final AgentHarnesses harnesses,
      final AgentSessionStore sessions,
      final ObjectProvider<InputGateway> inputs,
      final ObjectProvider<AgentConversationGateway> conversations,
      final ObjectMapper json) {
    this(
        gateway,
        resolver,
        harnesses,
        sessions,
        inputs,
        conversations,
        json,
        Duration.ofSeconds(DEFAULT_BEAT_SECONDS));
  }

  /**
   * Creates the plugin with its own heartbeat interval, for tests.
   *
   * @param gateway starts the process
   * @param resolver resolves config expressions
   * @param harnesses the presets
   * @param sessions remembers sessions between turns
   * @param inputs the input request system, for approvals
   * @param conversations records the conversations
   * @param json the JSON mapper
   * @param beat how often an open conversation tells the engine that its node is alive
   */
  /* default */ AgentSessionPlugin(
      final ProcessExecutorGateway gateway,
      final VariableResolver resolver,
      final AgentHarnesses harnesses,
      final AgentSessionStore sessions,
      final ObjectProvider<InputGateway> inputs,
      final ObjectProvider<AgentConversationGateway> conversations,
      final ObjectMapper json,
      final Duration beat) {
    this.resolver = resolver;
    this.settings = new AgentSessionConfig(harnesses);
    this.sessions = sessions;
    this.conversations = conversations;
    final AgentAdapter jsonl = new JsonlAdapter(gateway, inputs, json, beat);
    adapters.put(jsonl.protocol(), jsonl);
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public PluginTier getTier() {
    return PluginTier.EXTENDED;
  }

  @Override
  public boolean isRestricted() {
    return true;
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Runs an AI agent harness on a prompt and reports how it ended. The harness is a preset;"
            + " the process starts through the process executor, so yukta.process.allowed-commands"
            + " must allow its executable.",
        "Configure with:\n"
            + "- harness: the preset id; the values are listed in this field's options.\n"
            + "- prompt: what to ask (an expression over the message is allowed).\n"
            + "- model, maxTurns: only for a harness that supports them.\n"
            + "- session: a key; the same key continues the conversation (harness capability"
            + " resume). onBusy WAIT (default) or FAIL for a second turn while one runs.\n"
            + "- args: extra command-line arguments, e.g. [\"--allowedTools\", \"Read,Edit\"].\n"
            + "- approvals: ASK to have each tool use approved through the input request system"
            + " (approvalRisk, approvalTimeout seconds, approvalAudience); needs a harness with the"
            + " ask capability.\n"
            + "- mode: TASK (default; the node ends with the agent's turn) or CONVERSATION (the node"
            + " stays open for messages sent to its conversation until it is ended, idle for"
            + " idleTimeout (default 900 s) or running for conversationTimeout (default 14400 s);"
            + " needs a harness with an interactive mode). steerAudience: who may send a message,"
            + " interrupt or end (default role:OPERATOR).\n"
            + "- workingDir, env, timeout (seconds, default 600).\n"
            + "Emits {status, reason, text, sessionId, turn, exitCode, conversationId} on port"
            + " success, failure (the agent or harness failed, or timed out) or incomplete (stopped"
            + " early, e.g. out of turns). Enable with yukta.plugins.enabled=AGENT_SESSION.\n"
            + "Harnesses:\n"
            + settings.describeHarnesses(),
        Optional.empty());
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return settings.schema();
  }

  @Override
  public List<String> getOutputPorts() {
    return List.of("success", FAILURE, "incomplete");
  }

  @Override
  public Duration getDefaultTimeout(final Map<String, Object> config) {
    return AgentSessionConfig.stepTimeout(config);
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    try {
      settings.validate(config);
    } catch (IllegalArgumentException e) {
      return Mono.error(e);
    }
    final AgentPreset preset = settings.preset(config);
    return adapters.containsKey(preset.protocol())
        ? Mono.empty()
        : Mono.error(
            new IllegalArgumentException(
                "harness '"
                    + preset.id()
                    + "' speaks the protocol "
                    + preset.protocol()
                    + ", which this server does not support yet"));
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final AgentPreset preset = settings.preset(config);
    return input.flatMapSequential(
        message -> run(message, config, preset),
        ConfigValidation.concurrency(config, AgentSessionConfig.DEFAULT_CONCURRENCY));
  }

  private Mono<Message<?>> run(
      final Message<?> message, final Map<String, Object> config, final AgentPreset preset) {
    return Mono.zip(
            resolve(config.get(AgentSessionConfig.PROMPT), message),
            resolve(config.get(AgentSessionConfig.WORKING_DIR), message),
            resolve(config.get(AgentSessionConfig.MODEL), message),
            resolveEnv(config.get(AgentSessionConfig.ENV), message),
            resolve(config.get(AgentSessionConfig.SESSION), message))
        .flatMap(t -> {
          final Turn turn = new Turn(t.getT1(), t.getT2(), t.getT3(), t.getT4());
          final String key = t.getT5();
          if (key.isEmpty()) {
            return turn(message, config, preset, turn, null);
          }
          return Mono.usingWhen(
              acquire(key, AgentSessionConfig.waitsWhenBusy(config)),
              lease -> turn(message, config, preset, turn, lease),
              lease -> Mono.fromRunnable(lease::close),
              (lease, e) -> Mono.fromRunnable(lease::close),
              lease -> Mono.fromRunnable(lease::close));
        })
        .onErrorResume(
            SessionBusyException.class,
            e ->
                Mono.just(
                    DefaultMessage.from(
                            message,
                            Map.<String, Object>of(
                                STATUS, FAILURE, "reason", e.getMessage(), "text", ""))
                        .withSourcePort(FAILURE)));
  }

  /** Takes the session, waiting for the running turn when {@code onBusy} is WAIT. */
  private Mono<AgentSessionStore.Lease> acquire(final String key, final boolean wait) {
    // ponytail: polls every BUSY_POLL; a fair queue only matters with many waiters on one session
    return Mono.defer(
            () -> {
              final AgentSessionStore.Lease lease = sessions.tryAcquire(key);
              if (lease != null) {
                return Mono.just(lease);
              }
              return wait
                  ? Mono.<AgentSessionStore.Lease>empty()
                  : Mono.error(new SessionBusyException("session '" + key + "' is busy"));
            })
        .repeatWhenEmpty(repeat -> repeat.delayElements(BUSY_POLL));
  }

  private Mono<Message<?>> turn(
      final Message<?> message,
      final Map<String, Object> config,
      final AgentPreset preset,
      final Turn turn,
      final AgentSessionStore.Lease lease) {
    final long timeout = AgentSessionConfig.timeoutSeconds(config);
    final boolean ask = AgentSessionConfig.asks(config);
    final String token = lease == null ? null : lease.token();
    final int attempt = lease == null ? 1 : lease.turns() + 1;
    final boolean chat = AgentSessionConfig.converses(config);
    final AgentRequests.Settings approvals = ask ? AgentSessionConfig.settings(config) : null;
    return Mono.deferContextual(ctx -> {
      final InputIdentity who = ask ? InputIdentity.from(ctx) : identity(ctx);
      // a preset with an interactive mode always starts in it when the run is recorded, so a
      // message can be sent; without a node identity there is no one to ask or to steer
      final AgentPreset.Interactive talk =
          who != null && (ask || conversations.getIfAvailable() != null)
              ? preset.interactive()
              : null;
      final ProcessExecutionSpec spec = spec(config, preset, turn, talk, token, timeout);
      return open(who, message, preset, turn, capabilities(preset, talk, ask), config)
          .flatMap(conversation ->
              adapters
                  .get(preset.protocol())
                  .run(
                      new AgentRun(
                          message,
                          preset,
                          spec,
                          turn.prompt(),
                          talk,
                          approvals,
                          timeout,
                          conversation,
                          ctx.<PluginLogSink>getOrEmpty(WorkflowContext.LOG_SINK_KEY)
                              .orElse(PluginLogSink.NOOP),
                          who,
                          chat,
                          AgentSessionConfig.idleTimeout(config),
                          ctx.<Runnable>getOrDefault(WorkflowContext.HEARTBEAT_KEY, NO_BEAT)))
                  .map(outcome -> reply(message, outcome, lease, attempt, conversation)));
    });
  }

  private static ProcessExecutionSpec spec(
      final Map<String, Object> config,
      final AgentPreset preset,
      final Turn turn,
      final AgentPreset.@Nullable Interactive talk,
      final @Nullable String token,
      final long timeout) {
    return ProcessExecutionSpec.builder()
        .command(
            AgentSessionConfig.command(
                talk == null ? preset.command() : talk.command(), preset, config, turn, token))
        .workingDir(turn.workingDir().isEmpty() ? null : turn.workingDir())
        .timeoutSeconds(timeout)
        .env(turn.env())
        .maxOutputLines(KEPT_LINES)
        .maxOutputBytes(KEPT_BYTES)
        // standard error is kept apart so the conversation can show it as notices; the run log
        // still gets its lines as it always did (LogMirror)
        .captureStderr(true)
        .build();
  }

  /**
   * Opens the conversation of this message. Without the gateway, without a node identity, or when
   * the conversation cannot be stored, the run goes on with a handle that drops everything: a
   * conversation is a view of the run and must never fail it.
   */
  private Mono<AgentConversationHandle> open(
      final @Nullable InputIdentity who,
      final Message<?> message,
      final AgentPreset preset,
      final Turn turn,
      final Set<String> capabilities,
      final Map<String, Object> config) {
    final AgentConversationGateway gateway = conversations.getIfAvailable();
    if (gateway == null || who == null) {
      return Mono.just(AgentConversationHandle.none());
    }
    return gateway
        .open(
            new AgentConversationSpec(
                who,
                message.id(),
                preset.id(),
                preset.protocol(),
                turn.model().isEmpty() ? null : turn.model(),
                capabilities,
                AgentSessionConfig.steerAudience(config)))
        .onErrorResume(e -> {
          log.atWarn().setCause(e).log("Could not open an agent conversation; running without");
          return Mono.just(AgentConversationHandle.none());
        });
  }

  /** What this conversation can do, from the preset and how the agent runs. */
  private static Set<String> capabilities(
      final AgentPreset preset, final AgentPreset.@Nullable Interactive talk, final boolean ask) {
    final Set<String> found = new LinkedHashSet<>();
    if (talk != null) {
      found.add(AgentCapability.MESSAGE);
      if (talk.steer()) {
        found.add(AgentCapability.STEER);
      }
      if (talk.canInterrupt()) {
        found.add(AgentCapability.INTERRUPT);
      }
    }
    if (ask && talk != null) {
      found.add(AgentCapability.ASK);
    }
    if (preset.capabilities().contains("resume")) {
      found.add(AgentCapability.RESUME);
    }
    return found;
  }

  private static @Nullable InputIdentity identity(final ContextView ctx) {
    try {
      return InputIdentity.from(ctx);
    } catch (IllegalStateException e) {
      return null;
    }
  }

  @SuppressWarnings("PMD.CompareObjectsWithEquals") // none() is one shared instance
  private static Message<?> reply(
      final Message<?> message,
      final Map<String, Object> outcome,
      final AgentSessionStore.Lease lease,
      final int attempt,
      final AgentConversationHandle conversation) {
    if (conversation != AgentConversationHandle.none()) {
      outcome.put("conversationId", conversation.id());
    }
    if (lease != null) {
      if (!FAILURE.equals(outcome.get(STATUS))) {
        lease.complete(String.valueOf(outcome.get("sessionId")));
      }
      outcome.put("turn", attempt);
    }
    return DefaultMessage.from(message, outcome)
        .withSourcePort(String.valueOf(outcome.get(STATUS)));
  }

  /** A second turn of a session that runs one at a time, when the node says FAIL. */
  private static final class SessionBusyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /* default */ SessionBusyException(final String message) {
      super(message);
    }
  }

  private Mono<String> resolve(final Object value, final Message<?> message) {
    return value == null
        ? Mono.just("")
        : resolver.resolve(value, message).map(String::valueOf).defaultIfEmpty("");
  }

  private Mono<Map<String, String>> resolveEnv(final Object env, final Message<?> message) {
    if (!(env instanceof final Map<?, ?> map) || map.isEmpty()) {
      return Mono.just(Map.of());
    }
    return Flux.fromIterable(map.entrySet())
        .concatMap(
            e -> resolve(e.getValue(), message).map(v -> Map.entry(String.valueOf(e.getKey()), v)))
        .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new);
  }
}
