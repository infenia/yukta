/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.jagratha.plugin.core;

import com.infenia.jagratha.plugin.Message;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** High-speed, non-reflective evaluator for simple expressions. Supports: ==, exists, matches. */
@SuppressWarnings("PMD.OnlyOneReturn")
public final class SimpleExpressionEvaluator {
  private static final String PAYLOAD_PREFIX = "payload.";
  private static final String METADATA_PREFIX = "metadata.";
  private static final String PAYLOAD = "payload";

  private static final Pattern EXPR_PATTERN =
      Pattern.compile("^(\\S+)\\s+(==|exists|matches)(?:\\s+(.+))?$");
  private static final Map<String, Expression> CACHE = new ConcurrentHashMap<>();

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
  public static boolean evaluate(final String expressionStr, final Message message) {
    final Expression expression =
        CACHE.computeIfAbsent(expressionStr, SimpleExpressionEvaluator::parse);
    return expression.evaluate(message);
  }

  /**
   * Pre-parse and cache an expression.
   *
   * @param expressionStr the expression string
   */
  public static void preParse(final String expressionStr) {
    if (expressionStr != null && !expressionStr.isBlank()) {
      CACHE.computeIfAbsent(expressionStr, SimpleExpressionEvaluator::parse);
    }
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
      case "matches" -> new MatchesExpression(path, stripQuotes(operand));
      default -> throw new UnsupportedOperationException("Operator " + operator + " not supported");
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

  private abstract static class Expression {
    protected final String path;

    protected Expression(final String path) {
      this.path = path;
    }

    /* default */

    abstract boolean evaluate(Message message);

    protected Object getValue(final Message message) {
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

  private static class EqualsExpression extends Expression {
    private final String expected;

    /* default */ EqualsExpression(final String path, final String expected) {
      super(path);
      this.expected = expected;
    }

    @Override
    public boolean evaluate(final Message message) {
      final Object value = getValue(message);
      return value == null ? expected == null : String.valueOf(value).equals(expected);
    }
  }

  private static class ExistsExpression extends Expression {
    /* default */ ExistsExpression(final String path) {
      super(path);
    }

    @Override
    public boolean evaluate(final Message message) {
      return getValue(message) != null;
    }
  }

  private static class MatchesExpression extends Expression {
    private final Pattern pattern;

    /* default */ MatchesExpression(final String path, final String regex) {
      super(path);
      this.pattern = Pattern.compile(regex);
    }

    @Override
    public boolean evaluate(final Message message) {
      final Object value = getValue(message);
      return value != null && pattern.matcher(String.valueOf(value)).matches();
    }
  }
}
