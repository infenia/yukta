// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.PluginTier;
import com.infenia.yukta.plugin.input.InputGateway;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.ConfigValidation;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs an AI agent harness (Claude Code, Codex, ...) on a prompt and emits how it ended on the port
 * {@code success}, {@code failure} or {@code incomplete}. Which harness is configuration: a preset
 * says how to start it and read its output. The process starts through {@link
 * ProcessExecutorGateway}, so the operator's command allow-list applies, and cancelling the run
 * kills it.
 */
@SuppressWarnings("PMD.CouplingBetweenObjects")
public final class AgentSessionPlugin implements ProcessorPlugin {

  private static final String TYPE = "AGENT_SESSION";
  private static final String FAILURE = "failure";
  private static final String STATUS = "status";
  private static final Duration BUSY_POLL = Duration.ofMillis(250);
  private static final int KEPT_LINES = 50;
  private static final long KEPT_BYTES = 64 * 1024L;

  private final VariableResolver resolver;
  private final AgentSessionConfig settings;
  private final AgentSessionStore sessions;
  private final AgentTurn agent;

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
   * @param json the JSON mapper
   */
  public AgentSessionPlugin(
      final ProcessExecutorGateway gateway,
      final VariableResolver resolver,
      final AgentHarnesses harnesses,
      final AgentSessionStore sessions,
      final ObjectProvider<InputGateway> inputs,
      final ObjectMapper json) {
    this.resolver = resolver;
    this.settings = new AgentSessionConfig(harnesses);
    this.sessions = sessions;
    this.agent = new AgentTurn(gateway, inputs, json);
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
            + "- workingDir, env, timeout (seconds, default 600).\n"
            + "Emits {status, reason, text, sessionId, turn, exitCode} on port success, failure"
            + " (the agent or harness failed, or timed out) or incomplete (stopped early, e.g. out"
            + " of turns). Enable with yukta.plugins.enabled=AGENT_SESSION.\n"
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
    return Mono.empty();
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
        .flatMap(
            t -> {
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
    final AgentPreset.Interactive talk = ask ? preset.interactive() : null;
    final String token = lease == null ? null : lease.token();
    final int attempt = lease == null ? 1 : lease.turns() + 1;
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder()
            .command(
                AgentSessionConfig.command(
                    talk == null ? preset.command() : talk.command(), preset, config, turn, token))
            .workingDir(turn.workingDir().isEmpty() ? null : turn.workingDir())
            .timeoutSeconds(timeout)
            .env(turn.env())
            .maxOutputLines(KEPT_LINES)
            .maxOutputBytes(KEPT_BYTES)
            .build();
    final AgentTurn.Plan plan =
        new AgentTurn.Plan(
            spec,
            preset,
            talk,
            ask ? AgentSessionConfig.settings(config) : null,
            turn.prompt(),
            timeout);
    return agent.run(message, plan).map(outcome -> reply(message, outcome, lease, attempt));
  }

  private static Message<?> reply(
      final Message<?> message,
      final Map<String, Object> outcome,
      final AgentSessionStore.Lease lease,
      final int attempt) {
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
