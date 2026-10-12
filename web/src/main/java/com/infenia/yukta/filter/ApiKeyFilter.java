// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import com.infenia.yukta.plugin.control.ControlActor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Requires an API key on every {@code /api/**} request when one is configured, as {@code
 * Authorization: Bearer <key>} or {@code X-API-Key: <key>}. With no key configured the API stays
 * open, which is safe only because the server listens on loopback by default.
 *
 * <p>{@code yukta.api-key} is a single key that may do everything. {@code yukta.api-keys} lists
 * named keys with a role (see {@link ApiRole}):
 *
 * <pre>
 * yukta.api-keys[0].name=alice
 * yukta.api-keys[0].key=...
 * yukta.api-keys[0].role=OPERATOR
 * </pre>
 *
 * <p>A key whose role does not allow the request gets 403. With named keys, the control commands a
 * request sends are recorded with the actor {@code key:<name>}.
 */
@Component
@NullMarked
@Order(Ordered.HIGHEST_PRECEDENCE)
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.OnlyOneReturn"})
public class ApiKeyFilter implements WebFilter {

  /** Header that may carry the key instead of the Authorization header. */
  private static final String API_KEY_HEADER = "X-API-Key";

  /** Prefix of the Authorization header value. */
  private static final String BEARER = "Bearer ";

  /** Keys clients may present; empty disables the check. */
  private final List<Key> keys;

  /** Whether the actor of a request is recorded (named keys are configured). */
  private final boolean named;

  /** A key clients may present. */
  /* default */ record Key(String name, byte[] bytes, ApiRole role) {}

  /** A key as written in the configuration. */
  public record KeyEntry(String name, String key, ApiRole role) {}

  /** The keys read from the configuration. */
  private record Config(List<Key> keys, boolean named) {}

  /**
   * Create the filter from the configuration.
   *
   * @param environment holds {@code yukta.api-key} and {@code yukta.api-keys}
   */
  @Autowired
  public ApiKeyFilter(final Environment environment) {
    this(read(environment));
  }

  private ApiKeyFilter(final Config config) {
    this.keys = config.keys();
    this.named = config.named();
  }

  /**
   * Create the filter with one key that may do everything.
   *
   * @param apiKey the required key, or empty to leave the API open
   */
  public ApiKeyFilter(final String apiKey) {
    this.keys =
        apiKey.isEmpty()
            ? List.of()
            : List.of(new Key("default", apiKey.getBytes(StandardCharsets.UTF_8), ApiRole.ADMIN));
    this.named = false;
  }

  private static Config read(final Environment environment) {
    final List<KeyEntry> entries =
        Binder.get(environment)
            .bind("yukta.api-keys", Bindable.listOf(KeyEntry.class))
            .orElseGet(List::of);
    final String single = Objects.requireNonNullElse(environment.getProperty("yukta.api-key"), "");
    final List<Key> all = new ArrayList<>();
    if (!single.isEmpty()) {
      all.add(new Key("default", single.getBytes(StandardCharsets.UTF_8), ApiRole.ADMIN));
    }
    for (final KeyEntry entry : entries) {
      final String key = Objects.requireNonNull(entry.key(), "yukta.api-keys[].key is required");
      all.add(
          new Key(
              Objects.requireNonNullElse(entry.name(), "unnamed"),
              key.getBytes(StandardCharsets.UTF_8),
              Objects.requireNonNullElse(entry.role(), ApiRole.VIEWER)));
    }
    return new Config(List.copyOf(all), !entries.isEmpty());
  }

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    if (keys.isEmpty() || !exchange.getRequest().getPath().value().startsWith("/api/")) {
      return chain.filter(exchange);
    }
    final HttpHeaders headers = exchange.getRequest().getHeaders();
    final String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
    final String presented =
        authorization != null && authorization.startsWith(BEARER)
            ? authorization.substring(BEARER.length())
            : headers.getFirst(API_KEY_HEADER);
    final Key match = presented == null ? null : find(presented);
    if (match == null) {
      exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
      exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
      return exchange.getResponse().setComplete();
    }
    if (!match
        .role()
        .allows(exchange.getRequest().getMethod(), exchange.getRequest().getPath().value())) {
      exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
      return exchange.getResponse().setComplete();
    }
    return named
        ? chain
            .filter(exchange)
            .contextWrite(ctx -> ctx.put(ControlActor.CONTEXT_KEY, "key:" + match.name()))
        : chain.filter(exchange);
  }

  /** Compares against every key, so the time taken does not tell which one is close. */
  @Nullable
  private Key find(final String presented) {
    final byte[] bytes = presented.getBytes(StandardCharsets.UTF_8);
    Key match = null;
    for (final Key key : keys) {
      if (MessageDigest.isEqual(bytes, key.bytes())) {
        match = key;
      }
    }
    return match;
  }
}
