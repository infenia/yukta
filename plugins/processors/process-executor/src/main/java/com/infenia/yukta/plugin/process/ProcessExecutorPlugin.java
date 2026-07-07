/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.plugin.process;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.util.MapUtils;
import com.infenia.yukta.util.VariableResolver;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Processor plugin for executing external processes. supports variable resolution in command,
 * environment, and working directory.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.UseConcurrentHashMap", "PMD.AvoidDuplicateLiterals"})
public class ProcessExecutorPlugin implements ProcessorPlugin {

  private static final String TYPE = "PROCESS_EXECUTOR";
  private static final String METADATA = "METADATA";
  private static final String PAYLOAD = "PAYLOAD";
  private static final String OUTPUT_KEY = "process.output";
  private static final String COMMAND = "command";

  private final ProcessExecutorGateway gateway;
  private final VariableResolver resolver;

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public String getDescription() {
    return "Executes external processes with reactive streaming output. Supports multi-OS "
        + "(Linux, macOS, Windows) with shell wrapping and proper cleanup on timeout/error. "
        + "IMPORTANT: Large output in non-streaming mode may cause memory issues. "
        + "For large outputs, use streamOutput=true for per-line streaming. "
        + "SECURITY: Do not export sensitive data (passwords, API keys) via metadata.";
  }

  @Override
  // A bare "return """ text block collides with this project's checkstyle
  // TextBlockGoogleStyleFormatting rule, which google-java-format's own formatting of `return`
  // statements can never satisfy (spotlessApply keeps re-collapsing it); string concatenation
  // stays.
  @SuppressWarnings("StringConcatToTextBlock")
  public String getUsagePattern() {
    return "Configuration Parameters:\n\n"
        + "REQUIRED:\n"
        + "- command: List<String> (SpEL supported)\n"
        + "  Command and arguments to execute\n"
        + "  Example: [\"mvn\", \"clean\", \"test\"] or [\"./gradlew\", \"build\"]\n"
        + "\nOPTIONAL:\n"
        + "- workingDir: String (SpEL supported, default: current directory)\n"
        + "  Working directory for process execution\n"
        + "  Example: \"/path/to/project\"\n"
        + "\n- timeout: Long (SpEL supported, default: 300 seconds)\n"
        + "  Maximum execution time in seconds. Must be positive.\n"
        + "  Example: 600 (for 10-minute timeout)\n"
        + "\n- env: Map<String, String> (SpEL supported, default: empty)\n"
        + "  Environment variables for the process\n"
        + "  Example: {\"NODE_ENV\": \"production\", \"DEBUG\": \"true\"}\n"
        + "\n- useShell: Boolean (default: false)\n"
        + "  Execute command via shell (sh on Unix, cmd.exe on Windows)\n"
        + "  Use true only if command contains pipes, redirects, or other shell features\n"
        + "\n- streamOutput: Boolean (default: false)\n"
        + "  Stream output per line (true) vs buffer entire output (false)\n"
        + "  Recommended: true for long-running processes or large outputs\n"
        + "\n- outputTarget: String (default: \"PAYLOAD\")\n"
        + "  Where to store output: \"PAYLOAD\" or \"METADATA\"\n"
        + "  PAYLOAD: Output becomes the message payload\n"
        + "  METADATA: Output stored in metadata[\"process.output\"]\n";
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    log.atDebug().log("Process executor starting: processing input stream");
    return input.flatMapSequential(message -> executeProcess(message, config));
  }

  private Flux<Message<?>> executeProcess(
      final Message<?> message, final Map<String, Object> config) {
    log.atDebug().log("Starting executeProcess for message");
    return resolveConfig(config)
        .flatMapMany(
            resolvedConfig -> {
              @SuppressWarnings("unchecked")
              final List<String> command = (List<String>) resolvedConfig.get("command");
              final String workingDir = (String) resolvedConfig.get("workingDir");
              final long timeout = (long) resolvedConfig.getOrDefault("timeout", 300L);
              @SuppressWarnings("unchecked")
              final Map<String, String> env =
                  (Map<String, String>) resolvedConfig.getOrDefault("env", Map.of());
              final boolean useShell = (boolean) resolvedConfig.getOrDefault("useShell", false);
              final boolean streamOutput =
                  (boolean) resolvedConfig.getOrDefault("streamOutput", false);
              final String outputTarget =
                  (String) resolvedConfig.getOrDefault("outputTarget", "PAYLOAD");

              log.atInfo().log(
                  "Starting process execution: command={}, timeout={}s, streamOutput={}, "
                      + "outputTarget={}",
                  command,
                  timeout,
                  streamOutput,
                  outputTarget);

              if (streamOutput) {
                return gateway
                    .executeStream(command, workingDir, timeout, env, useShell)
                    .doOnNext(line -> log.atDebug().log("Output line received: {}", line))
                    .map(line -> createOutputMessage(message, line, outputTarget))
                    .doOnComplete(
                        () -> log.atInfo().log("Process completed successfully: streaming mode"));
              } else {
                return gateway
                    .executeStream(command, workingDir, timeout, env, useShell)
                    .collectList()
                    .map(list -> String.join("\n", list))
                    .doOnNext(
                        output -> {
                          log.atInfo()
                              .setMessage("Process completed: buffer mode, output size={} bytes")
                              .addArgument(output.length())
                              .log();
                          log.atInfo().setMessage("Process output:\n{}").addArgument(output).log();
                        })
                    .map(output -> createOutputMessage(message, output, outputTarget))
                    .flux()
                    .doOnError(
                        error -> {
                          log.atError()
                              .setMessage(
                                  "Process execution failed: command={}, workingDir={}, error"
                                      + " type={}, error message={}")
                              .addArgument(command)
                              .addArgument(workingDir)
                              .addArgument(error.getClass().getSimpleName())
                              .addArgument(error.getMessage())
                              .setCause(error)
                              .log();
                          if (error instanceof WorkflowExecutionException wee) {
                            log.atError()
                                .setMessage("WorkflowExecutionException details: {}")
                                .addArgument(wee.getMessage())
                                .log();
                          }
                        });
              }
            })
        .doOnError(
            error ->
                log.atError()
                    .setCause(error)
                    .log(
                        "Process executor configuration resolution failed: {}",
                        error.getMessage()));
  }

