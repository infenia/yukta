// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** A Java module added to settings.gradle.kts must also be put on this module's classpath. */
class ModuleCoverageTest {

  /**
   * Modules with nothing to check: the checker itself, the app, the other languages, the helper.
   */
  private static final List<String> SKIPPED =
      List.of("architecture", "boot", "cli", "ui", "test-support");

  @Test
  void everyJavaModuleIsAnalysed() throws IOException {
    final String settings = Files.readString(Path.of("..", "settings.gradle.kts"));
    final String build = Files.readString(Path.of("build.gradle.kts"));
    final List<String> modules =
        Pattern.compile("^include\\(\"(.*)\"\\)", Pattern.MULTILINE)
            .matcher(settings)
            .results()
            .map(m -> m.group(1))
            .filter(m -> !SKIPPED.contains(m))
            .toList();
    assertThat(modules).isNotEmpty();
    assertThat(modules).allSatisfy(m -> assertThat(build).contains("project(\":" + m + "\")"));
  }
}
