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
package com.infenia.yukta.logging.impl.file;

import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * File-system based implementation of PluginLogWriter.
 *
 * <p>Persists logs to /var/log/yukta/plugins/{sessionId}/{executionId}.log with timestamp, stream,
 * logLevel, pluginId, pluginName, and message format. Newlines embedded in messages are escaped so
 * each log entry stays on a single line.
 */
@Slf4j
@RequiredArgsConstructor
public class FileSystemPluginLogWriter implements PluginLogWriter {

  /** Log file extension used for plugin log files. */
  private static final String LOG_FILE_EXTENSION = ".log";

  /** Base directory for log files. */
  private final String baseLogDir;

  /**
   * Create FileSystemPluginLogWriter with default base directory.
   *
   * @return a new instance using /var/log/yukta/plugins
   */
  public static FileSystemPluginLogWriter withDefaultDir() {
    return new FileSystemPluginLogWriter("/var/log/yukta/plugins");
  }

  @Override
  public Mono<Void> write(final PluginLogEntry entry) {
    return writeBatch(List.of(entry));
  }

  @Override
  public Mono<Void> writeBatch(final List<PluginLogEntry> entries) {
    final Mono<Void> result;
    if (entries.isEmpty()) {
      result = Mono.empty();
    } else {
      result =
          Mono.fromRunnable(
                  () -> {
                    final var entriesByExecution =
                        entries.stream()
                            .collect(Collectors.groupingBy(PluginLogEntry::executionId));

                    for (final var entry : entriesByExecution.entrySet()) {
                      final String executionId = entry.getKey();
                      final List<PluginLogEntry> execEntries = entry.getValue();
                      final String sessionId = execEntries.getFirst().sessionId();
                      writeToFile(sessionId, executionId, execEntries);
                    }
                  })
              .subscribeOn(Schedulers.boundedElastic())
              .doFinally(
                  _ ->
                      log.atTrace()
                          .addKeyValue("entriesCount", entries.size())
                          .log("Wrote plugin log batch to file system"))
              .then();
    }
    return result;
  }

  @Override
  public Mono<Void> close() {
    return Mono.fromRunnable(() -> log.atDebug().log("Closed file system plugin log writer"));
  }

  private void writeToFile(
      final String sessionId, final String executionId, final List<PluginLogEntry> entries) {
    try {
      final Path logDir = Path.of(baseLogDir).resolve(sessionId);
      Files.createDirectories(logDir);

      final Path logFile = logDir.resolve(executionId + LOG_FILE_EXTENSION);
      final String content =
          entries.stream()
              .map(
                  e ->
                      String.format(
                          "%s | %s | %s | %s | %s | %s",
                          e.timestamp(),
                          e.stream(),
                          e.logLevel(),
                          e.pluginId(),
                          e.pluginName(),
                          escapeMessage(e.message())))
              .collect(Collectors.joining("\n"));

      final OpenOption[] options =
          Files.exists(logFile)
              ? new OpenOption[] {StandardOpenOption.APPEND}
              : new OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.WRITE};

      Files.writeString(logFile, content + "\n", options);

      log.atTrace()
          .addKeyValue("sessionId", sessionId)
          .addKeyValue("executionId", executionId)
          .addKeyValue("entriesCount", entries.size())
          .log("Wrote entries to file");
    } catch (final IOException e) {
      log.atError()
          .addKeyValue("sessionId", sessionId)
          .addKeyValue("executionId", executionId)
          .setCause(e)
          .log("Failed to write plugin logs to file");
    }
  }

  /**
   * Escape newlines and carriage returns in a message so it stays on a single line in the log file.
   *
   * @param message the raw message
   * @return the message with embedded line terminators escaped
   */
  private static String escapeMessage(final String message) {
    return message.replace("\r", "\\r").replace("\n", "\\n");
  }
}
