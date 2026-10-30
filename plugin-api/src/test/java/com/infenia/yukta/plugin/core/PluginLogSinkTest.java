// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestAssertionsShouldIncludeMessage")
class PluginLogSinkTest {

  @Test
  void noopSinkDropsEveryLine() {
    assertDoesNotThrow(() -> PluginLogSink.NOOP.line("stdout", "ignored"));
  }

  @Test
  void sinkKeyIsStableForPluginsThatLookItUp() {
    final List<String> seen = new ArrayList<>();
    final PluginLogSink sink = (stream, line, level) -> seen.add(stream + ":" + line + ":" + level);

    sink.line("stderr", "boom");
    sink.line("stdout", "careful", LogLevel.WARN);

    assertEquals(List.of("stderr:boom:null", "stdout:careful:WARN"), seen);
    assertEquals("yukta.log-sink", WorkflowContext.LOG_SINK_KEY);
  }
}