  @SuppressWarnings("unchecked")
  private Mono<Map<String, Object>> resolveConfig(final Map<String, Object> config) {
    return Mono.zip(
            resolveCommand((List<Object>) config.get(COMMAND)),
            resolveValue(config.get("workingDir")).map(Object::toString).defaultIfEmpty(""),
            resolveValue(config.getOrDefault("timeout", 300L))
                .map(v -> MapUtils.convert(v, Long.class))
                .defaultIfEmpty(300L),
            resolveEnv((Map<String, Object>) config.getOrDefault("env", Map.of())),
            resolveValue(config.getOrDefault("useShell", false))
                .map(v -> MapUtils.convert(v, Boolean.class))
                .defaultIfEmpty(false),
            resolveValue(config.getOrDefault("streamOutput", false))
                .map(v -> MapUtils.convert(v, Boolean.class))
                .defaultIfEmpty(false),
            resolveValue(config.getOrDefault("outputTarget", PAYLOAD))
                .map(Object::toString)
                .defaultIfEmpty(PAYLOAD))
        .map(
            tuple -> {
              final Map<String, Object> resolved = new HashMap<>();
              resolved.put("command", tuple.getT1());
              resolved.put("workingDir", tuple.getT2().isEmpty() ? null : tuple.getT2());
              resolved.put("timeout", tuple.getT3());
              resolved.put("env", tuple.getT4());
              resolved.put("useShell", tuple.getT5());
              resolved.put("streamOutput", tuple.getT6());
              resolved.put("outputTarget", tuple.getT7());
              return resolved;
            });
  }

  private Mono<List<String>> resolveCommand(final List<Object> command) {
    if (command == null || command.isEmpty()) {
      log.atWarn().log("Command resolution failed: command is mandatory");
      return Mono.error(new IllegalArgumentException("command is mandatory"));
    }
    return Flux.fromIterable(command)
        .flatMapSequential(this::resolveValue)
        .map(Object::toString)
        .collectList();
  }

  private Mono<Map<String, String>> resolveEnv(final Map<String, Object> env) {
    if (env == null || env.isEmpty()) {
      return Mono.just(Map.of());
    }
    return Flux.fromIterable(env.entrySet())
        .flatMapSequential(
            entry ->
                resolveValue(entry.getValue())
                    .map(val -> Map.entry(entry.getKey(), val.toString())))
        .collectMap(Map.Entry::getKey, Map.Entry::getValue)
        .doOnNext(
            resolved ->
                log.atDebug().log(
                    "Process executor environment variables resolved: {} variable(s)",
                    resolved.size()));
  }

  private Mono<Object> resolveValue(final Object value) {
    if (value == null) {
      return Mono.justOrEmpty(null);
    }
    return resolver.resolve(value);
  }

  private Message<?> createOutputMessage(
      final Message<?> original, final String output, final String target) {
    final String normalizedTarget = target == null ? PAYLOAD : target.toUpperCase(Locale.ROOT);

    if (METADATA.equals(normalizedTarget)) {
      final Map<String, Object> newMetadata = new HashMap<>(original.getMetadata());
      newMetadata.put(OUTPUT_KEY, output);
      log.atDebug().log(
          "Process output stored in metadata: key={}, size={} bytes", OUTPUT_KEY, output.length());
      return DefaultMessage.from(original, original.getPayload()).withMetadata(newMetadata);
    } else if (PAYLOAD.equals(normalizedTarget)) {
      log.atDebug().log("Process output stored in payload: {} bytes", output.length());
      return DefaultMessage.from(original, output);
    } else {
      log.atWarn()
          .log(
              "Invalid outputTarget: '{}'. Must be 'PAYLOAD' or 'METADATA'. Defaulting to PAYLOAD.",
              target);
      return DefaultMessage.from(original, output);
    }
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    if (config.get("command") == null) {
      log.atWarn().log("Process executor configuration validation failed: command is mandatory");
      return Mono.error(new IllegalArgumentException("command is mandatory"));
    }
    return Mono.empty();
  }
}
