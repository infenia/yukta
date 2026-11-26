// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.transformer;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.util.Map;
import java.util.Set;
import lombok.experimental.UtilityClass;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The {@code envelope} option of MAPPER: envelope fields set on the output message. */
@UtilityClass
class MapperEnvelope {

  /** Priority. */
  private static final String PRIORITY = "priority";

  /** Time to live in milliseconds. */
  private static final String TTL_MS = "ttlMs";

  /** Format indicator. */
  private static final String FORMAT = "formatIndicator";

  /** Reply to. */
  private static final String REPLY_TO = "replyTo";

  /** Correlation id. */
  private static final String CORRELATION_ID = "correlationId";

  /** The keys {@code envelope} takes. */
  private static final Set<String> KEYS =
      Set.of(PRIORITY, TTL_MS, FORMAT, REPLY_TO, CORRELATION_ID);

  /**
   * Checks the option.
   *
   * @param envelope the configured option, or null
   * @return empty, or an error naming what is wrong
   */
  /* default */ static Mono<Void> validate(final Object envelope) {
    if (envelope == null) {
      return Mono.empty();
    }
    if (!(envelope instanceof Map<?, ?> fields)) {
      return Mono.error(new IllegalArgumentException("envelope must be a map"));
    }
    return Flux.fromIterable(fields.entrySet())
        .filter(field -> !KEYS.contains(field.getKey()) || field.getValue() == null)
        .next()
        .flatMap(field ->
            Mono.error(
                new IllegalArgumentException(
                    "envelope takes priority, ttlMs, formatIndicator, replyTo and correlationId,"
                        + " each with a value; got '"
                        + field.getKey()
                        + "'")));
  }

  /**
   * The mapped message with the envelope fields set.
   *
   * @param mapped the message MAPPER built
   * @param input the message it was built from, which the expressions read
   * @param envelope the option
   * @param resolver resolves the expressions; null or none uses the values as written
   * @return the mapped message with the fields set
   */
  /* default */ static Mono<Message<?>> apply(
      final Message<?> mapped,
      final Message<?> input,
      final Map<String, Object> envelope,
      final ObjectProvider<VariableResolver> resolver) {
    final VariableResolver variables = resolver == null ? null : resolver.getIfAvailable();
    return Flux.fromIterable(envelope.entrySet())
        .concatMap(entry ->
            (variables == null
                    ? Mono.just(entry.getValue())
                    : variables.resolve(entry.getValue(), input))
                .map(value -> Map.<String, Object>entry(entry.getKey(), value)))
        .<Message<?>>reduce(
            mapped, (msg, field) -> withField(msg, field.getKey(), field.getValue()));
  }

  private static Message<?> withField(final Message<?> msg, final String key, final Object value) {
    return switch (key) {
      case PRIORITY -> msg.withPriority(Math.toIntExact(number(key, value)));
      case TTL_MS -> msg.withExpiration(System.currentTimeMillis() + number(key, value));
      case FORMAT -> msg.withFormatIndicator(String.valueOf(value));
      case REPLY_TO -> msg.withReplyTo(String.valueOf(value));
      case CORRELATION_ID -> msg.withCorrelationId(String.valueOf(value));
      default -> throw new IllegalArgumentException("Unknown envelope field: " + key);
    };
  }

  private static long number(final String key, final Object value) {
    try {
      return value instanceof Number n
          ? n.longValue()
          : Long.parseLong(String.valueOf(value).strip());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "envelope." + key + " must be a whole number; was '" + value + "'", e);
    }
  }
}
