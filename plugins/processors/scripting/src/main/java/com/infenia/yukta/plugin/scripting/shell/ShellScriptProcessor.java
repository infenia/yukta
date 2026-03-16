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
package com.infenia.yukta.plugin.scripting.shell;

import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * A processor plugin that executes shell scripts (bash).
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>script (required): The shell script content to execute.
 *   <li>executable (optional): The shell executable to use. Default is /bin/bash.
 *   <li>timeout (optional): Execution timeout in seconds. Default is 60.
 *   <li>workingDir (optional): The working directory for the script.
 * </ul>
 */
@Slf4j
@Component
public class ShellScriptProcessor implements ProcessorPlugin {

  private static final String TYPE = "SHELL_SCRIPT";
  private static final String CONFIG_SCRIPT = "script";
  private static final String CONFIG_EXECUTABLE = "executable";
  private static final String CONFIG_TIMEOUT = "timeout";
  private static final String CONFIG_WORK_DIR = "workingDir";

  private static final String DEF_EXE = "/bin/bash";
  private static final long DEFAULT_TIMEOUT = 60L;

  /** Default constructor. */
  public ShellScriptProcessor() {
    super();
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public String getDescription() {
    return "Executes a shell script (bash) and returns the output as a message payload.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- script: The shell script content.\n"
        + "- executable: Optional path to shell (default: /bin/bash).\n"
        + "- timeout: Optional timeout in seconds (default: 60).\n"
        + "- workingDir: Optional working directory.";
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    return input.flatMap(message -> executeScript(message, config));
  }

  private Mono<Message<?>> executeScript(
      final Message<?> message, final Map<String, Object> config) {

    return Mono.<Message<?>>fromCallable(() -> executeScriptSync(message, config))
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorMap(this::mapError);
  }

  private Message<?> executeScriptSync(final Message<?> message, final Map<String, Object> config)
      throws IOException, InterruptedException {
    final String script = (String) config.get(CONFIG_SCRIPT);
    final String executable = (String) config.getOrDefault(CONFIG_EXECUTABLE, DEF_EXE);
    final long timeout =
        ((Number) config.getOrDefault(CONFIG_TIMEOUT, DEFAULT_TIMEOUT)).longValue();
    if (script == null || script.isBlank()) {
      throw new WorkflowExecutionException("script configuration is mandatory");
    }

    final ProcessBuilder processBuilder = new ProcessBuilder(executable, "-c", script);
    final String workingDir = (String) config.get(CONFIG_WORK_DIR);
    if (workingDir != null && !workingDir.isBlank()) {
      processBuilder.directory(new File(workingDir));
    }

    // Export metadata as environment variables
    exportMetadata(message, processBuilder.environment());

    processBuilder.redirectErrorStream(true);
    final Process process = processBuilder.start();
    try {
      final boolean finished = process.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        throw new WorkflowExecutionException(
            "Shell script execution timed out after " + timeout + "s");
      }
      return createMessageFromProcess(message, process);
    } finally {
      process.destroyForcibly();
    }
  }

  private Message<?> createMessageFromProcess(final Message<?> message, final Process process)
      throws IOException {
    final String output = readProcessOutput(process.getInputStream());
    final int exitCode = process.exitValue();

    if (exitCode != 0) {
      throw new WorkflowExecutionException(
          "Shell script failed with exit code " + exitCode + ". Output: " + output);
    }
    return message.withPayload(output);
  }

  private String readProcessOutput(final InputStream inputStream) throws IOException {
    final byte[] outputBytes = inputStream.readAllBytes();
    return new String(outputBytes, StandardCharsets.UTF_8);
  }

  private void exportMetadata(final Message<?> message, final Map<String, String> env) {
    message
        .getMetadata()
        .forEach(
            (key, value) -> {
              if (value != null) {
                final String metadataValue = value.toString();
                final String keyUpper = key.toUpperCase(java.util.Locale.ROOT);
                final String envKey = "YUKTA_METADATA_" + keyUpper.replace('.', '_');
                env.put(envKey, metadataValue);
              }
            });
  }

  /**
   * Maps throwables to WorkflowExecutionException.
   *
   * @param throwable the error to map
   * @return the mapped exception
   */
  protected Throwable mapError(final Throwable throwable) {
    final Throwable result;
    if (throwable instanceof WorkflowExecutionException) {
      result = throwable;
    } else if (throwable instanceof IOException) {
      result =
          new WorkflowExecutionException(
              "Failed to start shell script: " + throwable.getMessage(), throwable);
    } else if (throwable instanceof InterruptedException) {
      result = new WorkflowExecutionException("Shell script execution interrupted", throwable);
    } else {
      result =
          new WorkflowExecutionException(
              "Shell script execution failed: " + throwable.getMessage(), throwable);
    }
    return result;
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    final String script = (String) config.get(CONFIG_SCRIPT);
    final Mono<Void> result;
    if (script == null || script.isBlank()) {
      result = Mono.error(new IllegalArgumentException("script is mandatory"));
    } else {
      result = Mono.empty();
    }
    return result;
  }
}
