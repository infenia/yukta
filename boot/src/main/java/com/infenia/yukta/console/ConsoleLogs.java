// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.ConsoleAppender;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * In console mode the logs go to a ring buffer shown in the log pane, not to stdout (which the
 * dashboard owns). {@link #install()} swaps the stdout appender for the ring; {@link #restore()}
 * puts it back.
 */
@SuppressWarnings({
  "PMD.AvoidSynchronizedStatement",
  "PMD.NullAssignment",
  "PMD.FieldDeclarationsShouldBeAtStartOfClass"
})
public final class ConsoleLogs extends AppenderBase<ILoggingEvent> {

  /** Most lines kept; the oldest are dropped first. */
  /* default */ static final int CAPACITY = 5000;

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

  /** One buffered log line. */
  /* default */ record Line(Level level, String text) {}

  private static final Object LOCK = new Object();
  private static ConsoleLogs instance;
  private static ConsoleAppender<ILoggingEvent> stdout;

  private final Deque<Line> lines = new ArrayDeque<>();

  /** Starts routing the root logger to the ring (idempotent). */
  @SuppressFBWarnings("MS_EXPOSE_REP")
  public static ConsoleLogs install() {
    synchronized (LOCK) {
      if (instance == null) {
        final LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        final Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        instance = new ConsoleLogs();
        instance.setContext(ctx);
        instance.setName("yukta-console-ring");
        instance.start();
        root.iteratorForAppenders()
            .forEachRemaining(
                a -> {
                  if (a instanceof ConsoleAppender<ILoggingEvent> c) {
                    stdout = c;
                  }
                });
        if (stdout != null) {
          root.detachAppender(stdout);
        }
        root.addAppender(instance);
      }
      return instance;
    }
  }

  /** Puts stdout logging back and returns what the ring held, oldest first. */
  public static List<String> restore() {
    final List<String> kept = new ArrayList<>();
    synchronized (LOCK) {
      if (instance != null) {
        final Logger root =
            ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(instance);
        if (stdout != null) {
          root.addAppender(stdout);
        }
        instance.snapshot(Level.ALL).forEach(l -> kept.add(l.text()));
        instance = null;
        stdout = null;
      }
    }
    return kept;
  }

  @Override
  protected void append(final ILoggingEvent e) {
    final String msg = e.getFormattedMessage();
    if (e.getLoggerName().endsWith("RequestLoggingFilter") && isPoll(msg)) {
      return; // the dashboard's own polling would drown the log
    }
    final String text =
        TIME.format(Instant.ofEpochMilli(e.getTimeStamp()))
            + " "
            + String.format("%-5s", e.getLevel())
            + " "
            + shortName(e.getLoggerName())
            + " : "
            + msg;
    synchronized (lines) {
      if (lines.size() == CAPACITY) {
        lines.removeFirst();
      }
      lines.addLast(new Line(e.getLevel(), text));
    }
  }

  private static boolean isPoll(final String msg) {
    return msg.contains("GET /api/executions") || msg.contains("GET /actuator/health");
  }

  private static String shortName(final String logger) {
    return logger.substring(logger.lastIndexOf('.') + 1);
  }

  /** The buffered lines at or above {@code min}, oldest first. */
  /* default */ List<Line> snapshot(final Level min) {
    synchronized (lines) {
      return lines.stream().filter(l -> l.level().isGreaterOrEqual(min)).toList();
    }
  }

  /** Adds a line directly (tests). */
  /* default */ void add(final Level level, final String text) {
    synchronized (lines) {
      if (lines.size() == CAPACITY) {
        lines.removeFirst();
      }
      lines.addLast(new Line(level, text));
    }
  }
}
