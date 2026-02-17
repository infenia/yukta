package com.infenia.jagratha.plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** AI plugin implementation for Qwen Code. Uses local 'qwen' command. */
@Slf4j
@Component
public class QwenCodePlugin implements AiPlugin {

  /** Public constructor for PMD. */
  public QwenCodePlugin() {
    super();
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
      final ProcessBuilder processBuilder = new ProcessBuilder(command);
      processBuilder.redirectErrorStream(true);
      final Process process = processBuilder.start();

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
}
