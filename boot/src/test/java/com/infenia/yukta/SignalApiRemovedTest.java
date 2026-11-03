// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/**
 * The signal API was replaced by input requests (ADR 0001, D2): {@code SignalWaits}, {@code
 * SignalNodeCommand}, the MCP tools {@code signal_node} and {@code list_pending_approvals} and the
 * REST paths for signals and approvals must not come back in product code. Tests, docs and design
 * records may still name them.
 */
@NoArgsConstructor
class SignalApiRemovedTest {

  private static final List<String> GONE =
      List.of(
          "SignalWaits",
          "SignalNodeCommand",
          "signal_node",
          "list_pending_approvals",
          "{nodeId}/signal",
          "}/approvals",
          "\"/approvals");

  /** Directories that hold product code, relative to the repository root. */
  private static final List<String> PRODUCT = List.of("src/main", "internal", "cmd");

  private static boolean isProductFile(final Path path) {
    final String name = path.toString().replace('\\', '/');
    final boolean source =
        name.endsWith(".java")
            || name.endsWith(".go")
            || name.endsWith(".ts")
            || name.endsWith(".tsx");
    return source
        && !name.contains("/build/")
        && !name.contains("/node_modules/")
        && !name.contains("/.claude/")
        && !name.contains("/src/test/")
        && !name.endsWith("_test.go")
        && !name.contains("/__tests__/")
        && !name.endsWith(".test.ts")
        && !name.endsWith(".test.tsx")
        && !name.endsWith("api.generated.ts")
        && PRODUCT.stream().anyMatch(name::contains);
  }

  @Test
  void noProductCodeNamesTheRemovedSignalApi() throws IOException {
    final Path root = Path.of("..").toAbsolutePath().normalize();
    final List<String> hits;
    try (Stream<Path> files = Files.walk(root)) {
      hits =
          files
              .filter(Files::isRegularFile)
              .filter(SignalApiRemovedTest::isProductFile)
              .flatMap(
                  file -> {
                    try {
                      final String text = Files.readString(file);
                      return GONE.stream()
                          .filter(text::contains)
                          .map(word -> root.relativize(file) + " names " + word);
                    } catch (final IOException unreadable) {
                      return Stream.empty();
                    }
                  })
              .toList();
    }

    assertThat(hits).isEmpty();
  }
}
