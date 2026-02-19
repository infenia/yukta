package com.infenia.jagratha.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infenia.jagratha.config.AppConfigService;
import com.infenia.jagratha.exception.UncheckedIoException;
import com.infenia.jagratha.util.ReactiveLock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Service for managing modified file logs for sessions. */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.UseConcurrentHashMap"})
public class FileLogService {

  private final AppConfigService configService;
  private final ObjectMapper objectMapper;

  private static final String PENDING_STATUS = "PENDING";

  private final Map<String, ReactiveLock> locks = new ConcurrentHashMap<>();

  private ReactiveLock getLock(final String sessionId) {
    return locks.computeIfAbsent(sessionId, k -> new ReactiveLock());
  }

  /**
   * Log a file path to a session-specific file.
   *
   * @param path the file path to log
   * @param sessionId the session identifier
   * @return Mono that completes when the file path is logged
   */
  public Mono<Void> saveFile(final String path, final String sessionId) {
    return Mono.defer(
            () -> {
              final String logsDir = configService.getFileLogDir(sessionId);
              if (logsDir == null || logsDir.isEmpty()) {
                return Mono.error(
                    new IllegalStateException("Modified files log directory is not configured."));
              }

              final ReactiveLock lock = getLock(sessionId);
              return lock.withLock(
                  Mono.fromRunnable(
                          () -> {
                            try {
                              final Path sessionDir = Path.of(logsDir).resolve(sessionId);
                              Files.createDirectories(sessionDir);
                              final Path logFile = sessionDir.resolve(sessionId + ".log");

                              final Map<String, String> files = readLogFileSync(logFile);
                              files.put(normalizePath(path, sessionId), PENDING_STATUS);
                              writeLogFileSync(logFile, files);

                              if (log.isInfoEnabled()) {
                                log.info("Logged file path {} for session {}", path, sessionId);
                              }
                            } catch (IOException e) {
                              throw new UncheckedIoException(
                                  "Failed to log file path: " + e.getMessage(), e);
                            }
                          })
                      .subscribeOn(Schedulers.boundedElastic())
                      .then());
            })
        .then();
  }

  /**
   * Get the map of modified files and their statuses for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing map of file paths to statuses
   */
  public Mono<Map<String, String>> getModifiedFiles(final String sessionId) {
    return Mono.fromCallable(
            () -> {
              final String logsDir = configService.getFileLogDir(sessionId);
              if (logsDir == null || logsDir.isEmpty()) {
                return Map.<String, String>of();
              }
              final Path logFile = Path.of(logsDir).resolve(sessionId).resolve(sessionId + ".log");
              try {
                return readLogFileSync(logFile);
              } catch (IOException e) {
                log.warn("Failed to read modified files log for session {}", sessionId, e);
                return Map.<String, String>of();
              }
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  /* default */ Map<String, String> readLogFileSync(final Path logFile) throws IOException {
    if (!Files.exists(logFile)) {
      return new LinkedHashMap<>();
    }
    try (Stream<String> lines = Files.lines(logFile, StandardCharsets.UTF_8)) {
      final Map<String, String> result = new LinkedHashMap<>();
      lines
          .filter(line -> !line.isBlank())
          .forEach(
              line -> {
                try {
                  final LogEntry entry = objectMapper.readValue(line, LogEntry.class);
                  result.put(entry.getPath(), entry.getStatus());
                } catch (JsonProcessingException e) {
                  if (log.isWarnEnabled()) {
                    log.warn("Failed to parse log line: {}", line, e);
                  }
                }
              });
      return result;
    }
  }

  /* default */ void writeLogFileSync(final Path logFile, final Map<String, String> files)
      throws IOException {
    final List<String> lines = new ArrayList<>();
    for (final Map.Entry<String, String> entry : files.entrySet()) {
      try {
        lines.add(objectMapper.writeValueAsString(new LogEntry(entry.getKey(), entry.getValue())));
      } catch (JsonProcessingException e) {
        if (log.isErrorEnabled()) {
          log.error("Failed to serialize log entry for file: {}", entry.getKey(), e);
        }
      }
    }
    Files.write(logFile, lines, StandardCharsets.UTF_8);
  }

  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  /* default */ static class LogEntry {
    private String path;
    private String status;
  }

  private String normalizePath(final String path, final String sessionId) {
    final String root = configService.getProjectPath(sessionId);
    if (root == null || root.isEmpty()) {
      return path;
    }
    return tryNormalize(path, root);
  }

  private String tryNormalize(final String path, final String root) {
    String result = path;
    try {
      final Path rootPath = Path.of(root).toAbsolutePath().normalize();
      final Path filePath = Path.of(path).toAbsolutePath().normalize();
      if (filePath.startsWith(rootPath)) {
        result = rootPath.relativize(filePath).toString();
      }
    } catch (InvalidPathException e) {
      log.warn("Failed to normalize path: {}", path, e);
    }
    return result;
  }

  /**
   * Get the lock for a session and run the given action.
   *
   * @param sessionId the session identifier
   * @param action the action to run
   * @param <T> the result type
   * @return Mono containing the result
   */
  public <T> Mono<T> withLock(final String sessionId, final Mono<T> action) {
    return getLock(sessionId).withLock(action);
  }
}
