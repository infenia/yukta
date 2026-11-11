// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Pins the contract names, and checks that the public page lists every one. */
@NoArgsConstructor
class SupervisorContractTest {

  @Test
  void versionIsOne() {
    assertThat(SupervisorContract.VERSION).isEqualTo(1);
  }

  @Test
  void operationSetIsPinned() {
    assertThat(SupervisorContract.READ_OPERATIONS)
        .containsExactly(
            "self",
            "events",
            "snapshot",
            "executions",
            "execution",
            "workflow",
            "inputs",
            "logs",
            "scheduler");
    assertThat(SupervisorContract.ACT_OPERATIONS)
        .containsExactly(
            "run.start",
            "run.pause",
            "run.resume",
            "run.resumeHeld",
            "run.stop",
            "run.restart",
            "run.restartFromNode",
            "run.priority",
            "node.pause",
            "node.resume",
            "node.stop",
            "node.skip",
            "node.retry",
            "node.stepMode",
            "node.breakpoint",
            "input.answer",
            "input.escalate",
            "server.setLimits",
            "server.drain",
            "notify",
            "delegate");
    assertThat(SupervisorContract.operations().keySet())
        .containsExactly("read", "act", "signals", "directives", "causes", "outcomes");
  }

  @Test
  void pageListsEveryName() throws Exception {
    final Path page = findPage();
    if (page == null) {
      return; // built outside the repository checkout
    }
    final String text = Files.readString(page);
    final List<String> names =
        SupervisorContract.operations().values().stream().flatMap(List::stream).toList();
    assertThat(names)
        .allSatisfy(n -> assertThat(text).contains(n.endsWith(":") ? "`" + n : "`" + n + "`"));
  }

  private static Path findPage() {
    return Stream.iterate(Path.of("").toAbsolutePath(), Path::getParent)
        .limit(5)
        .filter(p -> p != null)
        .map(p -> p.resolve("docs/reference/supervisors/contract.mdx"))
        .filter(Files::exists)
        .findFirst()
        .orElse(null);
  }
}
