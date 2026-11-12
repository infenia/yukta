// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.PluginLogSink;
import com.infenia.yukta.plugin.core.PluginTier;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.input.InputAsk;
import com.infenia.yukta.plugin.input.InputGateway;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.ConfigValidation;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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
public final class AgentSessionPlugin implements ProcessorPlugin {

  private static final String TYPE = "AGENT_SESSION";
  private static final String HARNESS = "harness";
  private static final String PROMPT = "prompt";
  private static final String MODEL = "model";
  private static final String MAX_TURNS = "maxTurns";
  private static final String ARGS = "args";
  private static final String SESSION = "session";
  private static final String ON_BUSY = "onBusy";
  private static final String APPROVALS = "approvals";
  private static final String APPROVAL_RISK = "approvalRisk";
  private static final String APPROVAL_TIMEOUT = "approvalTimeout";
  private static final String APPROVAL_AUDIENCE = "approvalAudience";
  private static final long DEFAULT_APPROVAL_SECONDS = 300;
  private static final Duration BUSY_POLL = Duration.ofMillis(250);
  private static final String TIMEOUT = "timeout";
  private static final long DEFAULT_TIMEOUT_SECONDS = 600;
  private static final long MARGIN_SECONDS = 30;
  private static final int DEFAULT_CONCURRENCY = 2;
  private static final int KEPT_LINES = 50;
  private static final long KEPT_BYTES = 64 * 1024L;

