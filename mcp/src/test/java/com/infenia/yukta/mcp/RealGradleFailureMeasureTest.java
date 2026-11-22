// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogStoreConfig;
import com.infenia.yukta.logging.impl.memory.InMemoryPluginLogStore;
import com.infenia.yukta.mcp.provider.DiagnosisProvider;
import com.infenia.yukta.mcp.provider.LogProvider;
import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.diagnosis.FailureDigestService;
import com.infenia.yukta.service.diagnosis.GoFailureExtractor;
import com.infenia.yukta.service.diagnosis.GradleFailureExtractor;
import com.infenia.yukta.service.diagnosis.JsFailureExtractor;
import com.infenia.yukta.service.diagnosis.ReportLineExtractor;
import com.infenia.yukta.service.diagnosis.RunLogSearcher;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.testing.Fixtures;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The token saving on a real failure: the Gradle step of a CI run of this repository that failed
 * four tests (1,330 lines, 117 KB), replayed as one node's log. An agent that reads the whole log
 * gets every line; the digest names the four tests and the failed task in a small fraction of it.
 */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class RealGradleFailureMeasureTest {

  private static final String SESSION = "s";
  private static final String RUN = "e";
  private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

  private static int size(final Object answer) {
    return JSON.writeValueAsString(answer).getBytes(StandardCharsets.UTF_8).length;
  }

  @Test
  void digestIsAtLeastTenTimesSmallerThanTheWholeLogAndNamesTheFailures() throws IOException {
    final InMemoryPluginLogStore store = new InMemoryPluginLogStore(new PluginLogStoreConfig());
    final List<String> lines;
    try (InputStream in =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/diagnosis/ci-gradle-failure.txt"))) {
      lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    }
    for (final String line : lines) {
      store
          .write(
              new PluginLogEntry(
                  RUN,
                  SESSION,
                  "build",
                  "PROCESS_EXECUTOR",
                  LogStream.STDOUT,
                  line,
                  line.contains("FAILED") || line.contains("What went wrong")
                      ? LogLevel.ERROR
                      : LogLevel.INFO,
                  Instant.EPOCH,
                  null,
                  null))
          .block();
    }
    final ControlBusGateway controlBus = mock(ControlBusGateway.class);
    when(controlBus.getProgress(SESSION, RUN))
        .thenReturn(
            Fixtures.workflowProgress(
                RUN,
                SESSION,
                "ci",
                TaskStatus.FAILURE,
                List.of(
                    new TaskProgress(
                        "build",
                        "PROCESS_EXECUTOR",
                        TaskStatus.FAILURE,
                        Instant.EPOCH,
                        null,
                        Map.of("error", "exit 1"))),
                Instant.EPOCH,
                Instant.EPOCH,
                null));
    final TaskTrackerService tracker = mock(TaskTrackerService.class);
    when(tracker.getTree(RUN)).thenReturn(List.of());
    final DiagnosisProvider diagnosis =
        new DiagnosisProvider(
            controlBus,
            new RunLogSearcher(store, tracker),
            new FailureDigestService(
                store,
                tracker,
                List.of(
                    new ReportLineExtractor(),
                    new GradleFailureExtractor(),
                    new GoFailureExtractor(),
                    new JsFailureExtractor())),
            List.of(),
            2_000);
    final LogProvider logs = new LogProvider(store, controlBus, List.of(), 2_000, 200_000, 16_000);

    final Object digest = diagnosis.getFailureDigest(SESSION, RUN).block();
    final Object whole = logs.getExecutionLogs(SESSION, RUN, 5_000, null).block();
    final Object lastDefault = logs.getExecutionLogs(SESSION, RUN, null, null).block();
    final int digestSize = size(digest);
    final int wholeSize = size(whole);

    final JsonNode findings = JSON.valueToTree(digest).path("digest").path("findings");
    assertThat(findings.toString())
        .contains(
            "go_findsTestsPackagesAndCompileErrors()",
            "gradle_findsTestsCompileErrorsLintAndTasks()",
            "js_findsVitestJestTscAndEslint()",
            "gradle_keepsCauseThatIsNotTask()",
            ":core:test");
    assertThat(wholeSize)
        .as(
            "digest %d bytes vs whole log %d bytes (%.0fx)",
            digestSize, wholeSize, (double) wholeSize / digestSize)
        .isGreaterThanOrEqualTo(10 * digestSize);
    assertThat(size(lastDefault)).isLessThan(wholeSize);
    assertThat(digestSize).isLessThan(6 * 1024);
    // the numbers, for the record (build/ is not committed)
    Files.writeString(
        Path.of("build", "real-gradle-failure-measure.txt"),
        "lines=%d digest=%d wholeLog=%d defaultLog=%d ratio=%.1f%n"
            .formatted(
                lines.size(),
                digestSize,
                wholeSize,
                size(lastDefault),
                (double) wholeSize / digestSize));
  }
}
