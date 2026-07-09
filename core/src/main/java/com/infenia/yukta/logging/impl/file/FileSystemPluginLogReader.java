// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.impl.file;

import com.infenia.yukta.logging.api.ExecutionSummary;
import com.infenia.yukta.logging.api.LogLevel;
import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * File-system based implementation of PluginLogReader.
 *
 * <p>Reads logs from /var/log/yukta/plugins/{sessionId}/*.log files.
 */
@Slf4j
@RequiredArgsConstructor
@SuppressWarnings("PMD.UnnecessaryModifier")
public class FileSystemPluginLogReader implements PluginLogReader {

  /** Log file extension used for plugin log files. */
  private static final String LOG_FILE_EXTENSION = ".log";

  /** Minimum number of parts expected in a parsed log line. */
  private static final int MIN_LOG_PARTS = 6;

  /** ISO offset date time formatter for parsing log timestamps. */
  private static final DateTimeFormatter TIMESTAMP_FORMAT =
      DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

  /** Base directory for log files. */
  private final String baseLogDir;

  @Override
  public Flux<PluginLogEntry> readExecution(final String executionId) {
    return Mono.fromCallable(
            () -> {
              final List<PluginLogEntry> entries = new ArrayList<>();
              final Path logFile = findExecutionLogFile(executionId);
              if (logFile != null) {
                final Path sessionDir = logFile.getParent();
                entries.addAll(readLogFile(logFile, sessionDir.getFileName().toString()));
              }
              return entries;
            })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapIterable(entries -> entries);
  }

  /**
   * Scan the base log directory's sessions for the log file matching an execution.
   *
   * @param executionId the execution to locate
   * @return the matching log file path, or {@code null} if not found
   */
  private Path findExecutionLogFile(final String executionId) {
    final Path baseDir = Path.of(baseLogDir);
    Path result = null;

    if (Files.exists(baseDir)) {
      try (final var sessions = Files.list(baseDir)) {
        result =
            sessions
                .map(sessionDir -> sessionDir.resolve(executionId + LOG_FILE_EXTENSION))
                .filter(Files::exists)
                .findFirst()
                .orElse(null);
      } catch (final IOException e) {
        log.atWarn()
            .addKeyValue("baseDir", baseLogDir)
            .setCause(e)
            .log("Error scanning sessions for execution log");
      }
    }
    return result;
  }

  @Override
  public Flux<PluginLogEntry> readSession(final String sessionId) {
    return Mono.fromCallable(
            () -> {
              final List<PluginLogEntry> entries = new ArrayList<>();
              final Path sessionDir = getValidatedSessionDir(sessionId);

              if (!Files.exists(sessionDir)) {
                return entries;
              }

              try (final var files = Files.list(sessionDir)) {
                files
                    .filter(logFile -> logFile.toString().endsWith(LOG_FILE_EXTENSION))
                    .forEach(logFile -> entries.addAll(readLogFile(logFile, sessionId)));
              } catch (final IOException e) {
                log.atWarn()
                    .addKeyValue("sessionId", sessionId)
                    .setCause(e)
                    .log("Error reading session logs");
              }

              entries.sort((a, b) -> a.timestamp().compareTo(b.timestamp()));
              return entries;
            })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapIterable(entries -> entries);
  }

