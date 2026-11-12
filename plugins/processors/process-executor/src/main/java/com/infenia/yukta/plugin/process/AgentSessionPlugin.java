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
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.ConfigValidation;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  private static final String TIMEOUT = "timeout";
  private static final long DEFAULT_TIMEOUT_SECONDS = 600;
  private static final long MARGIN_SECONDS = 30;
  private static final int DEFAULT_CONCURRENCY = 2;
  private static final int KEPT_LINES = 50;
  private static final long KEPT_BYTES = 64 * 1024L;

  private final ProcessExecutorGateway gateway;
  private final VariableResolver resolver;
  private final AgentHarnesses harnesses;
  private final ObjectMapper json;

  /**
   * Creates the plugin.
   *
   * @param gateway starts the process
   * @param resolver resolves config expressions
   * @param harnesses the presets
   * @param json the JSON mapper
   */
  public AgentSessionPlugin(
      final ProcessExecutorGateway gateway,
      final VariableResolver resolver,
      final AgentHarnesses harnesses,
      final ObjectMapper json) {
    this.gateway = gateway;
    this.resolver = resolver;
    this.harnesses = harnesses;
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
            + "- args: extra command-line arguments, e.g. [\"--allowedTools\", \"Read,Edit\"].\n"
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
            resolveEnv(config.get("env"), message))
        .flatMap(
            t -> {
              final long timeout = timeoutSeconds(config);
              final ProcessExecutionSpec spec =
                  ProcessExecutionSpec.builder()
                      .command(command(preset, config, t.getT1(), t.getT3()))
                      .workingDir(t.getT2().isEmpty() ? null : t.getT2())
                      .timeoutSeconds(timeout)
                      .env(t.getT4())
                      .maxOutputLines(KEPT_LINES)
                      .maxOutputBytes(KEPT_BYTES)
                      .build();
              return Mono.deferContextual(
                  ctx -> {
                    final AgentOutput output =
                        new AgentOutput(
                            preset.output(),
                            ctx.<PluginLogSink>getOrEmpty(WorkflowContext.LOG_SINK_KEY)
                                .orElse(PluginLogSink.NOOP),
                            json);
                    return gateway
                        .executeForResult(spec, output)
                        .map(r -> output.outcome(r.exitCode(), r.timedOut(), timeout));
                  });
            })
        .map(
            outcome ->
                DefaultMessage.from(message, outcome)
                    .withSourcePort(String.valueOf(outcome.get("status"))));
  }

  private static List<String> command(
      final AgentPreset preset,
      final Map<String, Object> config,
      final String prompt,
      final String model) {
    final List<String> command = new ArrayList<>();
    preset.command().forEach(a -> command.add(a.replace("{prompt}", prompt)));
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
