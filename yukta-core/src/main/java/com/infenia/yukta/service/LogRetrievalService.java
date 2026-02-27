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
package com.infenia.yukta.service;

import com.infenia.yukta.config.AppConfigService;
import com.infenia.yukta.validation.FileName;
import com.infenia.yukta.validation.SessionId;
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
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Service for retrieving logs from disk. */
@Slf4j
@Service
@Validated
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
  public Mono<List<String>> listLogs(@SessionId final String sessionId) {
    return Mono.zip(
            configService.getResultLogDir(sessionId), configService.getFileLogDir(sessionId))
        .flatMap(
            tuple ->
                Mono.fromCallable(
                    () -> {
                      final List<String> logFiles = new ArrayList<>();
                      final String resultsDir = tuple.getT1();
                      final String fileLogDir = tuple.getT2();

                      addLogsFromDir(logFiles, resultsDir, sessionId);
                      addLogsFromDir(logFiles, fileLogDir, sessionId);

                      return logFiles.stream().distinct().sorted().toList();
                    }))
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
  public Mono<String> getLogContent(
      @SessionId final String sessionId, @FileName final String fileName) {
    return fetchLogContent(sessionId, fileName).subscribeOn(Schedulers.boundedElastic());
  }

  private Mono<String> fetchLogContent(final String sessionId, final String fileName) {
    return Mono.zip(
            configService.getResultLogDir(sessionId), configService.getFileLogDir(sessionId))
        .flatMap(
            tuple ->
                Mono.fromCallable(
                    () -> {
                      final String resultsDir = tuple.getT1();
                      final String fileLogDir = tuple.getT2();

                      Path logFile = findLogFile(resultsDir, sessionId, fileName);
                      if (logFile == null) {
                        logFile = findLogFile(fileLogDir, sessionId, fileName);
                      }

                      if (logFile == null || !Files.exists(logFile)) {
                        throw new IOException("Log file not found: " + fileName);
                      }
                      return Files.readString(logFile, StandardCharsets.UTF_8);
                    }));
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