  @Override
  public Mono<List<ExecutionSummary>> listExecutions(final String sessionId) {
    return Mono.fromCallable(
            () -> {
              final List<ExecutionSummary> summaries = new ArrayList<>();
              final Path sessionDir = getValidatedSessionDir(sessionId);

              if (!Files.exists(sessionDir)) {
                return summaries;
              }

              try (final var files = Files.list(sessionDir)) {
                files
                    .filter(logFile -> logFile.toString().endsWith(LOG_FILE_EXTENSION))
                    .forEach(
                        logFile -> {
                          final String executionId =
                              logFile.getFileName().toString().replace(LOG_FILE_EXTENSION, "");
                          final List<PluginLogEntry> entries = readLogFile(logFile, sessionId);

                          if (!entries.isEmpty()) {
                            final Instant startInstant = entries.getFirst().timestamp();
                            final Instant endInstant = entries.getLast().timestamp();
                            final var startTime =
                                ZonedDateTime.ofInstant(startInstant, ZoneId.systemDefault())
                                    .toLocalDateTime();
                            final var endTime =
                                ZonedDateTime.ofInstant(endInstant, ZoneId.systemDefault())
                                    .toLocalDateTime();
                            summaries.add(
                                new ExecutionSummary(
                                    executionId, sessionId, startTime, endTime, entries.size()));
                          }
                        });
              } catch (final IOException e) {
                log.atWarn()
                    .addKeyValue("sessionId", sessionId)
                    .setCause(e)
                    .log("Error listing executions");
              }

              return summaries.stream()
                  .sorted((a, b) -> b.startTime().compareTo(a.startTime()))
                  .collect(Collectors.toList());
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  @Override
  public Mono<String> getRawContent(final String executionId) {
    return Mono.fromCallable(
            () -> {
              final Path matchedLogFile = findExecutionLogFile(executionId);

              if (matchedLogFile == null) {
                return "";
              }

              try {
                return Files.readString(matchedLogFile, StandardCharsets.UTF_8);
              } catch (final IOException e) {
                log.atWarn()
                    .addKeyValue("executionId", executionId)
                    .setCause(e)
                    .log("Error reading raw log content");
                return "";
              }
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  /**
   * Get the validated session directory path.
   *
   * @param sessionId the session ID
   * @return the validated session directory path
   * @throws IllegalArgumentException if the resolved path escapes the base log directory
   */
  private Path getValidatedSessionDir(final String sessionId) {
    final Path baseDir = Path.of(baseLogDir).normalize().toAbsolutePath();
    final Path sessionDir = baseDir.resolve(sessionId).normalize().toAbsolutePath();

    if (!sessionDir.startsWith(baseDir)) {
      throw new IllegalArgumentException("Invalid session ID: path traversal detected");
    }

    return sessionDir;
  }

  private List<PluginLogEntry> readLogFile(final Path logFile, final String sessionId) {
    final List<PluginLogEntry> entries = new ArrayList<>();
    final String executionId =
        Objects.requireNonNull(logFile.getFileName()).toString().replace(LOG_FILE_EXTENSION, "");

    try {
      final List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
      for (final String line : lines) {
        final PluginLogEntry entry = parseLine(line, executionId, sessionId);
        if (entry != null) {
          entries.add(entry);
        }
      }
    } catch (final IOException e) {
      log.atWarn().addKeyValue("logFile", logFile).setCause(e).log("Error reading log file");
    }

    return entries;
  }

  private PluginLogEntry parseLine(
      final String line, final String executionId, final String sessionId) {
    PluginLogEntry entry = null;
    try {
      final List<String> parts = List.of(line.split(" \\| ", MIN_LOG_PARTS));
      if (parts.size() >= MIN_LOG_PARTS) {
        final String timestampStr = parts.get(0).trim();
        final Instant timestamp = parseTimestamp(timestampStr);
        final LogStream stream = LogStream.valueOf(parts.get(1).trim());
        final LogLevel logLevel = LogLevel.valueOf(parts.get(2).trim());
        final String pluginId = parts.get(3).trim();
        final String pluginName = parts.get(4).trim();
        final String message = unescapeMessage(parts.get(5).trim());

        entry =
            new PluginLogEntry(
                executionId,
                sessionId,
                pluginId,
                pluginName,
                stream,
                message,
                logLevel,
                timestamp,
                null,
                null);
      }
    } catch (final IllegalArgumentException | IndexOutOfBoundsException e) {
      log.atTrace()
          .addKeyValue("line", line.substring(0, Math.min(50, line.length())))
          .setCause(e)
          .log("Failed to parse log line");
    }
    return entry;
  }

  /**
   * Reverse the line-terminator escaping applied by FileSystemPluginLogWriter.
   *
   * @param message the escaped message read from a log line
   * @return the original message with newlines and carriage returns restored
   */
  private static String unescapeMessage(final String message) {
    return message.replace("\\n", "\n").replace("\\r", "\r");
  }

  private Instant parseTimestamp(final String timestampStr) {
    Instant result;
    try {
      result = ZonedDateTime.parse(timestampStr, TIMESTAMP_FORMAT).toInstant();
    } catch (final DateTimeParseException e) {
      result = parseLocalTimestamp(timestampStr);
    }
    return result;
  }

  private Instant parseLocalTimestamp(final String timestampStr) {
    Instant result;
    try {
      result =
          LocalDateTime.parse(timestampStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
              .atZone(ZoneId.systemDefault())
              .toInstant();
    } catch (final DateTimeParseException e) {
      log.atDebug()
          .addKeyValue("timestampStr", timestampStr)
          .setCause(e)
          .log("Failed to parse timestamp, using current time");
      result = Instant.now();
    }
    return result;
  }
}
