// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.testsupport;

import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.AssertionFailedError;

/**
 * Finds errors a passing test hides: a Reactor signal that was dropped, and an ERROR log the test
 * did not ask for (see {@link ExpectedErrorLog}). Registered for every module by the ServiceLoader
 * file in this jar; the build turns auto-detection on.
 *
 * <p>The system property {@code yukta.errorGuard} (Gradle: {@code -PerrorGuard=...}) picks the
 * mode: {@code report} (default) logs one {@code [error-guard]} warning per offending test and
 * passes; {@code strict} fails the test; {@code off} does nothing.
 */
@Slf4j
@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
public final class ErrorGuardExtension implements BeforeEachCallback, AfterEachCallback {

  /** System property that selects the mode. */
  public static final String MODE_PROPERTY = "yukta.errorGuard";

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(ErrorGuardExtension.class);

  @Override
  public void beforeEach(final ExtensionContext context) {
    if (mode() != Mode.OFF) {
      final List<Probe> probes = new ArrayList<>();
      if (present("reactor.core.publisher.Hooks")) {
        probes.add(new ReactorProbe());
      }
      if (present("ch.qos.logback.classic.LoggerContext") && LogbackProbe.available()) {
        probes.add(new LogbackProbe());
      }
      probes.forEach(Probe::start);
      context.getStore(NAMESPACE).put(Probe.class, probes);
    }
  }

  @Override
  public void afterEach(final ExtensionContext context) {
    final List<?> probes = context.getStore(NAMESPACE).remove(Probe.class, List.class);
    if (probes != null) {
      final List<String> seen = new ArrayList<>();
      probes.forEach(probe -> seen.addAll(((Probe) probe).stop()));
      report(context, unexpected(seen, expectedPatterns(context)));
    }
  }

  private static void report(final ExtensionContext context, final List<String> offenders) {
    if (!offenders.isEmpty()) {
      final String report =
          context.getDisplayName()
              + " in "
              + context.getRequiredTestClass().getSimpleName()
              + ": "
              + String.join(" | ", offenders);
      if (mode() == Mode.STRICT) {
        throw new AssertionFailedError(
            "Hidden errors (annotate with @ExpectedErrorLog if intended): " + report);
      }
      log.warn("[error-guard] {}", report);
    }
  }

  /** The lines that no pattern matches. */
  /* default */ static List<String> unexpected(
      final List<String> seen, final List<Pattern> expected) {
    return seen.stream()
        .filter(line -> expected.stream().noneMatch(pattern -> pattern.matcher(line).find()))
        .toList();
  }

  private static List<Pattern> expectedPatterns(final ExtensionContext context) {
    final List<Pattern> patterns = new ArrayList<>();
    collect(context.getElement(), patterns);
    Class<?> type = context.getRequiredTestClass();
    while (type != null) {
      collect(Optional.of(type), patterns);
      type = type.getEnclosingClass();
    }
    return patterns;
  }

  private static void collect(
      final Optional<? extends AnnotatedElement> element, final List<Pattern> into) {
    element
        .map(e -> e.getAnnotation(ExpectedErrorLog.class))
        .ifPresent(
            annotation -> {
              for (final String regex : annotation.value()) {
                into.add(Pattern.compile(regex));
              }
            });
  }

  /* default */ static boolean present(final String className) {
    boolean found = true;
    try {
      Class.forName(className, false, Thread.currentThread().getContextClassLoader());
    } catch (ClassNotFoundException e) {
      found = false;
    }
    return found;
  }

  private static Mode mode() {
    return Mode.parse(System.getProperty(MODE_PROPERTY));
  }

  /** What to do with an offender. */
  /* default */ enum Mode {
    OFF,
    REPORT,
    STRICT;

    /** Reads a property value; anything unknown or missing means {@link #REPORT}. */
    /* default */ static Mode parse(final String value) {
      final String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
      return switch (text) {
        case "off" -> OFF;
        case "strict" -> STRICT;
        default -> REPORT;
      };
    }
  }
}
