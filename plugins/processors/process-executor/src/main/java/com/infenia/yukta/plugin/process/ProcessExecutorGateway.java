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

import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Gateway for executing external processes with reactive streaming output. */
@Slf4j
@Service
@SuppressWarnings({
  "PMD.OnlyOneReturn",
  "PMD.UseConcurrentHashMap",
  "PMD.CognitiveComplexity",
  "PMD.AtLeastOneConstructor"
})
@SuppressFBWarnings(
    value = "OS_OPEN_STREAM",
    justification = "Streams are properly closed via Flux.usingWhen lifecycle management")
public class ProcessExecutorGateway {

  /**
   * Execute a process and return the output as a stream of strings with newlines preserved.
   *
   * @param command the command and arguments to execute
   * @param workingDir the working directory (null defaults to current directory)
   * @param timeoutSeconds the timeout in seconds (must be positive)
   * @param env custom environment variables
   * @param useShell whether to execute the command via a shell
   * @return a Flux containing the process output lines with newlines preserved
   * @throws IllegalArgumentException if timeoutSeconds is not positive
   */
  public Flux<String> executeStream(
      final List<String> command,
      final String workingDir,
      final long timeoutSeconds,
      final Map<String, String> env,
      final boolean useShell) {
    if (timeoutSeconds <= 0) {
      return Flux.error(
          new IllegalArgumentException("timeoutSeconds must be positive, got: " + timeoutSeconds));
    }

    final List<String> actualCommand = useShell ? wrapInShell(command) : command;

    return Flux.usingWhen(
            Mono.fromCallable(
                    () -> {
                      log.debug("Starting process: {}", actualCommand);
                      final ProcessBuilder processBuilder = new ProcessBuilder(actualCommand);
                      if (workingDir != null && !workingDir.isBlank()) {
                        processBuilder.directory(new File(workingDir));
                      }
                      if (env != null) {
                        processBuilder.environment().putAll(env);
                      }
                      processBuilder.redirectErrorStream(true);
                      return processBuilder.start();
                    })
                .subscribeOn(Schedulers.boundedElastic()),
            process -> {
              final java.io.BufferedReader reader =
                  new java.io.BufferedReader(
                      new java.io.InputStreamReader(
                          process.getInputStream(), StandardCharsets.UTF_8));
              final Flux<String> lines =
                  Flux.fromStream(reader.lines())
                      .doFinally(
                          signalType -> {
                            try {
                              reader.close();
                            } catch (java.io.IOException e) {
                              log.warn("Failed to close process output reader", e);
                            }
                          })
                      .subscribeOn(Schedulers.boundedElastic());

              // Validate exit code after all lines are consumed to ensure proper ordering
              final Mono<Void> exitCheck =
                  Mono.fromFuture(process.onExit())
                      .flatMap(
                          p -> {
                            final int exitCode = p.exitValue();
                            if (exitCode != 0) {
                              log.warn(
                                  "Process completed with exit code {}: {}",
                                  exitCode,
                                  actualCommand);
                              return Mono.error(
                                  new WorkflowExecutionException(
                                      "Process failed with exit code " + exitCode));
                            }
                            log.debug("Process completed successfully: {}", actualCommand);
                            return Mono.empty();
                          })
                      .then()
                      .subscribeOn(Schedulers.boundedElastic());

              // Emit all lines first, then validate exit code
              return lines.concatWith(exitCheck.then(Mono.empty()));
            },
            process ->
                Mono.fromRunnable(
                        () -> {
                          if (process.isAlive()) {
                            process.destroy();
                          }
                        })
                    .subscribeOn(Schedulers.boundedElastic()),
            (process, err) ->
                Mono.fromRunnable(
                        () -> {
                          if (process.isAlive()) {
                            log.warn("Forcibly destroying process due to error: {}", actualCommand);
                            process.destroyForcibly();
                          }
                        })
                    .subscribeOn(Schedulers.boundedElastic()),
            process ->
                Mono.fromRunnable(
                        () -> {
                          if (process.isAlive()) {
                            process.destroy();
                          }
                        })
                    .subscribeOn(Schedulers.boundedElastic()))
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .onErrorMap(
            e -> {
              if (e instanceof TimeoutException) {
                log.error("Process timed out after {}s: {}", timeoutSeconds, actualCommand);
                return new WorkflowExecutionException(
                    "Process timed out after " + timeoutSeconds + "s", e);
              }
              return e;
            });
  }

