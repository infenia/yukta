// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * A question a plugin asks. The plugin never says which run or node asks: the engine knows that
 * ({@link InputIdentity}).
 *
 * <p>The prompt and the context come from the plugin, often from an AI agent, so they are untrusted
 * text. They are capped here and redacted before they are stored or shown.
 *
 * @param key idempotency key, see {@link InputKeys}; asking again with the same key returns the
 *     same request, or the earlier answer
 * @param kind what is asked for
 * @param prompt the question, at most {@link InputLimits#PROMPT_MAX_BYTES} bytes
 * @param context facts that help answer, at most {@link InputLimits#CONTEXT_MAX_BYTES} bytes as
 *     JSON
 * @param options the answers offered. {@link InputKind#CHOICE} needs two or more. {@link
 *     InputKind#APPROVAL} may have none, which means any non-blank decision (approve and reject by
 *     convention, as {@code WAIT_FOR_SIGNAL} accepts today). No other kind has options
 * @param responseSchema a JSON Schema for {@link InputKind#STRUCTURED}; required for it, forbidden
 *     for the other kinds
 * @param risk how much a wrong answer costs, which decides who may answer
 * @param audience who may answer; empty defers to the current tier of the route
 * @param ttl how long the request lives; the engine caps it with its own maximum
 * @param onTimeout what happens at the deadline
 * @param defaultDecision the decision {@link TimeoutAction#USE_DEFAULT} uses; required for it, and
 *     one of the options when there are any
 * @param routeName a named route, or {@code null} for the default route
 * @param requester a label for the asking plugin such as {@code LLM_CALL}; informational, never
 *     used for authorisation
 * @since 0.1.0-alpha.4
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "the compact constructor stores unmodifiable copies")
public record InputRequestSpec(
    String key,
    InputKind kind,
    String prompt,
    Map<String, Object> context,
    List<InputOption> options,
    @Nullable String responseSchema,
    InputRisk risk,
    Audience audience,
    Duration ttl,
    TimeoutAction onTimeout,
    @Nullable String defaultDecision,
    @Nullable String routeName,
    String requester) {

  /** The accepted shape of a route name. */
  private static final Pattern ROUTE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  /** Reads a schema to see that it is a JSON object. */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * Checks every field and keeps immutable copies.
   *
   * @param key idempotency key
   * @param kind what is asked for
   * @param prompt the question
   * @param context facts that help answer
   * @param options the answers offered
   * @param responseSchema a JSON Schema, for {@link InputKind#STRUCTURED}
   * @param risk the risk
   * @param audience who may answer
   * @param ttl how long the request lives
   * @param onTimeout what happens at the deadline
   * @param defaultDecision the decision of {@link TimeoutAction#USE_DEFAULT}
   * @param routeName a named route, or {@code null}
   * @param requester a label for the asking plugin
   */
  public InputRequestSpec {
    InputLimits.requireId("key", key);
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(risk, "risk");
    Objects.requireNonNull(audience, "audience");
    Objects.requireNonNull(onTimeout, "onTimeout");
    Objects.requireNonNull(ttl, "ttl");
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("ttl must be positive");
    }
    InputLimits.requireText("prompt", prompt, InputLimits.PROMPT_MAX_BYTES);
    context = copy(context);
    InputLimits.requireWithin("context", context, InputLimits.CONTEXT_MAX_BYTES);
    options = checkOptions(kind, options);
    checkSchema(kind, responseSchema);
    checkDefault(onTimeout, defaultDecision, options);
    if (routeName != null && !ROUTE_NAME.matcher(routeName).matches()) {
      throw new IllegalArgumentException("routeName must match [A-Za-z0-9._-]{1,64}");
    }
    InputLimits.requireText("requester", requester, InputLimits.REQUESTER_MAX_CHARS * 4);
    if (requester.length() > InputLimits.REQUESTER_MAX_CHARS) {
      throw new IllegalArgumentException(
          "requester is longer than " + InputLimits.REQUESTER_MAX_CHARS + " characters");
    }
  }

  private static Map<String, Object> copy(final @Nullable Map<String, Object> context) {
    // Map.copyOf rejects null values, and a value in the context may be null
    return context == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(context));
  }

  private static List<InputOption> checkOptions(
      final InputKind kind, final @Nullable List<InputOption> options) {
    final List<InputOption> copy = options == null ? List.of() : List.copyOf(options);
    requireOptionsFit(kind, copy);
    final Set<String> ids = new HashSet<>();
    for (final InputOption option : copy) {
      if (!ids.add(option.id())) {
        throw new IllegalArgumentException("Option id used twice: " + option.id());
      }
    }
    return copy;
  }

  private static void requireOptionsFit(final InputKind kind, final List<InputOption> options) {
    final boolean offers = kind == InputKind.APPROVAL || kind == InputKind.CHOICE;
    if (!offers && !options.isEmpty()) {
      throw new IllegalArgumentException("A " + kind + " request has no options");
    }
    if (kind == InputKind.CHOICE && options.size() < 2) {
      throw new IllegalArgumentException("A CHOICE request needs at least two options");
    }
    if (options.size() > InputLimits.OPTIONS_MAX) {
      throw new IllegalArgumentException(
          "A request offers at most " + InputLimits.OPTIONS_MAX + " options");
    }
  }

  private static void checkSchema(final InputKind kind, final @Nullable String schema) {
    if (kind != InputKind.STRUCTURED) {
      if (schema != null) {
        throw new IllegalArgumentException("Only a STRUCTURED request has a responseSchema");
      }
      return;
    }
    if (schema == null || schema.isBlank()) {
      throw new IllegalArgumentException("A STRUCTURED request needs a responseSchema");
    }
    InputLimits.optionalText("responseSchema", schema, InputLimits.CONTEXT_MAX_BYTES);
    requireJsonObject(schema);
  }

  private static void requireJsonObject(final String schema) {
    final boolean isObject;
    try {
      isObject = JSON.readTree(schema).isObject();
    } catch (JacksonException e) {
      throw new IllegalArgumentException("responseSchema is not valid JSON", e);
    }
    if (!isObject) {
      throw new IllegalArgumentException("responseSchema must be a JSON object");
    }
  }

  private static void checkDefault(
      final TimeoutAction onTimeout,
      final @Nullable String defaultDecision,
      final List<InputOption> options) {
    if (onTimeout != TimeoutAction.USE_DEFAULT) {
      return;
    }
    if (defaultDecision == null || defaultDecision.isBlank()) {
      throw new IllegalArgumentException("USE_DEFAULT needs a defaultDecision");
    }
    InputLimits.optionalText("defaultDecision", defaultDecision, InputLimits.TEXT_MAX_BYTES);
    if (!options.isEmpty() && options.stream().noneMatch(o -> o.id().equals(defaultDecision))) {
      throw new IllegalArgumentException("defaultDecision is not one of the options");
    }
  }
}