  private final ProcessExecutorGateway gateway;
  private final VariableResolver resolver;
  private final AgentHarnesses harnesses;
  private final AgentSessionStore sessions;
  private final ObjectProvider<InputGateway> inputs;
  private final ObjectMapper json;

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
    this.gateway = gateway;
    this.resolver = resolver;
    this.harnesses = harnesses;
    this.sessions = sessions;
    this.inputs = inputs;
    this.json = json;
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
            + describeHarnesses(),
        Optional.empty());
  }

  private String describeHarnesses() {
    final StringBuilder out = new StringBuilder();
    harnesses
        .all()
        .forEach(
            h ->
                out.append("- ")
                    .append(h.id())
                    .append(": ")
                    .append(h.description())
                    .append(" Capabilities: ")
                    .append(h.capabilities().stream().sorted().toList())
                    .append('\n'));
    return out.toString();
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.choice(
            HARNESS,
            true,
            null,
            "Which agent harness to run",
            harnesses.all().stream().map(AgentPreset::id).toArray(String[]::new)),
        ConfigField.required(PROMPT, ConfigField.Type.EXPRESSION, "What to ask the agent"),
        ConfigField.optional(
            MODEL, ConfigField.Type.STRING, null, "Model, if the harness takes one"),
        ConfigField.optional(
            MAX_TURNS, ConfigField.Type.NUMBER, null, "Most turns, if the harness takes a limit"),
        ConfigField.optional(
            ARGS, ConfigField.Type.LIST, null, "Extra command-line arguments for the harness"),
        ConfigField.optional(
            SESSION,
            ConfigField.Type.EXPRESSION,
            null,
            "Session key: nodes and messages with the same key continue one conversation (needs a"
                + " harness with resume). Without it every message starts afresh."),
        ConfigField.choice(
            ON_BUSY,
            false,
            "WAIT",
            "A session runs one turn at a time. WAIT queues the next turn; FAIL sends it to the"
                + " failure port at once.",
            "WAIT",
            "FAIL"),
        ConfigField.choice(
            APPROVALS,
            false,
            "NONE",
            "ASK: each tool use the harness needs permission for becomes an approval request on"
                + " the input request system (answered by a person, a supervisor or an agent;"
                + " no answer in time refuses the tool). NONE: the harness decides alone (see"
                + " args). Needs a harness with the ask capability.",
            "NONE",
            "ASK"),
        ConfigField.choice(
            APPROVAL_RISK,
            false,
            "MEDIUM",
            "Risk of the approval requests",
            "LOW",
            "MEDIUM",
            "HIGH"),
        ConfigField.optional(
            APPROVAL_TIMEOUT,
            ConfigField.Type.NUMBER,
            DEFAULT_APPROVAL_SECONDS,
            "Seconds to wait for an answer before the tool is refused"),
        ConfigField.optional(
            APPROVAL_AUDIENCE,
            ConfigField.Type.LIST,
            null,
            "Who may answer: supervisor:<id>, role:OPERATOR, key:<name>, ... (default: the current"
                + " tier)"),
        ConfigField.optional("workingDir", ConfigField.Type.STRING, null, "Working directory"),
        ConfigField.optional("env", ConfigField.Type.MAP, null, "Extra environment variables"),
        ConfigField.optional(
            TIMEOUT, ConfigField.Type.NUMBER, DEFAULT_TIMEOUT_SECONDS, "Timeout in seconds"),
        ConfigField.optional(
            ConfigValidation.CONCURRENCY_KEY,
            ConfigField.Type.NUMBER,
            DEFAULT_CONCURRENCY,
            "Messages handled at once (each starts an agent)"));
  }

  @Override
  public List<String> getOutputPorts() {
    return List.of("success", "failure", "incomplete");
  }

  @Override
  public Duration getDefaultTimeout(final Map<String, Object> config) {
    final long seconds = timeoutSeconds(config);
    return Duration.ofSeconds(seconds + Math.max(MARGIN_SECONDS, seconds / 10));
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    try {
      final AgentPreset preset = preset(config);
      if (config.get(PROMPT) == null) {
        throw new IllegalArgumentException("prompt is mandatory");
      }
      if (config.get(MODEL) != null && preset.modelArgs().isEmpty()) {
        throw new IllegalArgumentException(
            "harness '" + preset.id() + "' does not take a model; remove model");
      }
      if (config.get(MAX_TURNS) != null && preset.maxTurnsArgs().isEmpty()) {
        throw new IllegalArgumentException(
            "harness '" + preset.id() + "' has no turn limit; remove maxTurns");
      }
      if (config.get(SESSION) != null && preset.resumeArgs().isEmpty()) {
        throw new IllegalArgumentException(
            "harness '" + preset.id() + "' cannot resume a session; remove session");
      }
      onBusy(config);
      if (asks(config) && preset.interactive() == null) {
        throw new IllegalArgumentException(
            "harness '" + preset.id() + "' cannot ask for approvals; remove approvals");
      }
      settings(config);
      if (config.get(ARGS) != null && !(config.get(ARGS) instanceof List)) {
        throw new IllegalArgumentException("args must be a list of strings");
      }
      timeoutSeconds(config);
      ConfigValidation.concurrency(config, DEFAULT_CONCURRENCY);
    } catch (IllegalArgumentException e) {
      return Mono.error(e);
    }
    return Mono.empty();
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final AgentPreset preset = preset(config);
    return input.flatMapSequential(
        message -> run(message, config, preset),
        ConfigValidation.concurrency(config, DEFAULT_CONCURRENCY));
  }

  private Mono<Message<?>> run(
      final Message<?> message, final Map<String, Object> config, final AgentPreset preset) {
    return Mono.zip(
            resolve(config.get(PROMPT), message),
            resolve(config.get("workingDir"), message),
            resolve(config.get(MODEL), message),
            resolveEnv(config.get("env"), message),
            resolve(config.get(SESSION), message))
        .flatMap(
            t -> {
              final String key = t.getT5();
              if (key.isEmpty()) {
                return turn(
                    message, config, preset, t.getT1(), t.getT2(), t.getT3(), t.getT4(), null);
              }
              return Mono.usingWhen(
                  acquire(key, onBusy(config)),
                  lease ->
                      turn(
                          message, config, preset, t.getT1(), t.getT2(), t.getT3(), t.getT4(),
                          lease),
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
                                "status", "failure", "reason", e.getMessage(), "text", ""))
                        .withSourcePort("failure")));
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
      final String prompt,
      final String workingDir,
      final String model,
      final Map<String, String> env,
      final AgentSessionStore.Lease lease) {
    final long timeout = timeoutSeconds(config);
    final String token = lease == null ? null : lease.token();
    final int attempt = lease == null ? 1 : lease.turns() + 1;
    final boolean ask = asks(config);
    final AgentPreset.Interactive talk = ask ? preset.interactive() : null;
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder()
            .command(
                command(
                    talk == null ? preset.command() : talk.command(),
                    preset,
                    config,
                    prompt,
                    model,
                    token))
            .workingDir(workingDir.isEmpty() ? null : workingDir)
            .timeoutSeconds(timeout)
            .env(env)
            .maxOutputLines(KEPT_LINES)
            .maxOutputBytes(KEPT_BYTES)
            .build();
    return Mono.deferContextual(
            ctx -> {
              final AgentOutput output =
                  new AgentOutput(
                      preset.output(),
                      talk,
                      ctx.<PluginLogSink>getOrEmpty(WorkflowContext.LOG_SINK_KEY)
                          .orElse(PluginLogSink.NOOP),
                      json);
              if (talk == null) {
                return gateway
                    .executeForResult(spec, output)
                    .map(r -> output.outcome(r.exitCode(), r.timedOut(), timeout));
              }
              final AtomicReference<AgentApprovals> approvals = new AtomicReference<>();
              return gateway
                  .executeInteractive(
                      spec,
                      output,
                      stdin -> {
                        final AgentApprovals a =
                            new AgentApprovals(
                                talk,
                                settings(config),
                                json,
                                inputs::getIfAvailable,
                                InputIdentity.from(ctx),
                                message,
                                preset.id(),
                                stdin);
                        approvals.set(a);
                        output.converse(a::handle, stdin::close);
                        stdin.write(AgentApprovals.userMessage(talk.userMessage(), prompt, json));
                      })
                  .doFinally(
                      signal -> {
                        final AgentApprovals a = approvals.get();
                        if (a != null) {
                          a.dispose();
                        }
                      })
                  .map(r -> output.outcome(r.exitCode(), r.timedOut(), timeout));
            })
        .map(
            outcome -> {
              if (lease != null) {
                if (!"failure".equals(outcome.get("status"))) {
                  lease.complete(String.valueOf(outcome.get("sessionId")));
                }
                outcome.put("turn", attempt);
              }
              return (Message<?>)
                  DefaultMessage.from(message, outcome)
                      .withSourcePort(String.valueOf(outcome.get("status")));
            });
  }

  private static boolean asks(final Map<String, Object> config) {
    final String value =
        String.valueOf(config.getOrDefault(APPROVALS, "NONE")).toUpperCase(java.util.Locale.ROOT);
    if (!"NONE".equals(value) && !"ASK".equals(value)) {
      throw new IllegalArgumentException("approvals must be NONE or ASK, got: " + value);
    }
    return "ASK".equals(value);
  }

  private static AgentApprovals.Settings settings(final Map<String, Object> config) {
    final Object timeout = config.get(APPROVAL_TIMEOUT);
    if (timeout != null && !(timeout instanceof final Number n && n.longValue() > 0)) {
      throw new IllegalArgumentException("approvalTimeout must be positive, got: " + timeout);
    }
    return new AgentApprovals.Settings(
        InputAsk.risk(config.get(APPROVAL_RISK), InputRisk.MEDIUM),
        Duration.ofSeconds(
            timeout == null ? DEFAULT_APPROVAL_SECONDS : ((Number) timeout).longValue()),
        InputAsk.audience(config.get(APPROVAL_AUDIENCE)));
  }

  private static boolean onBusy(final Map<String, Object> config) {
    final String value =
        String.valueOf(config.getOrDefault(ON_BUSY, "WAIT")).toUpperCase(java.util.Locale.ROOT);
    if (!"WAIT".equals(value) && !"FAIL".equals(value)) {
      throw new IllegalArgumentException("onBusy must be WAIT or FAIL, got: " + value);
    }
    return "WAIT".equals(value);
  }

  /** A second turn of a session that runs one at a time, when the node says FAIL. */
  private static final class SessionBusyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    SessionBusyException(final String message) {
      super(message);
    }
  }

  private static List<String> command(
      final List<String> base,
      final AgentPreset preset,
      final Map<String, Object> config,
      final String prompt,
      final String model,
      final String token) {
    final List<String> command = new ArrayList<>();
    base.forEach(a -> command.add(a.replace("{prompt}", prompt)));
    if (token != null && !token.isBlank()) {
      preset.resumeArgs().forEach(a -> command.add(a.replace("{session}", token)));
    }
    if (!model.isEmpty()) {
      preset.modelArgs().forEach(a -> command.add(a.replace("{model}", model)));
    }
    if (config.get(MAX_TURNS) != null) {
      preset
          .maxTurnsArgs()
          .forEach(
              a -> command.add(a.replace("{maxTurns}", String.valueOf(config.get(MAX_TURNS)))));
    }
    if (config.get(ARGS) instanceof final List<?> extra) {
      extra.forEach(a -> command.add(String.valueOf(a)));
    }
    return command;
  }

  private AgentPreset preset(final Map<String, Object> config) {
    final Object id = config.get(HARNESS);
    if (id == null) {
      throw new IllegalArgumentException(
          "harness is mandatory; one of " + harnesses.all().stream().map(AgentPreset::id).toList());
    }
    final AgentPreset preset = harnesses.get(String.valueOf(id));
    if (preset == null) {
      throw new IllegalArgumentException(
          "unknown harness '"
              + id
              + "'; one of "
              + harnesses.all().stream().map(AgentPreset::id).toList());
    }
    return preset;
  }

  private static long timeoutSeconds(final Map<String, Object> config) {
    final Object value = config.get(TIMEOUT);
    if (value == null) {
      return DEFAULT_TIMEOUT_SECONDS;
    }
    if (value instanceof final Number n && n.longValue() > 0) {
      return n.longValue();
    }
    throw new IllegalArgumentException("timeout must be positive, got: " + value);
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
        .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new)
        .map(m -> (Map<String, String>) m);
  }
}
