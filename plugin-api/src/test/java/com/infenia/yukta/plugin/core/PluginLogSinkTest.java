// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressWarnings({
  "PMD.AtLeastOneConstructor",
  "PMD.CommentRequired",
  "PMD.UnitTestAssertionsShouldIncludeMessage"
})
class PluginLogSinkTest {

  @Test
  void noopSinkDropsEveryLine() {
    assertDoesNotThrow(() -> PluginLogSink.NOOP.line("stdout", "ignored"));
  }

  @Test
  void sinkKeyIsStableForPluginsThatLookItUp() {
    final List<String> seen = new ArrayList<>();
    final PluginLogSink sink = (stream, line) -> seen.add(stream + ":" + line);

    sink.line("stderr", "boom");

    assertEquals(List.of("stderr:boom"), seen);
    assertEquals("yukta.log-sink", WorkflowContext.LOG_SINK_KEY);
  }
}
