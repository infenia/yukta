// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.util;

import com.infenia.yukta.plugin.store.SecretProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Resolves variables and expressions in configuration values. */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.SimplifyBooleanReturns"})
public class VariableResolver {

  /** Pattern for matching variable expressions like ${varname}. */
  private static final Pattern EXPR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

  /** Pattern for identifying sensitive configuration key names. */
  private static final Pattern SECRET_BLACKLIST =
      Pattern.compile(".*(PASSWORD|SECRET|KEY).*", Pattern.CASE_INSENSITIVE);

  /** Prefix indicating a value is encrypted and needs decryption. */
  private static final String DECRYPTED_PREFIX = "decrypted:";

  /** Provider for retrieving encrypted secrets. */
  private final SecretProvider secretProvider;

  /**
   * Check if a value is static (no interpolation or dynamic resolution).
   *
   * @param value the value to check
   * @return true if static
   */
  public boolean isStatic(final Object value) {
    if (!(value instanceof String strValue)) {
      return true;
    }
    return !strValue.startsWith(DECRYPTED_PREFIX) && !EXPR_PATTERN.matcher(strValue).find();
  }

  /**
   * Resolve a value that may contain variables or expressions.
   *
   * @param value the value to resolve
   * @return a Mono containing the resolved value
   */
  public Mono<Object> resolve(final Object value) {
    if (!(value instanceof String strValue)) {
      return Mono.just(value);
    }

    if (strValue.startsWith(DECRYPTED_PREFIX)) {
      return resolveSecret(strValue.substring(DECRYPTED_PREFIX.length())).map(val -> val);
    }

    final Matcher matcher = EXPR_PATTERN.matcher(strValue);
    if (!matcher.find()) {
      return Mono.just(strValue);
    }

    // Optimization: If the whole string is a single variable, we can preserve type
    if (matcher.start() == 0 && matcher.end() == strValue.length()) {
      return resolveExpression(matcher.group(1));
    }

    // Interpolation case: Always returns String
    return resolveInterpolation(strValue);
  }

  private Mono<Object> resolveExpression(final String expression) {
    String resolvedKey = expression;
    String resolvedType = "string";

    final int lastColon = expression.lastIndexOf(':');
    if (lastColon > 0 && lastColon < expression.length() - 1) {
      final String suffix = expression.substring(lastColon + 1);
      if (isSupportedType(suffix)) {
        resolvedKey = expression.substring(0, lastColon);
        resolvedType = suffix.toLowerCase(Locale.ROOT);
      }
    }

    final String finalKey = resolvedKey;
    final String finalType = resolvedType;

    return resolveRawValue(finalKey).map(val -> castValue(val, finalType));
  }

  private boolean isSupportedType(final String type) {
    final String lowType = type.toLowerCase(Locale.ROOT);
    return "int".equals(lowType)
        || "integer".equals(lowType)
        || "long".equals(lowType)
        || "double".equals(lowType)
        || "float".equals(lowType)
        || "bool".equals(lowType)
        || "boolean".equals(lowType)
        || "string".equals(lowType);
  }

  private Mono<Object> resolveRawValue(final String key) {
    if (key.startsWith(DECRYPTED_PREFIX)) {
      return resolveSecret(key.substring(DECRYPTED_PREFIX.length())).map(Object::toString);
    }

    if (SECRET_BLACKLIST.matcher(key).matches()) {
      return Mono.error(new SecurityException("Access to sensitive key '" + key + "' is blocked"));
    }

    if (key.startsWith("env.")) {
      return Mono.justOrEmpty(System.getenv(key.substring(4)));
    } else if (key.startsWith("sys.")) {
      return Mono.justOrEmpty(System.getProperty(key.substring(4)));
    } else if (key.startsWith("context.")) {
      return resolveContextVariable(key.substring(8));
    }

    return Mono.just(key);
  }

  private Mono<Object> resolveContextVariable(final String varName) {
    return Mono.deferContextual(ctx -> Mono.justOrEmpty(ctx.getOrEmpty(varName)));
  }

  private Mono<String> resolveSecret(final String key) {
    return secretProvider
        .getSecret(key)
        .switchIfEmpty(
            Mono.error(new IllegalArgumentException("Secret not found for key: " + key)));
  }

  private Object castValue(final Object value, final String type) {
    return switch (type) {
      case "int", "integer" -> MapUtils.convert(value, Integer.class);
      case "long" -> MapUtils.convert(value, Long.class);
      case "double", "float" -> MapUtils.convert(value, Double.class);
      case "bool", "boolean" -> MapUtils.convert(value, Boolean.class);
      default -> String.valueOf(value);
    };
  }

  private Mono<Object> resolveInterpolation(final String value) {
    int lastEnd = 0;
    final Matcher matcher = EXPR_PATTERN.matcher(value);

    record Segment(int start, int end, String expression, String literal) {}

    final List<Segment> segments = new ArrayList<>();

    while (matcher.find()) {
      if (matcher.start() > lastEnd) {
        segments.add(
            new Segment(lastEnd, matcher.start(), null, value.substring(lastEnd, matcher.start())));
      }
      segments.add(new Segment(matcher.start(), matcher.end(), matcher.group(1), null));
      lastEnd = matcher.end();
    }
    if (lastEnd < value.length()) {
      segments.add(new Segment(lastEnd, value.length(), null, value.substring(lastEnd)));
    }

    return Flux.fromIterable(segments)
        .flatMapSequential(
            segment -> {
              if (segment.expression() != null) {
                return resolveExpression(segment.expression()).map(String::valueOf);
              } else {
                return Mono.just(segment.literal());
              }
            })
        .collectList()
        .map(list -> String.join("", list));
  }
}
