// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.dto.request.ConfigRequest;
import com.infenia.yukta.mapper.SessionMapper;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.service.session.SessionService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** Tests for {@link BootstrapRunner}. */
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyStaticImports")
class BootstrapRunnerTest {

  private static final String VALID =
      """
      {"sessionId":"s1","name":"n","description":"d","initiator":"i","projectPath":"/tmp",
       "workflows":{"w":{"workflowId":"w","description":"d",
         "nodes":[{"nodeId":"a","type":"CONSTANT_SOURCE","config":{}}],"edges":[]}}}
      """;

  private static final String A_JSON = "a.json";
  private static final Duration INTERVAL = Duration.ofSeconds(5);

  @TempDir /* default */ Path dir;

  private final SessionService service = mock(SessionService.class);
  private final SessionMapper mapper = mock(SessionMapper.class);
  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  private BootstrapRunner runner(final Path folder, final boolean watch, final Duration interval) {
    when(mapper.configRequestToSessionConfigData(any(ConfigRequest.class)))
        .thenReturn(mock(SessionConfigData.class));
    when(service.applyConfig(any())).thenReturn(Mono.empty());
    return new BootstrapRunner(
        folder.toString(),
        JsonMapper.builder().build(),
        validator,
        mapper,
        service,
        watch,
        interval);
  }

  @Test
  void validFilesAreApplied_inNameOrder_andOthersAreSkipped() throws IOException {
    Files.writeString(dir.resolve("b.json"), VALID);
    Files.writeString(dir.resolve(A_JSON), VALID);
    Files.writeString(dir.resolve("broken.json"), "not json");
    Files.writeString(dir.resolve("invalid.json"), "{\"sessionId\":\"x\"}");
    Files.writeString(dir.resolve("notes.txt"), "ignored");

    assertThat(runner(dir, false, INTERVAL).apply()).isEqualTo(2);
  }

  @Test
  void failingFile_isSkipped() throws IOException {
    Files.writeString(dir.resolve(A_JSON), VALID);
    final BootstrapRunner runner = runner(dir, false, INTERVAL);
    when(service.applyConfig(any())).thenReturn(Mono.error(new IllegalStateException("no")));

    assertThat(runner.apply()).isZero();
  }

  @Test
  void unreadableFolder_appliesNothing() {
    Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
    assertThat(dir.toFile().setReadable(false)).isTrue();
    try {
      assertThat(runner(dir, false, INTERVAL).apply()).isZero();
    } finally {
      assertThat(dir.toFile().setReadable(true)).isTrue();
    }
  }

  @Test
  void missingFolder_appliesNothing_andRunNeverThrows() {
    final BootstrapRunner runner = runner(dir.resolve("missing"), false, INTERVAL);

    assertThat(runner.apply()).isZero();
    runner.run(null);
    verify(service, never()).applyConfig(any());
  }

  @Test
  void apply_skipsUnchangedFiles_andReappliesOneThatChanged() throws IOException {
    final Path file = dir.resolve(A_JSON);
    Files.writeString(file, VALID);
    final BootstrapRunner runner = runner(dir, false, INTERVAL);

    assertThat(runner.apply()).isEqualTo(1);
    assertThat(runner.apply()).isZero();

    Files.writeString(file, VALID);
    Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
    assertThat(runner.apply()).isEqualTo(1);
  }

  @Test
  void apply_picksUpFileAddedLater_andRetriesBrokenOneOnlyWhenItChanges() throws IOException {
    final BootstrapRunner runner = runner(dir, false, INTERVAL);
    assertThat(runner.apply()).isZero();

    final Path file = dir.resolve("late.json");
    Files.writeString(file, "not json");
    assertThat(runner.apply()).isZero();
    assertThat(runner.apply()).isZero();

    Files.writeString(file, VALID);
    Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
    assertThat(runner.apply()).isEqualTo(1);
  }

  @Test
  void unreadableFile_isNotAppliedAndDoesNotStopOthers() throws IOException {
    Files.writeString(dir.resolve(A_JSON), VALID);
    Files.createSymbolicLink(dir.resolve("dangling.json"), dir.resolve("missing.txt"));

    assertThat(runner(dir, false, INTERVAL).apply()).isEqualTo(1);
  }

  @Test
  void watch_reappliesChangedFile_untilStopped() throws IOException {
    final Path file = dir.resolve(A_JSON);
    Files.writeString(file, VALID);
    final BootstrapRunner runner = runner(dir, true, Duration.ofMillis(20));

    runner.run(null);
    verify(service, times(1)).applyConfig(any());

    Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
    verify(service, timeout(5_000).times(2)).applyConfig(any());

    runner.stop();
    Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 120_000));
    verify(service, after(200).times(2)).applyConfig(any());
  }

  @Test
  void stop_beforeRun_isNoOp() {
    runner(dir, false, INTERVAL).stop();

    verify(service, never()).applyConfig(any());
  }
}
