// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Console terminal plugin test. */
@NoArgsConstructor
class ConsoleTerminalPluginTest {

  @Test
  void testConsume() {
    final ConsoleTerminalPlugin plugin = new ConsoleTerminalPlugin();
    final Message<?> message = DefaultMessage.create(UUID.randomUUID(), Map.of("key", "value"));

    StepVerifier.create(plugin.consume(Flux.just(message), Map.of())).verifyComplete();
  }

  @Test
  void testGetType() {
    assertEquals("CONSOLE_TERMINAL", new ConsoleTerminalPlugin().getType(), "Test get type");
  }

  @Test
  void testMetadata() {
    final ConsoleTerminalPlugin plugin = new ConsoleTerminalPlugin();
    assertEquals(
        "Logs message payloads to the console/logger.",
        plugin.describe().description(),
        "Test metadata");
    assertEquals(
        "Consumes messages and prints their payload to the application logs. No configuration"
            + " required.",
        plugin.describe().usagePattern(),
        "Test metadata (2)");
    org.junit.jupiter.api.Assertions.assertTrue(
        plugin.describe().uiDesign().isPresent(), "Test metadata (3)");
    assertEquals(140, plugin.describe().uiDesign().get().width(), "Test metadata (4)");
    assertEquals(80, plugin.describe().uiDesign().get().height(), "Test metadata (5)");
    org.junit.jupiter.api.Assertions.assertTrue(
        plugin.describe().uiDesign().get().html().contains("terminal"), "Test metadata (6)");
  }

  @Test
  void testLifecycle() {
    final ConsoleTerminalPlugin plugin = new ConsoleTerminalPlugin();
    StepVerifier.create(plugin.validateConfig(Map.of())).verifyComplete();
    StepVerifier.create(plugin.initialize(Map.of())).verifyComplete();
  }
}
