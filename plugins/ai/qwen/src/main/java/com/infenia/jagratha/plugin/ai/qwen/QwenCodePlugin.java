package com.infenia.jagratha.plugin.ai.qwen;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/** AI plugin implementation for Qwen Code. Uses local 'qwen' command. */
@Slf4j
public class QwenCodePlugin implements com.infenia.jagratha.plugin.AiPlugin {

  private final ProcessExecutor processExecutor;

  /** Public constructor for PMD. */
  public QwenCodePlugin() {
    this(new DefaultProcessExecutor());
  }

  /**
   * Constructor with process executor for testing.
   *
   * @param processExecutor the process executor
   */
  public QwenCodePlugin(final ProcessExecutor processExecutor) {
    super();
    this.processExecutor = processExecutor;
  }

  @Override
  public String getName() {
    return "qwen-code";
  }

  @Override
  @SuppressWarnings("PMD.DoNotUseThreads")
  public String execute(final String prompt, final Map<String, Object> config) {
    final List<String> command = List.of("qwen", "--prompt", prompt);

    if (log.isInfoEnabled()) {
      log.info("Executing Qwen with prompt length: {}", prompt.length());
    }

    String result;
    try {
      final Process process = processExecutor.execute(command);

      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        final String output = reader.lines().collect(Collectors.joining("\n"));
        final int exitCode = process.waitFor();
        if (exitCode == 0) {
          result = output;
        } else {
          log.warn("Qwen execution failed with exit code {}", exitCode);
          result = "Error executing Qwen (exit code " + exitCode + "): " + output;
        }
      }
    } catch (IOException e) {
      log.error("Failed to execute Qwen", e);
      result = "Error executing Qwen: " + e.getMessage();
    } catch (InterruptedException e) {
      log.error("Qwen execution interrupted", e);
      Thread.currentThread().interrupt();
      result = "Error executing Qwen: interrupted";
    }
    return result;
  }

  /** Interface for executing external processes. */
  @FunctionalInterface
  public interface ProcessExecutor {
    /**
     * Execute a command.
     *
     * @param command the command to execute
     * @return the process
     * @throws IOException if execution fails
     */
    Process execute(List<String> command) throws IOException;
  }

  /** Default implementation of ProcessExecutor. */
  private static final class DefaultProcessExecutor implements ProcessExecutor {
    @Override
    public Process execute(final List<String> command) throws IOException {
      final ProcessBuilder processBuilder = new ProcessBuilder(command);
      processBuilder.redirectErrorStream(true);
      return processBuilder.start();
    }
  }
}
