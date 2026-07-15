// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for ProcessExecutionResult ensuring proper handling of output lines and normalization. */
@NoArgsConstructor
class ProcessExecutionResultTest {

  @Test
  void builder_nullLineLists_normalizedToEmpty() {
    final ProcessExecutionResult result =
        ProcessExecutionResult.of(0, null, null, 0L, false, false);

    assertThat(result.stdoutLines()).isEmpty();
    assertThat(result.stderrLines()).isEmpty();
    assertThat(result.stdout()).isEmpty();
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void builder_lineLists_defensivelyCopied() {
    final List<String> stdout = new ArrayList<>(List.of("out"));
    final List<String> stderr = new ArrayList<>(List.of("err"));

    final ProcessExecutionResult result =
        ProcessExecutionResult.of(0, stdout, stderr, 0L, false, false);
    stdout.add("mutated");
    stderr.add("mutated");

    assertThat(result.stdoutLines()).containsExactly("out");
    assertThat(result.stderrLines()).containsExactly("err");
  }

  @Test
  void isSuccess_zeroExitWithinTimeout_true() {
    final ProcessExecutionResult result =
        ProcessExecutionResult.of(0, null, null, 0L, false, false);

    assertThat(result.isSuccess()).isTrue();
  }

  @Test
  void isSuccess_nonZeroExit_false() {
    final ProcessExecutionResult result =
        ProcessExecutionResult.of(7, null, null, 0L, false, false);

    assertThat(result.isSuccess()).isFalse();
  }

  @Test
  void isSuccess_timedOut_false() {
    final ProcessExecutionResult result =
        ProcessExecutionResult.of(
            ProcessExecutionResult.TIMEOUT_EXIT_CODE, null, null, 0L, true, false);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.exitCode()).isEqualTo(-1);
  }

  @Test
  void stdoutAndStderr_joinLinesWithNewlines() {
    final ProcessExecutionResult result =
        ProcessExecutionResult.of(0, List.of("a", "b"), List.of("x", "y"), 12L, false, true);

    assertThat(result.stdout()).isEqualTo("a\nb");
    assertThat(result.stderr()).isEqualTo("x\ny");
    assertThat(result.durationMillis()).isEqualTo(12L);
    assertThat(result.outputTruncated()).isTrue();
  }
}
