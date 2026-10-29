// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import java.util.ArrayList;
import java.util.List;
import lombok.NoArgsConstructor;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/** Records the ERROR events of every Logback logger. */
@NoArgsConstructor
final class LogbackProbe implements Probe {

  private final EventCollector appender = new EventCollector();
  private final List<Logger> attached = new ArrayList<>();

  /** Whether Logback is the SLF4J backend of this JVM. */
  /* default */ static boolean available() {
    final ILoggerFactory factory = LoggerFactory.getILoggerFactory();
    return factory instanceof LoggerContext;
  }

  @Override
  public void start() {
    final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    appender.events.clear();
    appender.setContext(context);
    appender.start();
    // A logger that is not additive keeps its events away from the root, so it gets the appender
    // too.
    for (final Logger logger : context.getLoggerList()) {
      if (Logger.ROOT_LOGGER_NAME.equals(logger.getName()) || !logger.isAdditive()) {
        logger.addAppender(appender);
        attached.add(logger);
      }
    }
  }

  @Override
  public List<String> snapshot() {
    return errors(List.copyOf(appender.events));
  }

  @Override
  public List<String> stop() {
    attached.forEach(logger -> logger.detachAppender(appender));
    attached.clear();
    appender.stop();
    return errors(List.copyOf(appender.events));
  }

  private static List<String> errors(final List<ILoggingEvent> events) {
    final List<String> seen = new ArrayList<>();
    for (final ILoggingEvent event : events) {
      if (event.getLevel().isGreaterOrEqual(Level.ERROR)) {
        final IThrowableProxy thrown = event.getThrowableProxy();
        seen.add(
            "ERROR "
                + event.getLoggerName()
                + " - "
                + event.getFormattedMessage()
                + (thrown == null
                    ? ""
                    : " [" + thrown.getClassName() + ": " + thrown.getMessage() + "]"));
      }
    }
    return seen;
  }
}
