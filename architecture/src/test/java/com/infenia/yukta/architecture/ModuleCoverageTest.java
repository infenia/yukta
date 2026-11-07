// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every Java module is in settings.gradle.kts and is put on this module's classpath. */
class ModuleCoverageTest {

  /**
   * Modules with nothing to check: the checker itself, the app, the other languages, the helper.
   * The plus* modules may only be used by boot (licensing), so plus:plus-architecture checks them.
   */
  private static final List<String> SKIPPED =
      List.of("architecture", "boot", "cli", "ui", "test-support");

  private static Set<String> included() throws IOException {
    return Pattern.compile("^include\\(\"(.*)\"\\)", Pattern.MULTILINE)
        .matcher(Files.readString(Path.of("..", "settings.gradle.kts")))
        .results()
        .map(m -> m.group(1))
        .collect(Collectors.toSet());
  }

  @Test
  void everyIncludedJavaModuleIsAnalysed() throws IOException {
    final String build = Files.readString(Path.of("build.gradle.kts"));
    final List<String> modules =
        included().stream().filter(m -> !SKIPPED.contains(m) && !m.startsWith("plus")).toList();
    assertThat(modules).isNotEmpty();
    assertThat(modules).allSatisfy(m -> assertThat(build).contains("project(\":" + m + "\")"));
  }

  @Test
  void everyJavaSourceDirectoryBelongsToAnIncludedModule() throws IOException {
    final Path root = Path.of("..");
    final Set<String> included = included();
    try (Stream<Path> dirs =
        Files.find(root, 6, (p, a) -> a.isDirectory() && p.endsWith("src/main/java"))) {
      final List<String> modules =
          dirs.map(root::relativize)
              .filter(rel -> rel.getNameCount() > 3)
              .map(rel -> rel.subpath(0, rel.getNameCount() - 3).toString())
              .filter(m -> !m.matches(".*(^|/)(build|node_modules|\\.claude|examples|docs)(/.*|$)"))
              .map(m -> m.replace('/', ':'))
              .toList();
      assertThat(modules).isNotEmpty();
      assertThat(included).containsAll(modules);
    }
  }
}
