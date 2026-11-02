// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * The one code path the plugins that ask for input share: read the question from a node's config,
 * find the {@link InputGateway}, and tell a timeout from a refusal. A plugin takes an {@code
 * ObjectProvider<InputGateway>} and passes {@code provider::getIfAvailable}; the run and node that
 * ask come from the Reactor context, so a plugin never names them.
 */
@UtilityClass
public class InputAsk {

  /**
   * Context key under which a request carries the name of the signal it stands for, so the older
   * signal command can answer it.
   */
  public static final String SIGNAL_CONTEXT_KEY = "signal";

  /** Why an ask fails when the application has no gateway. */
  public static final String NO_GATEWAY =
      "No InputGateway is available: this plugin asks for input through the input broker, which"
          + " this application does not provide";

  /**
   * Asks and waits.
   *
   * @param gateway where the gateway is found, at use time; may return {@code null}
   * @param spec the question
   * @return the answer; fails with {@link IllegalStateException} when there is no gateway, and as
   *     {@link InputGateway#ask} does otherwise
   */
  public static Mono<InputResponse> ask(
      final Supplier<@Nullable InputGateway> gateway, final InputRequestSpec spec) {
    return Mono.defer(
        () -> {
          final InputGateway found = gateway.get();
          return found == null
              ? Mono.<InputResponse>error(new IllegalStateException(NO_GATEWAY))
              : found.ask(spec);
        });
  }

  /**
   * Whether an ask failed because nobody answered in time.
   *
   * @param error the failure
   * @return true for {@link InputExpiredException}
   */
  public static boolean isTimeout(final Throwable error) {
    return error instanceof InputExpiredException;
  }

  /**
   * Reads a kind option.
   *
   * @param value the config value, or {@code null}
   * @param fallback the kind when there is no value
   * @return the kind
   * @throws IllegalArgumentException if the value is not a kind name
   */
  public static InputKind kind(final @Nullable Object value, final InputKind fallback) {
    return enumValue("kind", value, fallback, InputKind.class);
  }

  /**
   * Reads a risk option.
   *
   * @param value the config value, or {@code null}
   * @param fallback the risk when there is no value
   * @return the risk
   * @throws IllegalArgumentException if the value is not a risk name
   */
  public static InputRisk risk(final @Nullable Object value, final InputRisk fallback) {
    return enumValue("risk", value, fallback, InputRisk.class);
  }

  /**
   * Reads an options list: strings, or {@code {id, label}} maps.
   *
   * @param value the config value, or {@code null} for none
   * @return the options, empty for none
   * @throws IllegalArgumentException if an entry is neither
   */
  public static List<InputOption> options(final @Nullable Object value) {
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof final List<?> list)) {
      throw new IllegalArgumentException("options must be a list of ids or {id, label}");
    }
    final List<InputOption> options = new ArrayList<>();
    for (final Object entry : list) {
      if (entry instanceof final String id) {
        options.add(new InputOption(id));
      } else if (entry instanceof final Map<?, ?> map && map.get("id") instanceof final String id) {
        options.add(
            new InputOption(
                id, map.get("label") == null ? null : String.valueOf(map.get("label"))));
      } else {
        throw new IllegalArgumentException("an option is an id or {id, label}: " + entry);
      }
    }
    return options;
  }

  /**
   * Reads an audience option.
   *
   * @param value the config value: a list of selectors, or {@code null} for the current tier
   * @return the audience
   * @throws IllegalArgumentException if it is not a list of valid selectors
   */
  public static Audience audience(final @Nullable Object value) {
    if (value == null) {
      return Audience.CURRENT_TIER;
    }
    if (!(value instanceof final List<?> list)) {
      throw new IllegalArgumentException("audience must be a list of selectors");
    }
    final List<String> selectors = new ArrayList<>();
    for (final Object entry : list) {
      selectors.add(entry instanceof final String text ? text : String.valueOf(entry));
    }
    return new Audience(selectors);
  }

  /**
   * Cleans text that came from a model or another untrusted source: control characters other than
   * newline and tab are dropped and the text is cut to a byte limit on a character boundary.
   *
   * @param text the text, or {@code null}
   * @param maxBytes the longest result, in UTF-8 bytes
   * @return the cleaned text, empty for {@code null}
   */
  public static String clean(final @Nullable String text, final int maxBytes) {
    if (text == null) {
      return "";
    }
    final StringBuilder out = new StringBuilder(Math.min(text.length(), maxBytes));
    int bytes = 0;
    int index = 0;
    while (index < text.length()) {
      final int point = text.codePointAt(index);
      index += Character.charCount(point);
      if (isDroppedControl(point)) {
        continue;
      }
      final int size = new String(Character.toChars(point)).getBytes(StandardCharsets.UTF_8).length;
      if (bytes + size > maxBytes) {
        break;
      }
      bytes += size;
      out.appendCodePoint(point);
    }
    return out.toString().strip();
  }

  private static boolean isDroppedControl(final int point) {
    return switch (Character.getType(point)) {
      case Character.CONTROL -> point != '\n' && point != '\t';
      case Character.FORMAT, Character.UNASSIGNED, Character.SURROGATE -> true;
      default -> false;
    };
  }

  private static <E extends Enum<E>> E enumValue(
      final String name, final @Nullable Object value, final E fallback, final Class<E> type) {
    if (value == null) {
      return fallback;
    }
    try {
      return Enum.valueOf(type, String.valueOf(value).strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          name + " must be one of " + List.of(type.getEnumConstants()) + ": " + value, e);
    }
  }
}
