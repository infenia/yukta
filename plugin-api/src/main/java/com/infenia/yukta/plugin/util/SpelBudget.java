// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.util;

import java.io.Serial;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.MethodExecutor;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;

/**
 * A time limit for one expression evaluation.
 *
 * <p>An evaluation cannot be interrupted from outside while it computes, so the sandbox checks the
 * limit as the expression works: every method call and every property read, and while a regular
 * expression reads its text ({@code matches} and {@code split}, which would otherwise run a
 * backtracking pattern for as long as it likes). The limit is {@value #TIMEOUT_PROPERTY}
 * milliseconds, 1,000 by default, and 0 or less turns it off. Pure arithmetic on literals does not
 * check it, but such an expression cannot grow with the data. Looping over a collection with a
 * selection or projection is bounded separately by Spring's own limit of 10,000 operations per
 * evaluation.
 */
final class SpelBudget {

  /** System property: the longest one evaluation may run, in milliseconds. */
  /* default */ static final String TIMEOUT_PROPERTY = "yukta.spel.timeout-ms";

  /** Milliseconds one evaluation may run when {@value #TIMEOUT_PROPERTY} is not set. */
  /* default */ static final long DEFAULT_TIMEOUT_MS = 1_000L;

  /** The String methods that run a regular expression. */
  private static final String MATCHES_METHOD = "matches";

  /** The String methods that run a regular expression. */
  private static final String SPLIT_METHOD = "split";

  /** How many characters a regex reads between two looks at the clock. */
  private static final int CHARS_PER_CHECK = 1_024;

  /** The deadline of the evaluation running on this thread, in {@link System#nanoTime} terms. */
  private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();

  private SpelBudget() {
    // static helpers only
  }

  /**
   * The longest one evaluation may run.
   *
   * @return milliseconds; 0 or less means no limit
   */
  /* default */ static long timeoutMillis() {
    return Long.getLong(TIMEOUT_PROPERTY, DEFAULT_TIMEOUT_MS);
  }

  /**
   * Runs an evaluation under the time limit.
   *
   * @param evaluation the evaluation
   * @param <T> the result type
   * @return the result of the evaluation
   */
  /* default */ static <T> @Nullable T run(final Supplier<T> evaluation) {
    final long timeout = timeoutMillis();
    final Long outer = DEADLINE.get();
    if (timeout > 0) {
      DEADLINE.set(System.nanoTime() + timeout * 1_000_000L);
    }
    try {
      return evaluation.get();
    } finally {
      if (outer == null) {
        DEADLINE.remove();
      } else {
        DEADLINE.set(outer);
      }
    }
  }

  /**
   * Whether an error, or something it was caused by, is the time limit being hit.
   *
   * @param error the error
   * @return true if the evaluation ran out of time
   */
  /* default */ static boolean exceeded(final Throwable error) {
    boolean exceeded = false;
    Throwable cause = error;
    // bounded, so a cause chain that loops back on itself cannot spin forever
    for (int depth = 0; !exceeded && cause != null && depth < 8; depth++) {
      exceeded = cause instanceof Exceeded;
      cause = cause.getCause();
    }
    return exceeded;
  }

  /** Stops the evaluation on this thread if it has used up its time. */
  /* default */ static void check() {
    final Long deadline = DEADLINE.get();
    if (deadline != null && System.nanoTime() - deadline > 0) {
      throw new Exceeded();
    }
  }

  /**
   * Makes a method executor check the limit, and run {@code matches} and {@code split} on a text
   * that checks it while the regular expression reads it.
   *
   * @param delegate the executor that finds and calls the method
   * @param name the method name
   * @return the checking executor
   */
  /* default */ static MethodExecutor guard(final MethodExecutor delegate, final String name) {
    return (context, target, arguments) -> {
      check();
      final TypedValue regexResult = regexCall(name, target, arguments);
      return regexResult == null ? delegate.execute(context, target, arguments) : regexResult;
    };
  }

  /**
   * Makes a property accessor check the limit on every read.
   *
   * @param delegate the accessor
   * @return the checking accessor
   */
  /* default */ static PropertyAccessor guard(final PropertyAccessor delegate) {
    return new GuardedAccessor(delegate);
  }

  /** Runs {@code matches} or {@code split} on a text that checks the limit; null for others. */
  private static @Nullable TypedValue regexCall(
      final String name, final Object target, final @Nullable Object... arguments) {
    TypedValue result = null;
    if (target instanceof String text
        && arguments.length > 0
        && arguments[0] instanceof String regex) {
      if (MATCHES_METHOD.equals(name)) {
        result = matches(text, regex);
      } else if (SPLIT_METHOD.equals(name)) {
        result = split(text, regex, arguments);
      }
    }
    return result;
  }

  private static TypedValue matches(final String text, final String regex) {
    return new TypedValue(Pattern.compile(regex).matcher(new GuardedText(text)).matches());
  }

  private static TypedValue split(
      final String text, final String regex, final @Nullable Object... arguments) {
    // the executor is only called with the arguments of a method it found, already converted
    final int limit = arguments.length == 2 ? ((Number) arguments[1]).intValue() : 0;
    return new TypedValue(Pattern.compile(regex).split(new GuardedText(text), limit));
  }

  /** The evaluation ran out of time. */
  /* default */ static final class Exceeded extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    /* default */ Exceeded() {
      super("Expression evaluation exceeded its time budget");
    }
  }

  /** A text whose reading checks the limit every so often. */
  private static final class GuardedText implements CharSequence {

    /** The text. */
    private final String text;

    /** How many characters were read, to look at the clock only now and then. */
    private int reads;

    /* default */ GuardedText(final String text) {
      this.text = text;
    }

    @Override
    public int length() {
      return text.length();
    }

    @Override
    public char charAt(final int index) {
      reads++;
      if ((reads & (CHARS_PER_CHECK - 1)) == 0) {
        check();
      }
      return text.charAt(index);
    }

    @Override
    public CharSequence subSequence(final int start, final int end) {
      return text.subSequence(start, end);
    }
  }

  /** A property accessor that checks the limit before it reads. */
  private static final class GuardedAccessor implements PropertyAccessor {

    /** The accessor that does the reading. */
    private final PropertyAccessor delegate;

    /* default */ GuardedAccessor(final PropertyAccessor delegate) {
      this.delegate = delegate;
    }

    @Override
    public Class<?> @Nullable [] getSpecificTargetClasses() {
      return delegate.getSpecificTargetClasses();
    }

    @Override
    public boolean canRead(
        final EvaluationContext context, final @Nullable Object target, final String name)
        throws AccessException {
      check();
      return delegate.canRead(context, target, name);
    }

    @Override
    public TypedValue read(
        final EvaluationContext context, final @Nullable Object target, final String name)
        throws AccessException {
      check();
      return delegate.read(context, target, name);
    }

    @Override
    public boolean canWrite(
        final EvaluationContext context, final @Nullable Object target, final String name)
        throws AccessException {
      return delegate.canWrite(context, target, name);
    }

    @Override
    public void write(
        final EvaluationContext context,
        final @Nullable Object target,
        final String name,
        final @Nullable Object newValue)
        throws AccessException {
      delegate.write(context, target, name, newValue);
    }
  }
}
