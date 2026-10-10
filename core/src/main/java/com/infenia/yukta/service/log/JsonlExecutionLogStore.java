// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Execution log kept as one JSON object per line in {@code <dir>/executions.jsonl}.
 *
 * <p>A crash can leave a half-written last line; reading skips lines it cannot parse. Replacing the
 * log writes a temporary file and moves it into place.
 */
@Slf4j
@Component
@ConditionalOnExpression("!'${yukta.execution-log.dir:}'.isEmpty()")
@SuppressWarnings({
  "PMD.AvoidSynchronizedAtMethodLevel",
  "PMD.AvoidCatchingGenericException",
  "PMD.OnlyOneReturn"
})
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class JsonlExecutionLogStore implements ExecutionLogStore {

  /** File name inside the log directory. */
  private static final String FILE_NAME = "executions.jsonl";

  /** The log directory. */
  private final Path dir;

  /** The log file. */
  private final Path file;

  /** Serialises events. */
  private final ObjectMapper mapper;

  /**
   * Creates a store writing under the given directory.
   *
   * @param dir the log directory, created on first write
   * @param mapper serialises events
   */
  public JsonlExecutionLogStore(
      @Value("${yukta.execution-log.dir}") final String dir, final ObjectMapper mapper) {
    this.dir = Path.of(dir);
    this.file = this.dir.resolve(FILE_NAME);
    this.mapper = mapper;
  }

  @Override
  public synchronized void append(final ExecutionLogEvent event) {
    try {
      Files.createDirectories(dir);
      Files.writeString(
          file,
          mapper.writeValueAsString(event) + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (final IOException | RuntimeException e) {
      log.atError()
          .addKeyValue("executionId", event.executionId())
          .setCause(e)
          .log("Could not append to the execution log");
    }
  }

  @Override
  public synchronized List<ExecutionLogEvent> readAll() {
    final List<ExecutionLogEvent> events = new ArrayList<>();
    if (!Files.exists(file)) {
      return events;
    }
    try {
      for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        parse(line, events);
      }
    } catch (final IOException e) {
      log.atError().setCause(e).log("Could not read the execution log");
    }
    return events;
  }

  private void parse(final String line, final List<ExecutionLogEvent> events) {
    if (line.isBlank()) {
      return;
    }
    try {
      events.add(mapper.readValue(line, ExecutionLogEvent.class));
    } catch (final RuntimeException e) {
      log.atWarn().setCause(e).log("Skipping an unreadable execution log line");
    }
  }

  @Override
  public synchronized void replaceAll(final List<ExecutionLogEvent> events) {
    try {
      Files.createDirectories(dir);
      final Path tmp = Files.createTempFile(dir, "executions", ".tmp");
      try {
        final StringBuilder content = new StringBuilder();
        for (final ExecutionLogEvent event : events) {
          content.append(mapper.writeValueAsString(event)).append('\n');
        }
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(tmp);
      }
    } catch (final IOException | RuntimeException e) {
      log.atError().setCause(e).log("Could not rewrite the execution log");
    }
  }
}
