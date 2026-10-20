// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** Tests for {@link BootstrapRunner}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.TooManyStaticImports"})
class BootstrapRunnerTest {

  private static final String VALID =
      """
      {"sessionId":"s1","name":"n","description":"d","initiator":"i","projectPath":"/tmp",
       "workflows":{"w":{"workflowId":"w","description":"d",
         "nodes":[{"nodeId":"a","type":"CONSTANT_SOURCE","config":{}}],"edges":[]}}}
      """;

  @TempDir /* default */ Path dir;

  private final SessionService service = mock(SessionService.class);
  private final SessionMapper mapper = mock(SessionMapper.class);
  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  private BootstrapRunner runner(final Path folder) {
    when(mapper.configRequestToSessionConfigData(any(ConfigRequest.class)))
        .thenReturn(mock(SessionConfigData.class));
    when(service.applyConfig(any())).thenReturn(Mono.empty());
    return new BootstrapRunner(
        folder.toString(), JsonMapper.builder().build(), validator, mapper, service);
  }

  @Test
  void validFilesAreApplied_inNameOrder_andOthersAreSkipped() throws IOException {
    Files.writeString(dir.resolve("b.json"), VALID);
    Files.writeString(dir.resolve("a.json"), VALID);
    Files.writeString(dir.resolve("broken.json"), "not json");
    Files.writeString(dir.resolve("invalid.json"), "{\"sessionId\":\"x\"}");
    Files.writeString(dir.resolve("notes.txt"), "ignored");

    assertThat(runner(dir).apply()).isEqualTo(2);
  }

  @Test
  void failingFile_isSkipped() throws IOException {
    Files.writeString(dir.resolve("a.json"), VALID);
    final BootstrapRunner runner = runner(dir);
    when(service.applyConfig(any())).thenReturn(Mono.error(new IllegalStateException("no")));

    assertThat(runner.apply()).isZero();
  }

  @Test
  void unreadableFolder_appliesNothing() {
    Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
    assertThat(dir.toFile().setReadable(false)).isTrue();
    try {
      assertThat(runner(dir).apply()).isZero();
    } finally {
      assertThat(dir.toFile().setReadable(true)).isTrue();
    }
  }

  @Test
  void missingFolder_appliesNothing_andRunNeverThrows() {
    final BootstrapRunner runner = runner(dir.resolve("missing"));

    assertThat(runner.apply()).isZero();
    runner.run(null);
    verify(service, never()).applyConfig(any());
  }
}
