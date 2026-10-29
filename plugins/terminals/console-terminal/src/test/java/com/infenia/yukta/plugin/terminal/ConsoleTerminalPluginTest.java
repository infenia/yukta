// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
        "Logs message payloads to the console/logger (at DEBUG).",
        plugin.describe().description(),
        "Test metadata");
    assertEquals(
        "Consumes messages and prints their payload to the application logs at DEBUG level; INFO"
            + " shows only a message count. No configuration required.",
        plugin.describe().usagePattern(),
        "Test metadata (2)");
    org.junit.jupiter.api.Assertions.assertTrue(
        plugin.describe().uiDesign().isPresent(), "Test metadata (3)");
    assertEquals(140, plugin.describe().uiDesign().get().width(), "Test metadata (4)");
    assertEquals(80, plugin.describe().uiDesign().get().height(), "Test metadata (5)");
    org.junit.jupiter.api.Assertions.assertTrue(
        plugin.describe().uiDesign().get().icon().contains("terminal"), "Test metadata (6)");
  }

  @Test
  void testLifecycle() {
    final ConsoleTerminalPlugin plugin = new ConsoleTerminalPlugin();
    StepVerifier.create(plugin.validateConfig(Map.of())).verifyComplete();
    StepVerifier.create(plugin.initialize(Map.of())).verifyComplete();
  }

  @Test
  void payloadsAreNotLoggedAtInfo() {
    final Logger logger = (Logger) LoggerFactory.getLogger(ConsoleTerminalPlugin.class);
    final Level before = logger.getLevel();
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.INFO);
    try {
      final Message<?> message =
          DefaultMessage.create(UUID.randomUUID(), Map.of("secret", "s3cr3t-value"));
      StepVerifier.create(new ConsoleTerminalPlugin().consume(Flux.just(message), Map.of()))
          .verifyComplete();
      final List<ILoggingEvent> infos =
          appender.list.stream().filter(e -> e.getLevel() == Level.INFO).toList();
      assertEquals(1, infos.size(), "One INFO summary line, not one per message");
      assertEquals(
          false,
          infos.get(0).getFormattedMessage().contains("s3cr3t-value"),
          "The INFO line must not carry the payload");
      assertEquals(
          true, infos.get(0).getFormattedMessage().contains("1"), "The summary counts messages");
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(before);
    }
  }

  @Test
  void payloadsAreLoggedAtDebug() {
    final Logger logger = (Logger) LoggerFactory.getLogger(ConsoleTerminalPlugin.class);
    final Level before = logger.getLevel();
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.DEBUG);
    try {
      final Message<?> message = DefaultMessage.create(UUID.randomUUID(), Map.of("k", "visible"));
      StepVerifier.create(new ConsoleTerminalPlugin().consume(Flux.just(message), Map.of()))
          .verifyComplete();
      assertEquals(
          true,
          appender.list.stream()
              .anyMatch(
                  e -> e.getLevel() == Level.DEBUG && e.getFormattedMessage().contains("visible")),
          "The payload is logged at DEBUG");
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(before);
    }
  }
}
