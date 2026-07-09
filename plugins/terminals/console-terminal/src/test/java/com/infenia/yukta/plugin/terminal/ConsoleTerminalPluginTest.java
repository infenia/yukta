// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ConsoleTerminalPluginTest {

  @Test
  void testConsume() {
    ConsoleTerminalPlugin plugin = new ConsoleTerminalPlugin();
    Message<?> message = DefaultMessage.create(UUID.randomUUID(), Map.of("key", "value"));

    StepVerifier.create(plugin.consume(Flux.just(message), Map.of())).verifyComplete();
  }

  @Test
  void testGetType() {
    assertEquals("CONSOLE_TERMINAL", new ConsoleTerminalPlugin().getType());
  }

  @Test
  void testMetadata() {
    ConsoleTerminalPlugin plugin = new ConsoleTerminalPlugin();
    assertEquals("Logs message payloads to the console/logger.", plugin.getDescription());
    assertEquals(
        "Consumes messages and prints their payload to the application logs. No configuration"
            + " required.",
        plugin.getUsagePattern());
    org.junit.jupiter.api.Assertions.assertTrue(plugin.getUiDesign().isPresent());
    assertEquals(140, plugin.getUiDesign().get().width());
    assertEquals(80, plugin.getUiDesign().get().height());
    org.junit.jupiter.api.Assertions.assertTrue(
        plugin.getUiDesign().get().html().contains("terminal"));
  }

  @Test
  void testLifecycle() {
    ConsoleTerminalPlugin plugin = new ConsoleTerminalPlugin();
    StepVerifier.create(plugin.validateConfig(Map.of())).verifyComplete();
    StepVerifier.create(plugin.initialize(Map.of())).verifyComplete();
  }
}
