package com.infenia.jagratha.service;

import com.infenia.jagratha.config.AppConfigService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Service for retrieving logs from disk. */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("PMD.OnlyOneReturn")
public class LogRetrievalService {

  private final AppConfigService configService;

  /**
   * List all log files for a given session.
   *
   * @param sessionId the session identifier
   * @return Mono containing a list of log filenames
   */
  public Mono<List<String>> listLogs(final String sessionId) {
    return Mono.fromCallable(
            () -> {
              final List<String> logFiles = new ArrayList<>();
              final String resultsDir = configService.getResultLogDir(sessionId);
              final String fileLogDir = configService.getFileLogDir(sessionId);

              addLogsFromDir(logFiles, resultsDir, sessionId);
              addLogsFromDir(logFiles, fileLogDir, sessionId);

              return logFiles.stream().distinct().sorted().toList();
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  private void addLogsFromDir(
      final List<String> logFiles, final String baseDir, final String sessionId) {
    if (baseDir == null || baseDir.isEmpty()) {
      return;
    }
    try {
      final Path sessionDir = Path.of(baseDir).resolve(sessionId);
      if (Files.exists(sessionDir) && Files.isDirectory(sessionDir)) {
        try (Stream<Path> files = Files.list(sessionDir)) {
          files
              .filter(Files::isRegularFile)
              .map(path -> path.getFileName().toString())
              .forEach(logFiles::add);
        }
      }
    } catch (IOException e) {
      log.warn("Failed to list logs from directory: {}", baseDir, e);
    }
  }

  /**
   * Get the content of a specific log file.
   *
   * @param sessionId the session identifier
   * @param fileName the log filename
   * @return Mono containing the log content
   */
  public Mono<String> getLogContent(final String sessionId, final String fileName) {
    return Mono.fromCallable(() -> fetchLogContent(sessionId, fileName))
        .subscribeOn(Schedulers.boundedElastic());
  }

  private String fetchLogContent(final String sessionId, final String fileName) throws IOException {
    final String resultsDir = configService.getResultLogDir(sessionId);
    final String fileLogDir = configService.getFileLogDir(sessionId);

    Path logFile = findLogFile(resultsDir, sessionId, fileName);
    if (logFile == null) {
      logFile = findLogFile(fileLogDir, sessionId, fileName);
    }

    if (logFile == null || !Files.exists(logFile)) {
      throw new IOException("Log file not found: " + fileName);
    }
    return Files.readString(logFile, StandardCharsets.UTF_8);
  }

  private Path findLogFile(final String baseDir, final String sessionId, final String fileName) {
    if (baseDir != null && !baseDir.isEmpty()) {
      final Path path = Path.of(baseDir).resolve(sessionId).resolve(fileName);
      if (Files.exists(path)) {
        return path;
      }
    }
    return null;
  }
}
