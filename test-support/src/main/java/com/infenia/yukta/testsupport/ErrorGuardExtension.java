// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.testsupport;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.ReflectionSupport;
import org.opentest4j.AssertionFailedError;

/**
 * Finds errors a passing test hides: a Reactor signal that was dropped, and an ERROR log the test
 * did not ask for (see {@link ExpectedErrorLog}). It also finds the opposite: an {@code
 * ExpectedErrorLog} pattern that matches nothing, which is a bug that was fixed (or a test that
 * changed) and an exception nobody removed. A pattern on a method must match in that test; one on a
 * class must match in at least one of its tests. Registered for every module by the ServiceLoader
 * file in this jar; the build turns auto-detection on.
 *
 * <p>The system property {@code yukta.errorGuard} (Gradle: {@code -PerrorGuard=...}) picks the
 * mode: {@code strict} (the default) fails the test, {@code report} logs one {@code [error-guard]}
 * warning per offending test and passes, {@code off} does nothing. The property {@code
 * yukta.errorGuard.stale} ({@code false} when only some tests of a class run, as with {@code
 * --tests}) switches the unused-pattern check off, because the test that matched may not have run.
 */
@Slf4j
@NoArgsConstructor
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.TooManyMethods",
  "PMD.LawOfDemeter",
  "PMD.AvoidCatchingGenericException",
  "PMD.GodClass"
})
public final class ErrorGuardExtension
    implements BeforeEachCallback, AfterEachCallback, AfterAllCallback {

  /** System property that selects the mode. */
  public static final String MODE_PROPERTY = "yukta.errorGuard";

  /** System property that switches the unused-pattern check off when set to {@code false}. */
  public static final String STALE_PROPERTY = "yukta.errorGuard.stale";

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(ErrorGuardExtension.class);

  /** System property that, when {@code false}, limits what is expected to the running test. */
  public static final String MODULE_WIDE_PROPERTY = "yukta.errorGuard.moduleWide";

  /** The patterns declared anywhere in the test classes of this JVM, found once. */
  private static final AtomicReference<List<Pattern>> DECLARED = new AtomicReference<>();

  /** The probes of the test that is running, for {@link #awaitLogged}. */
  private static final AtomicReference<List<Probe>> ACTIVE = new AtomicReference<>(List.of());

  /** A pattern from an annotation, and where it was declared. */
  private record Expectation(Class<?> owner, String where, Pattern pattern, boolean required) {}

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
      ACTIVE.set(probes);
    }
  }

  @Override
  public void afterEach(final ExtensionContext context) {
    final List<?> probes = context.getStore(NAMESPACE).remove(Probe.class, List.class);
    ACTIVE.set(List.of());
    if (probes != null) {
      final List<String> seen = new ArrayList<>();
      probes.forEach(probe -> seen.addAll(((Probe) probe).stop()));
      final List<Expectation> expected = expectations(context);
      final List<Pattern> known =
          new ArrayList<>(expected.stream().map(Expectation::pattern).toList());
      known.addAll(declaredInModule(context.getRequiredTestClass()));
      final List<String> offenders = new ArrayList<>(unexpected(seen, known));
      for (final Expectation expectation : expected) {
        if (seen.stream().anyMatch(line -> expectation.pattern().matcher(line).find())) {
          matched(context).add(key(expectation));
        } else if (expectation.required()
            && expectation.where().startsWith("method")
            && staleChecked()) {
          offenders.add(unused(expectation));
        }
      }
      report(context, offenders);
    }
  }

  @Override
  public void afterAll(final ExtensionContext context) {
    if (mode() != Mode.OFF && staleChecked()) {
      final Class<?> type = context.getRequiredTestClass();
      final List<Expectation> declared = new ArrayList<>();
      collect(Optional.of(type), type, "class " + type.getSimpleName(), declared);
      final List<String> offenders =
          declared.stream()
              .filter(
                  expectation ->
                      expectation.required() && !matched(context).contains(key(expectation)))
              .map(ErrorGuardExtension::unused)
              .toList();
      report(context, offenders);
    }
  }

  /**
   * Waits until the running test has logged an ERROR (or dropped a signal) matching {@code regex}.
   * For a test that provokes an error on another thread: without it the error may be logged after
   * the test ended and land in the next one. Fails when nothing matches within {@code timeout}.
   * Returns at once when the guard records nothing (mode {@code off}, or the extension is not
   * registered, as in a PIT run): there is no late error to separate from the next test.
   *
   * @param regex searched in each recorded line
   * @param timeout how long to wait
   */
  public static void awaitLogged(final String regex, final Duration timeout) {
    if (ACTIVE.get().isEmpty()) {
      return;
    }
    final Pattern pattern = Pattern.compile(regex);
    final long deadline = System.nanoTime() + timeout.toNanos();
    boolean found = matches(pattern);
    while (!found && System.nanoTime() < deadline) {
      LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
      found = matches(pattern);
    }
    if (!found) {
      throw new AssertionFailedError(
          "Nothing matching /" + regex + "/ was logged within " + timeout + "; seen: " + seen());
    }
  }

  private static boolean matches(final Pattern pattern) {
    return seen().stream().anyMatch(line -> pattern.matcher(line).find());
  }

  private static List<String> seen() {
    final List<String> lines = new ArrayList<>();
    ACTIVE.get().forEach(probe -> lines.addAll(probe.snapshot()));
    return lines;
  }

  /**
   * Every pattern that any test class of the module declares (on the class, a nested class or a
   * method). An error that the module's tests expect somewhere is a known source: it is not
   * reported again when it is logged late, on another thread, while a neighbouring test runs. Only
   * an error source that no test declares fails. Switched off by {@link #MODULE_WIDE_PROPERTY}.
   */
  private static List<Pattern> declaredInModule(final Class<?> testClass) {
    List<Pattern> found = List.of();
    if (!"false".equalsIgnoreCase(System.getProperty(MODULE_WIDE_PROPERTY))) {
      found = DECLARED.updateAndGet(current -> current != null ? current : scan(testClass));
    }
    return found;
  }

  /* default */ static List<Pattern> scan(final Class<?> testClass) {
    final List<Pattern> patterns = new ArrayList<>();
    try {
      final URI root = testClass.getProtectionDomain().getCodeSource().getLocation().toURI();
      for (final Class<?> type :
          ReflectionSupport.findAllClassesInClasspathRoot(root, candidate -> true, name -> true)) {
        collectPatterns(type, patterns);
        for (final Method method : type.getDeclaredMethods()) {
          collectPatterns(method, patterns);
        }
      }
    } catch (URISyntaxException | RuntimeException | LinkageError e) {
      log.warn("[error-guard] could not scan the test classes, using the running test only", e);
    }
    return List.copyOf(patterns);
  }

  private static void collectPatterns(
      final AnnotatedElement element, final Collection<Pattern> into) {
    final ExpectedErrorLog annotation = element.getAnnotation(ExpectedErrorLog.class);
    if (annotation != null) {
      for (final String regex : annotation.value()) {
        into.add(Pattern.compile(regex));
      }
      for (final String regex : annotation.sometimes()) {
        into.add(Pattern.compile(regex));
      }
    }
  }

  private static String unused(final Expectation expectation) {
    return "unused @ExpectedErrorLog(\""
        + expectation.pattern().pattern()
        + "\") on "
        + expectation.where()
        + ": nothing matched it, remove it";
  }

  private static String key(final Expectation expectation) {
    return expectation.owner().getName() + "|" + expectation.pattern().pattern();
  }

  private static Set<String> matched(final ExtensionContext context) {
    return context
        .getRoot()
        .getStore(NAMESPACE)
        .getOrComputeIfAbsent("matched", k -> ConcurrentHashMap.newKeySet(), Set.class);
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

  private static List<Expectation> expectations(final ExtensionContext context) {
    final List<Expectation> found = new ArrayList<>();
    final Class<?> test = context.getRequiredTestClass();
    collect(context.getElement(), test, "method " + context.getDisplayName(), found);
    Class<?> type = test;
    while (type != null) {
      collect(Optional.of(type), type, "class " + type.getSimpleName(), found);
      type = type.getEnclosingClass();
    }
    return found;
  }

  private static void collect(
      final Optional<? extends AnnotatedElement> element,
      final Class<?> owner,
      final String where,
      final List<Expectation> into) {
    element
        .map(e -> e.getAnnotation(ExpectedErrorLog.class))
        .ifPresent(
            annotation -> {
              for (final String regex : annotation.value()) {
                into.add(new Expectation(owner, where, Pattern.compile(regex), true));
              }
              for (final String regex : annotation.sometimes()) {
                into.add(new Expectation(owner, where, Pattern.compile(regex), false));
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

  private static boolean staleChecked() {
    return !"false".equalsIgnoreCase(System.getProperty(STALE_PROPERTY));
  }

  private static Mode mode() {
    return Mode.parse(System.getProperty(MODE_PROPERTY));
  }

  /** What to do with an offender. */
  /* default */ enum Mode {
    OFF,
    REPORT,
    STRICT;

    /** Reads a property value; anything unknown or missing means {@link #STRICT}. */
    /* default */ static Mode parse(final String value) {
      final String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
      return switch (text) {
        case "off" -> OFF;
        case "report" -> REPORT;
        default -> STRICT;
      };
    }
  }
}
