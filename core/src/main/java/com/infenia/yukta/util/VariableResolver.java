// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.util;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.store.SecretProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Resolves variables and expressions in configuration values. */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.SimplifyBooleanReturns", "PMD.TooManyMethods"})
public class VariableResolver {

  /** Pattern for matching variable expressions like ${varname}. */
  private static final Pattern EXPR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

  /**
   * Pattern for identifying sensitive environment variable and system property names. Applies only
   * to {@code env.} and {@code sys.} lookups; message data is the workflow's own and is not
   * filtered.
   */
  private static final Pattern SECRET_BLACKLIST =
      Pattern.compile(
          ".*(PASSWORD|PASSWD|SECRET|TOKEN|CREDENTIAL|PRIVATE|KEY).*", Pattern.CASE_INSENSITIVE);

  /** Environment variables that workflows may read besides the {@code YUKTA_VAR_} ones. */
  private static final Set<String> SAFE_ENV =
      Set.of("HOME", "USER", "LANG", "TZ", "HOSTNAME", "PWD", "PATH");

  /** System properties that workflows may read besides the {@code yukta.var.} ones. */
  private static final Set<String> SAFE_SYS =
      Set.of(
          "user.name",
          "user.home",
          "user.dir",
          "user.timezone",
          "os.name",
          "java.version",
          "file.separator",
          "path.separator",
          "line.separator");

  /** Environment variables with this prefix are readable by workflows. */
  private static final String ENV_PREFIX = "YUKTA_VAR_";

  /** System properties with this prefix are readable by workflows. */
  private static final String SYS_PREFIX = "yukta.var.";

  /** System property listing more readable names, separated by commas. */
  private static final String ALLOWED_PROPERTY = "yukta.variables.allowed";

  /** Environment variable listing more readable names, separated by commas. */
  private static final String ALLOWED_ENV = "YUKTA_VARIABLES_ALLOWED";

  /** Prefix indicating a value is encrypted and needs decryption. */
  private static final String DECRYPTED_PREFIX = "decrypted:";

  /** Key referencing the whole message payload. */
  private static final String PAYLOAD = "payload";

  /** Prefix for paths into the message payload. */
  private static final String PAYLOAD_PREFIX = "payload.";

  /** Prefix for keys into the message metadata. */
  private static final String METADATA_PREFIX = "metadata.";

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
    return resolve(value, null);
  }

  /**
   * Resolve a value that may contain variables or expressions, with access to the message being
   * processed.
   *
   * <p>In addition to the message-independent sources, expressions may reference the message via
   * {@code ${payload}}, {@code ${payload.some.field}} (nested map access), and {@code
   * ${metadata.someKey}}. When {@code message} is null, such expressions fall back to the default
   * behavior of returning the key literally.
   *
   * @param value the value to resolve
   * @param message the message being processed, or null when no message context is available
   * @return a Mono containing the resolved value
   */
  public Mono<Object> resolve(final Object value, final Message<?> message) {
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
      return resolveExpression(matcher.group(1), message);
    }

    // Interpolation case: Always returns String
    return resolveInterpolation(strValue, message);
  }

  private Mono<Object> resolveExpression(final String expression, final Message<?> message) {
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

    return resolveRawValue(finalKey, message).map(val -> castValue(val, finalType));
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

  private Mono<Object> resolveRawValue(final String key, final Message<?> message) {
    if (key.startsWith(DECRYPTED_PREFIX)) {
      return resolveSecret(key.substring(DECRYPTED_PREFIX.length())).map(Object::toString);
    }

    if (key.startsWith("env.") || key.startsWith("sys.")) {
      return resolveHostValue(key);
    } else if (key.startsWith("context.")) {
      return resolveContextVariable(key.substring(8));
    } else if (message != null && isMessageKey(key)) {
      return resolveMessageValue(key, message);
    }

    return Mono.just(key);
  }

  /** Only a short list and the prefixed names are readable: the process holds more than secrets. */
  private static boolean isAllowed(final String name, final boolean env) {
    return name.startsWith(env ? ENV_PREFIX : SYS_PREFIX)
        || (env ? SAFE_ENV : SAFE_SYS).contains(name)
        || Stream.of(System.getProperty(ALLOWED_PROPERTY), System.getenv(ALLOWED_ENV))
            .filter(Objects::nonNull)
            .flatMap(list -> Stream.of(list.split(",")))
            .map(String::trim)
            .anyMatch(name::equals);
  }

  private static Mono<Object> resolveHostValue(final String key) {

    final String name = key.substring(4);
    final boolean env = key.startsWith("env.");
    if (SECRET_BLACKLIST.matcher(name).matches()) {
      return Mono.error(new SecurityException("Access to sensitive key '" + key + "' is blocked"));
    }
    if (!isAllowed(name, env)) {
      return Mono.error(
          new SecurityException(
              "Access to '"
                  + key
                  + "' is not allowed: name it "
                  + (env ? ENV_PREFIX : SYS_PREFIX)
                  + "* or list it in "
                  + ALLOWED_PROPERTY
                  + " / "
                  + ALLOWED_ENV));
    }
    return Mono.justOrEmpty(env ? System.getenv(name) : System.getProperty(name));
  }

  private boolean isMessageKey(final String key) {
    return PAYLOAD.equals(key) || key.startsWith(PAYLOAD_PREFIX) || key.startsWith(METADATA_PREFIX);
  }

  private Mono<Object> resolveMessageValue(final String key, final Message<?> message) {
    if (PAYLOAD.equals(key)) {
      return Mono.justOrEmpty(message.payload());
    }
    if (key.startsWith(PAYLOAD_PREFIX)) {
      return Mono.justOrEmpty(getNested(message.payload(), key.substring(PAYLOAD_PREFIX.length())));
    }
    return Mono.justOrEmpty(message.metadata().get(key.substring(METADATA_PREFIX.length())));
  }

  private Object getNested(final Object obj, final String path) {
    if (!(obj instanceof Map<?, ?> map)) {
      return null;
    }
    final int dotIndex = path.indexOf('.');
    if (dotIndex < 0) {
      return map.get(path);
    }
    final Object next = map.get(path.substring(0, dotIndex));
    return next == null ? null : getNested(next, path.substring(dotIndex + 1));
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

  private Mono<Object> resolveInterpolation(final String value, final Message<?> message) {
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
                return resolveExpression(segment.expression(), message).map(String::valueOf);
              } else {
                return Mono.just(segment.literal());
              }
            })
        .collectList()
        .map(list -> String.join("", list));
  }
}
