// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.infenia.yukta.message.Message;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** High-speed, non-reflective evaluator for simple expressions. Supports: ==, exists, matches. */
public final class SimpleExpressionEvaluator {
  /** Payload prefix. */
  private static final String PAYLOAD_PREFIX = "payload.";

  /** Metadata prefix. */
  private static final String METADATA_PREFIX = "metadata.";

  /** Payload. */
  private static final String PAYLOAD = "payload";

  /** Expr pattern. */
  private static final Pattern EXPR_PATTERN =
      Pattern.compile("^(\\S+)\\s+(==|exists|matches)(?:\\s+(.+))?$");

  /** Expressions kept parsed; the least recently used are dropped beyond this many. */
  private static final long CACHE_MAX = 1_000;

  /** Cache. A dropped expression is parsed again when it is next used. */
  private static final Cache<String, Expression> CACHE =
      Caffeine.newBuilder().maximumSize(CACHE_MAX).executor(Runnable::run).build();

  private SimpleExpressionEvaluator() {
    // Utility class
  }

  /**
   * Evaluate a simple expression against a message.
   *
   * @param expressionStr the expression string
   * @param message the message to evaluate
   * @return the result of evaluation
   * @throws IllegalArgumentException if the expression is invalid
   */
  public static boolean evaluate(final String expressionStr, final Message<?> message) {
    final Expression expression = CACHE.get(expressionStr, SimpleExpressionEvaluator::parse);
    return expression.evaluate(message);
  }

  /**
   * Pre-parse and cache an expression.
   *
   * @param expressionStr the expression string
   */
  public static void preParse(final String expressionStr) {
    if (expressionStr != null && !expressionStr.isBlank()) {
      CACHE.asMap().computeIfAbsent(expressionStr, SimpleExpressionEvaluator::parse);
    }
  }

  /**
   * Number of expressions kept parsed.
   *
   * @return the cache size
   */
  /* default */ static long cacheSize() {
    CACHE.cleanUp();
    return CACHE.estimatedSize();
  }

  private static Expression parse(final String expressionStr) {
    final Matcher matcher = EXPR_PATTERN.matcher(expressionStr.trim());
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Invalid SIMPLE expression: " + expressionStr);
    }
    final String path = matcher.group(1);
    final String operator = matcher.group(2);
    final String operand = matcher.group(3);

    return switch (operator) {
      case "==" -> new EqualsExpression(path, stripQuotes(operand));
      case "exists" -> new ExistsExpression(path);
      default -> new MatchesExpression(path, stripQuotes(operand));
    };
  }

  private static String stripQuotes(final String str) {
    if (str == null) {
      return null;
    }
    String result = str.trim();
    if ((result.startsWith("'") && result.endsWith("'"))
        || (result.startsWith("\"") && result.endsWith("\""))) {
      result = result.substring(1, result.length() - 1);
    }
    return result;
  }

  /** Expression. */
  private abstract static class Expression {
    /** Path. */
    /* default */ final String path;

    /* default */ Expression(final String path) {
      this.path = path;
    }

    /* default */ abstract boolean evaluate(Message<?> message);

    /* default */ Object getValue(final Message<?> message) {
      Object value = null;
      if (path.startsWith(PAYLOAD_PREFIX)) {
        value = getNested(message.payload(), path.substring(PAYLOAD_PREFIX.length()));
      } else if (path.startsWith(METADATA_PREFIX)) {
        value = message.metadata().get(path.substring(METADATA_PREFIX.length()));
      } else if (PAYLOAD.equals(path)) {
        value = message.payload();
      }
      return value;
    }

    private Object getNested(final Object obj, final String path) {
      Object result = null;
      if (obj instanceof Map<?, ?> map) {
        final int dotIndex = path.indexOf('.');
        if (dotIndex == -1) {
          result = map.get(path);
        } else {
          final String current = path.substring(0, dotIndex);
          final String remaining = path.substring(dotIndex + 1);
          final Object next = map.get(current);
          if (next != null) {
            result = getNested(next, remaining);
          }
        }
      }
      return result;
    }
  }

  /** Equals expression. */
  private static class EqualsExpression extends Expression {
    /** Expected. */
    private final String expected;

    /* default */ EqualsExpression(final String path, final String expected) {
      super(path);
      this.expected = expected;
    }

    @Override
    public boolean evaluate(final Message<?> message) {
      final Object value = getValue(message);
      return value == null ? expected == null : String.valueOf(value).equals(expected);
    }
  }

  /** Exists expression. */
  private static class ExistsExpression extends Expression {
    /* default */ ExistsExpression(final String path) {
      super(path);
    }

    @Override
    public boolean evaluate(final Message<?> message) {
      return getValue(message) != null;
    }
  }

  /** Matches expression. */
  private static class MatchesExpression extends Expression {
    /** Pattern. */
    private final Pattern pattern;

    /* default */ MatchesExpression(final String path, final String regex) {
      super(path);
      this.pattern = Pattern.compile(regex);
    }

    @Override
    public boolean evaluate(final Message<?> message) {
      final Object value = getValue(message);
      return value != null && pattern.matcher(String.valueOf(value)).matches();
    }
  }
}
