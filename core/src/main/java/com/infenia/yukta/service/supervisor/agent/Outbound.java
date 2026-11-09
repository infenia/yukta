// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.agent;

import com.infenia.yukta.plugin.control.engine.Signal;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Posts each signal of an {@code http} supervisor to the external system, when its settings name a
 * {@code url}. The body is what {@code /api/agent/v1/signals} returns for the signal ({@code
 * cursor}, {@code kind}, {@code signal}), so a system that missed a post reads it there with {@code
 * after=<cursor>}. The reply is not read: the system decides through the agent API (directives,
 * reviews, delegations). Delivery is best effort: a failed post is logged without the headers.
 */
@Slf4j
public final class Outbound {

  /** The setting with the URL to post to; without it nothing is posted. */
  public static final String URL = "url";

  /** Headers to send; a value may be {@code ${env.NAME}}. */
  public static final String HEADERS = "headers";

  /** Headers with secrets, masked on reads. */
  public static final String SECRET_HEADERS = "secretHeaders";

  /** How long one post may take, in milliseconds. */
  public static final String TIMEOUT = "timeoutMs";

  /** Most posts at once. */
  public static final String MAX_CONCURRENT = "maxConcurrent";

  /** Longest timeout a supervisor may set. */
  public static final int MAX_TIMEOUT_MS = 10_000;

  /** Most posts at once a supervisor may set. */
  public static final int MAX_POSTS = 32;

  private static final int DEFAULT_TIMEOUT_MS = 3000;

  private static final int DEFAULT_POSTS = 4;

  private static final String PREFIX = "${env.";

  private final Egress egress;

  private final WebClient client;

  private final JsonMapper json;

  private final UnaryOperator<String> environment;

  /**
   * Creates the poster.
   *
   * @param egress decides which URLs may be called
   * @param client the client to post with; it does not follow redirects
   * @param json writes the body as the agent API does
   * @param environment looks up an environment variable, or returns null
   */
  public Outbound(
      final Egress egress,
      final WebClient client,
      final JsonMapper json,
      final UnaryOperator<String> environment) {
    this.egress = egress;
    this.client = client;
    this.json = json;
    this.environment = environment;
  }

  /**
   * Reads the settings. Messages name the key, never a header value.
   *
   * @param settings the supervisor's settings
   * @return where to post, or null when no {@code url} is set
   * @throws IllegalArgumentException when a setting is wrong
   */
  public @Nullable Config read(final Map<String, Object> settings) {
    final Object raw = settings.get(URL);
    if (raw == null || raw instanceof final String blank && blank.isBlank()) {
      return null;
    }
    final String url = String.valueOf(raw).strip();
    final URI uri = URI.create(url);
    if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
        || uri.getHost() == null) {
      throw new IllegalArgumentException(URL + " must be an http or https URL with a host");
    }
    final Map<String, String> headers = new ConcurrentHashMap<>();
    headers.putAll(readHeaders(settings.get(HEADERS), HEADERS));
    headers.putAll(readHeaders(settings.get(SECRET_HEADERS), SECRET_HEADERS));
    return new Config(
        url,
        headers,
        Duration.ofMillis(bounded(settings, TIMEOUT, DEFAULT_TIMEOUT_MS, MAX_TIMEOUT_MS)),
        bounded(settings, MAX_CONCURRENT, DEFAULT_POSTS, MAX_POSTS));
  }

  /**
   * Posts the signals as they come.
   *
   * @param supervisorId the supervisor, for the log
   * @param config where to post
   * @param signals the signals with their cursors
   * @return completes when the signals do; never errors
   */
  public Mono<Void> deliver(
      final String supervisorId, final Config config, final Flux<AgentHub.Sequenced> signals) {
    return signals
        .flatMap(signal -> post(supervisorId, config, signal), config.maxConcurrent())
        .then();
  }

  private Mono<Void> post(
      final String supervisorId, final Config config, final AgentHub.Sequenced sequenced) {
    final Body body =
        new Body(sequenced.cursor(), sequenced.signal().kind().name(), sequenced.signal());
    return egress
        .check(config.url())
        .then(
            Mono.defer(
                () ->
                    client
                        .post()
                        .uri(URI.create(config.url()))
                        .headers(headers -> config.headers().forEach(headers::set))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json.writeValueAsString(body))
                        .retrieve()
                        .toBodilessEntity()
                        .timeout(config.timeout())))
        .then()
        .onErrorResume(
            error -> {
              log.atWarn()
                  .addKeyValue("supervisor", supervisorId)
                  .addKeyValue("cursor", sequenced.cursor())
                  .addKeyValue("error", error.getClass().getSimpleName())
                  .log("Could not post a signal to the external system; it can read it again");
              return Mono.empty();
            });
  }

  private Map<String, String> readHeaders(final @Nullable Object value, final String key) {
    if (value == null) {
      return Map.of();
    }
    if (!(value instanceof final Map<?, ?> map)) {
      throw new IllegalArgumentException(key + " must be an object of header names and values");
    }
    final Map<String, String> out = new ConcurrentHashMap<>();
    map.forEach(
        (name, text) -> out.put(String.valueOf(name), resolve(key, String.valueOf(name), text)));
    return out;
  }

  /** A header value; {@code ${env.NAME}} is read from the environment. No value is repeated. */
  private String resolve(final String key, final String name, final Object text) {
    final String raw = String.valueOf(text);
    if (raw.startsWith(PREFIX) && raw.endsWith("}")) {
      final String variable = raw.substring(PREFIX.length(), raw.length() - 1);
      final String found = environment.apply(variable);
      if (found == null) {
        throw new IllegalArgumentException(
            key + "." + name + " refers to the environment variable " + variable + ", not set");
      }
      return found;
    }
    return raw;
  }

  private static int bounded(
      final Map<String, Object> settings, final String key, final int fallback, final int max) {
    final Object value = settings.get(key);
    if (value == null) {
      return fallback;
    }
    if (value instanceof final Number number
        && number.doubleValue() == Math.rint(number.doubleValue())
        && number.doubleValue() >= 1
        && number.doubleValue() <= max) {
      return number.intValue();
    }
    throw new IllegalArgumentException(key + " must be a whole number from 1 to " + max);
  }

  /**
   * Where and how to post.
   *
   * @param url the URL
   * @param headers the headers, secrets resolved
   * @param timeout how long one post may take
   * @param maxConcurrent most posts at once
   */
  public record Config(
      String url, Map<String, String> headers, Duration timeout, int maxConcurrent) {

    /** Keeps an unmodifiable copy of the headers. */
    public Config {
      headers = Map.copyOf(headers);
    }

    /** Never shows the headers, which may hold secrets. */
    @Override
    public String toString() {
      return "Config[url=" + url + ", headers=" + headers.keySet() + "]";
    }
  }

  /** One post: the signal as the agent API shows it, with its cursor and kind, in that order. */
  private record Body(long cursor, String kind, Signal signal) {}
}
