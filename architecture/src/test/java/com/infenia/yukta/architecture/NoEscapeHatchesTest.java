// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Rules are strict by policy: a violation is fixed, not frozen or ignored. This keeps the escape
 * hatches out of the architecture modules' sources.
 */
class NoEscapeHatchesTest {

  /** Built from pieces so this file does not trip over itself. */
  private static final List<String> FORBIDDEN =
      List.of(
          "Freezing" + "ArchRule",
          "@Arch" + "Ignore",
          "archunit" + "_store",
          "freeze" + ".store",
          "because(\"\")");

  @Test
  void noFreezingIgnoringOrEmptyReasons() throws IOException {
    for (final String dir : List.of("src", "../plus/plus-architecture/src")) {
      final Path root = Path.of(dir);
      if (!Files.isDirectory(root)) {
        continue;
      }
      try (Stream<Path> files = Files.walk(root)) {
        for (final Path file :
            files
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".properties"))
                .filter(p -> !p.endsWith("NoEscapeHatchesTest.java"))
                .toList()) {
          final String text = Files.readString(file);
          assertThat(FORBIDDEN).as(file.toString()).noneMatch(text::contains);
        }
      }
    }
  }
}
