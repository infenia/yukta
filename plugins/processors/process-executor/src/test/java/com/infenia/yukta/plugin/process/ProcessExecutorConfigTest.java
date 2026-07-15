// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.CommentRequired", "PMD.UseConcurrentHashMap"})
@NoArgsConstructor
class ProcessExecutorConfigTest {

  @Test
  void compactConstructor_defensivelyCopiesCommand() {
    final List<String> originalCommand = new ArrayList<>();
    originalCommand.add("echo");
    originalCommand.add("hello");

    final ProcessExecutorConfig config =
        ProcessExecutorConfig.builder()
            .command(originalCommand)
            .workingDir(null)
            .timeout(10)
            .env(Map.of())
            .useShell(false)
            .outputFormat(OutputFormat.STRUCTURED)
            .failureMode(FailureMode.ERROR)
            .inputMode(InputMode.NONE)
            .routeByExitCode(false)
            .includeOutput(true)
            .includeInput(false)
            .captureStderr(false)
            .maxOutputLines(0)
            .maxOutputBytes(0)
            .build();

    originalCommand.add("mutated");

    assertThat(config.command()).containsExactly("echo", "hello");
    assertThatThrownBy(() -> config.command().add("attempt"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void compactConstructor_defensivelyCopiesEnv() {
    final Map<String, String> originalEnv = new HashMap<>();
    originalEnv.put("KEY1", "value1");
    originalEnv.put("KEY2", "value2");

    final ProcessExecutorConfig config =
        ProcessExecutorConfig.builder()
            .command(List.of("echo"))
            .workingDir(null)
            .timeout(10)
            .env(originalEnv)
            .useShell(false)
            .outputFormat(OutputFormat.STRUCTURED)
            .failureMode(FailureMode.ERROR)
            .inputMode(InputMode.NONE)
            .routeByExitCode(false)
            .includeOutput(true)
            .includeInput(false)
            .captureStderr(false)
            .maxOutputLines(0)
            .maxOutputBytes(0)
            .build();

    originalEnv.put("KEY3", "mutated");

    assertThat(config.env()).containsOnly(Map.entry("KEY1", "value1"), Map.entry("KEY2", "value2"));
    assertThatThrownBy(() -> config.env().put("KEY3", "attempt"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void compactConstructor_handlesNullCommand() {
    final ProcessExecutorConfig config =
        ProcessExecutorConfig.builder()
            .command(null)
            .workingDir(null)
            .timeout(10)
            .env(null)
            .useShell(false)
            .outputFormat(OutputFormat.STRUCTURED)
            .failureMode(FailureMode.ERROR)
            .inputMode(InputMode.NONE)
            .routeByExitCode(false)
            .includeOutput(true)
            .includeInput(false)
            .captureStderr(false)
            .maxOutputLines(0)
            .maxOutputBytes(0)
            .build();

    assertThat(config.command()).isEmpty();
    assertThat(config.env()).isEmpty();
  }
}
