// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for ProcessExecutionSpec ensuring proper defaults and command validation. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.CommentRequired", // Constants are self-documenting in test context
  "PMD.ShortVariable" // Single-letter constants intentional for test data
})
class ProcessExecutionSpecTest {

  private static final String ECHO = "echo";
  private static final String HI = "hi";
  private static final String TMP = "/tmp";
  private static final String K = "K";
  private static final String V = "V";
  private static final String INPUT = "input";
  private static final String K2 = "K2";
  private static final String V2 = "V2";

  @Test
  void builder_minimal_appliesDocumentedDefaults() {
    final ProcessExecutionSpec spec = ProcessExecutionSpec.builder().build();

    assertThat(spec.command()).isEmpty();
    assertThat(spec.env()).isEmpty();
    assertThat(spec.timeoutSeconds()).isEqualTo(ProcessExecutionSpec.DEFAULT_TIMEOUT_SECONDS);
    assertThat(spec.maxOutputLines()).isEqualTo(ProcessExecutionSpec.UNLIMITED);
    assertThat(spec.maxOutputBytes()).isEqualTo(ProcessExecutionSpec.UNLIMITED);
    assertThat(spec.workingDir()).isNull();
    assertThat(spec.stdin()).isNull();
    assertThat(spec.useShell()).isFalse();
    assertThat(spec.captureStderr()).isFalse();
  }

  @Test
  void builder_explicitValues_retained() {
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder()
            .command(List.of(ECHO, HI))
            .workingDir(TMP)
            .timeoutSeconds(10L)
            .env(Map.of(K, V))
            .useShell(true)
            .stdin(INPUT)
            .captureStderr(true)
            .maxOutputLines(5)
            .maxOutputBytes(1024L)
            .build();

    assertThat(spec.command()).containsExactly(ECHO, HI);
    assertThat(spec.workingDir()).isEqualTo(TMP);
    assertThat(spec.timeoutSeconds()).isEqualTo(10L);
    assertThat(spec.env()).containsEntry(K, V);
    assertThat(spec.useShell()).isTrue();
    assertThat(spec.stdin()).isEqualTo(INPUT);
    assertThat(spec.captureStderr()).isTrue();
    assertThat(spec.maxOutputLines()).isEqualTo(5);
    assertThat(spec.maxOutputBytes()).isEqualTo(1024L);
  }

  @Test
  void builder_negativeTimeout_fallsBackToDefault() {
    final ProcessExecutionSpec spec = ProcessExecutionSpec.builder().timeoutSeconds(-5L).build();

    assertThat(spec.timeoutSeconds()).isEqualTo(ProcessExecutionSpec.DEFAULT_TIMEOUT_SECONDS);
  }

  @Test
  void builder_negativeCaps_normalizedToUnlimited() {
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder().maxOutputLines(-1).maxOutputBytes(-1L).build();

    assertThat(spec.maxOutputLines()).isEqualTo(ProcessExecutionSpec.UNLIMITED);
    assertThat(spec.maxOutputBytes()).isEqualTo(ProcessExecutionSpec.UNLIMITED);
  }

  @Test
  void constructor_defensivelyCopiesCollections() {
    final List<String> command = new ArrayList<>(List.of(ECHO));
    final Map<String, String> env = new ConcurrentHashMap<>(Map.of(K, V));

    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder().command(command).env(env).build();
    command.add("mutated");
    env.put(K2, V2);

    assertThat(spec.command()).containsExactly(ECHO);
    assertThat(spec.env()).containsOnlyKeys(K);
  }

  @Test
  void builder_nullCommandAndEnv_normalizedToEmpty() {
    final ProcessExecutionSpec spec =
        ProcessExecutionSpec.builder().command(null).env(null).build();

    assertThat(spec.command()).isEmpty();
    assertThat(spec.env()).isEmpty();
  }
}