  /**
   * Execute a process with the given command, working directory, and timeout.
   *
   * <p><strong>MEMORY WARNING:</strong> This method buffers the entire process output in memory.
   * For processes generating large outputs (>100MB), consider using {@link #executeStream(List,
   * String, long, Map, boolean)} with streaming instead to avoid Out-of-Memory errors.
   *
   * @param command the command and arguments to execute
   * @param workingDir the working directory (null defaults to current directory)
   * @param timeoutSeconds the timeout in seconds
   * @return a Mono containing the process output with newlines preserved between lines
   */
  public Mono<String> execute(
      final List<String> command, final String workingDir, final long timeoutSeconds) {
    return executeStream(command, workingDir, timeoutSeconds, Map.of(), false)
        .collectList()
        // Restore newlines between lines that were stripped by BufferedReader.lines()
        .map(list -> String.join("\n", list))
        .onErrorMap(
            e -> {
              if (e instanceof WorkflowExecutionException) {
                return e;
              }
              return new WorkflowExecutionException(
                  "Process execution failed: " + e.getMessage(), e);
            });
  }

  /**
   * Execute a process with metadata exported as environment variables.
   *
   * <p><strong>SECURITY WARNING:</strong> All metadata values are exported as YUKTA_METADATA_*
   * environment variables, which are:
   *
   * <ul>
   *   <li>Visible to all child processes spawned by the command
   *   <li>May appear in process listings and logs
   *   <li>Potentially visible in stack traces and error messages
   * </ul>
   *
   * <p><strong>Do NOT export sensitive data such as:</strong> passwords, API keys, tokens, private
   * keys, or other credentials.
   *
   * <p><strong>MEMORY WARNING:</strong> This method buffers the entire process output in memory.
   * For processes generating large outputs (>100MB), consider using {@link #executeStream(List,
   * String, long, Map, boolean)} with streaming instead to avoid Out-of-Memory errors.
   *
   * @param command the command and arguments to execute
   * @param workingDir the working directory (null defaults to current directory)
   * @param timeoutSeconds the timeout in seconds
   * @param metadata the metadata map to export as environment variables (must not contain sensitive
   *     data)
   * @return a Mono containing the process output with newlines preserved between lines
   */
  public Mono<String> executeWithMetadata(
      final List<String> command,
      final String workingDir,
      final long timeoutSeconds,
      final Map<String, Object> metadata) {

    final Map<String, String> env = new HashMap<>();
    if (metadata != null) {
      exportMetadata(metadata, env);
    }

    return executeStream(command, workingDir, timeoutSeconds, env, false)
        .collectList()
        // Restore newlines between lines that were stripped by BufferedReader.lines()
        .map(list -> String.join("\n", list))
        .onErrorMap(
            e -> {
              if (e instanceof WorkflowExecutionException) {
                return e;
              }
              return new WorkflowExecutionException(
                  "Process execution failed: " + e.getMessage(), e);
            });
  }

  /**
   * Wrap the command in a shell based on the OS. Command arguments are properly escaped to prevent
   * shell injection.
   *
   * @param command the original command and arguments
   * @return the shell-wrapped command
   */
  private List<String> wrapInShell(final List<String> command) {
    final String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    final List<String> wrapped = new ArrayList<>();
    if (osName.contains("win")) {
      wrapped.add("cmd.exe");
      wrapped.add("/c");
    } else {
      wrapped.add("/bin/sh");
      wrapped.add("-c");
    }
    // Escape shell metacharacters to prevent injection
    final String joined =
        command.stream().map(this::escapeShellArg).collect(Collectors.joining(" "));
    wrapped.add(joined);
    return wrapped;
  }

  /**
   * Escape a shell argument by wrapping in single quotes and escaping single quotes within, but
   * only if necessary. This prevents shell metacharacter injection by handling dangerous characters
   * like semicolons, pipes, redirects, etc.
   *
   * @param arg the argument to escape
   * @return the escaped argument safe for shell execution
   */
  private String escapeShellArg(final String arg) {
    // Check if arg contains dangerous shell metacharacters that require quoting
    // Allow alphanumeric, spaces, common punctuation, paths, hyphens, underscores
    // Only quote if it contains shell special chars like ;|&$`<>(){}[]'"\
    if (arg.matches("^[a-zA-Z0-9._/ :,()\\-]*$")) {
      // Safe argument - contains only benign characters
      return arg;
    }
    // Unsafe argument - wrap in single quotes and escape any single quotes within
    // by closing the quote, adding an escaped quote, and reopening
    return "'" + arg.replace("'", "'\"'\"'") + "'";
  }

  /**
   * Export metadata as environment variables with YUKTA_METADATA_ prefix. Useful for passing
   * workflow context to spawned processes.
   *
   * @param metadata the metadata map
   * @param env the environment map to populate
   */
  private void exportMetadata(final Map<String, Object> metadata, final Map<String, String> env) {
    metadata.forEach(
        (key, value) -> {
          if (value != null) {
            final String metadataValue = value.toString();
            final String keyUpper = key.toUpperCase(Locale.ROOT);
            final String envKey = "YUKTA_METADATA_" + keyUpper.replace('.', '_');
            env.put(envKey, metadataValue);
          }
        });
  }
}
