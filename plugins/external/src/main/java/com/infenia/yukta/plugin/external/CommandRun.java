// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.external;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginLogSink;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.process.ProcessExecutionResult;
import com.infenia.yukta.plugin.process.ProcessExecutionSpec;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a command plugin for one message: the message envelope v1 on stdin, stderr to the node's
 * log, stdout as output objects (one per line), a non-zero exit fails the message. Only the
 * configuration fields the manifest declares are sent, resolved for the message ({@code ${...}} and
 * {@code decrypted:} secrets), so a secret travels on stdin only.
 */
@SuppressWarnings({
  "PMD.TooManyMethods", // one run: resolve, start, check, parse
  "PMD.UseConcurrentHashMap" // maps local to one message
})
/* default */ final class CommandRun {

  private static final String STDERR = "stderr";

  private final PluginManifest manifest;
  private final ProcessExecutorGateway gateway;
  private final VariableResolver resolver;
  private final JsonMapper json;
  private final WireSchemas schemas;
  private final OutputLimits limits;

  /**
   * What a plugin may write to stdout for one message.
   *
   * @param maxBytes the most bytes; more fails the message
   * @param maxOutputs the most output objects; more fails the message
   */
  /* default */ record OutputLimits(long maxBytes, int maxOutputs) {}

  /* default */ CommandRun(
      final PluginManifest manifest,
      final ProcessExecutorGateway gateway,
      final VariableResolver resolver,
      final JsonMapper json,
      final WireSchemas schemas,
      final OutputLimits limits) {
    this.manifest = manifest;
    this.gateway = gateway;
    this.resolver = resolver;
    this.json = json;
    this.schemas = schemas;
    this.limits = limits;
  }

  /**
   * Runs the plugin for a message and returns its output messages.
   *
   * @param message the input
   * @param config the node configuration
   * @return the output messages, in the order the plugin wrote them
   */
  /* default */ Flux<Message<?>> outputs(
      final Message<?> message, final Map<String, Object> config) {
    return run(message, config).flatMapIterable(result -> parse(message, result));
  }

  /**
   * Runs the plugin for a message; its stdout is not read (a terminal).
   *
   * @param message the input
   * @param config the node configuration
   * @return completes when the plugin exited with 0
   */
  /* default */ Mono<Void> consume(final Message<?> message, final Map<String, Object> config) {
    return run(message, config).then();
  }

  private Mono<ProcessExecutionResult> run(
      final Message<?> message, final Map<String, Object> config) {
    return resolve(message, config)
        .flatMap(
            resolved ->
                Mono.deferContextual(
                    ctx ->
                        gateway.executeInstalled(
                            spec(message, resolved),
                            stderrOnly(
                                ctx.<PluginLogSink>getOrEmpty(WorkflowContext.LOG_SINK_KEY)
                                    .orElse(PluginLogSink.NOOP)))))
        .flatMap(this::requireSuccess);
  }

  /** The declared fields of the configuration, each resolved for the message. */
  private Mono<Map<String, Object>> resolve(
      final Message<?> message, final Map<String, Object> config) {
    return Flux.fromIterable(manifest.config())
        .map(ConfigField::name)
        .filter(name -> config.get(name) != null)
        .concatMap(
            name ->
                resolver.resolve(config.get(name), message).map(value -> Map.entry(name, value)))
        .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new);
  }

  private ProcessExecutionSpec spec(final Message<?> message, final Map<String, Object> config) {
    final Map<String, Object> msg = new LinkedHashMap<>();
    msg.put("id", message.id());
    if (message.traceId() != null) {
      msg.put("traceId", message.traceId());
    }
    msg.put("metadata", message.metadata());
    msg.put("payload", message.payload());
    final Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("apiVersion", "yukta/message/v1");
    envelope.put("config", config);
    envelope.put("message", msg);
    return ProcessExecutionSpec.builder()
        .command(manifest.command())
        .workingDir(manifest.dir().toString())
        .timeoutSeconds(Math.max(1L, (manifest.timeout().toMillis() + 999) / 1000))
        .stdin(json.writeValueAsString(envelope))
        .captureStderr(true)
        .maxOutputBytes(limits.maxBytes())
        .build();
  }

  /** The node's log gets the plugin's stderr; stdout is output, which may hold anything. */
  private static PluginLogSink stderrOnly(final PluginLogSink sink) {
    return (stream, line, level) -> {
      if (STDERR.equals(stream)) {
        sink.line(stream, line, level);
      }
    };
  }

  private Mono<ProcessExecutionResult> requireSuccess(final ProcessExecutionResult result) {
    if (result.timedOut()) {
      return Mono.error(refused("timed out after " + manifest.timeout()));
    }
    if (result.exitCode() != 0) {
      return Mono.error(refused("exited with code " + result.exitCode()));
    }
    if (result.outputTruncated()) {
      return Mono.error(refused("wrote more than " + limits.maxBytes() + " bytes"));
    }
    return Mono.just(result);
  }

  private List<Message<?>> parse(final Message<?> original, final ProcessExecutionResult result) {
    final List<String> lines =
        result.stdoutLines().stream().filter(line -> !line.isBlank()).toList();
    if (lines.size() > limits.maxOutputs()) {
      throw refused("wrote " + lines.size() + " outputs, more than " + limits.maxOutputs());
    }
    final List<Message<?>> outputs = new ArrayList<>(lines.size());
    for (final String line : lines) {
      outputs.add(output(original, line));
    }
    return outputs;
  }

  @SuppressWarnings("unchecked")
  private Message<?> output(final Message<?> original, final String line) {
    final Object doc;
    try {
      doc = json.readValue(line, Object.class);
    } catch (final JacksonException e) {
      throw refused("wrote a line that is not JSON", e);
    }
    final String refusal = schemas.outputRefusal(doc);
    if (refusal != null) {
      throw refused("wrote an invalid output: " + refusal);
    }
    final Map<String, Object> out = (Map<String, Object>) doc;
    final String port = (String) out.getOrDefault("port", manifest.ports().get(0));
    if (!manifest.ports().contains(port)) {
      throw refused("wrote to port '" + port + "', not one of " + manifest.ports());
    }
    final Map<String, Object> metadata = new HashMap<>(original.metadata());
    metadata.putAll((Map<String, Object>) out.getOrDefault("metadata", Map.of()));
    final Message<?> message =
        DefaultMessage.from(original, out.get("payload")).withMetadata(metadata);
    return message.withSourcePort(port);
  }

  private IllegalStateException refused(final String what) {
    return new IllegalStateException("Plugin " + manifest.type() + " " + what);
  }

  private IllegalStateException refused(final String what, final Throwable cause) {
    return new IllegalStateException("Plugin " + manifest.type() + " " + what, cause);
  }
}
