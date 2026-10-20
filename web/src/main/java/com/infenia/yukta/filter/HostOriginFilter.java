// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import com.infenia.yukta.security.ApiAuthenticator;
import com.infenia.yukta.security.ApiAuthenticator.Scope;
import com.infenia.yukta.security.ApiKeyAuthenticator;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Keeps a web page in the operator's browser from driving a server that has no API key (the
 * loopback default): a request must name a known host ({@code localhost}, {@code 127.0.0.1}, {@code
 * [::1]} or one of {@code yukta.allowed-hosts}), which stops DNS rebinding, and a request that
 * changes something must come from the same site (its {@code Origin} header is absent, matches the
 * {@code Host}, or is one of {@code yukta.allowed-origins}). Both answer 403. When an API key is
 * configured the check is off: a page cannot send the key header across sites.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.OnlyOneReturn"})
public class HostOriginFilter implements WebFilter {

  /** Host names that always reach a loopback server. */
  @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
  private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

  /** Whether the check is on: it is, unless an API key is configured. */
  private final boolean enforced;

  /** Extra host names, lower case ({@code yukta.allowed-hosts}). */
  private final Set<String> hosts;

  /** Extra origins, lower case ({@code yukta.allowed-origins}). */
  private final Set<String> origins;

  /**
   * Create the filter from the configuration.
   *
   * @param environment holds the API keys, {@code yukta.allowed-hosts} and {@code
   *     yukta.allowed-origins}
   */
  public HostOriginFilter(final Environment environment) {
    this(environment, ApiKeyAuthenticator.read(environment));
  }

  /**
   * Create the filter from the configuration and the authenticator in use.
   *
   * @param environment holds {@code yukta.allowed-hosts} and {@code yukta.allowed-origins}
   * @param authenticator tells whether any caller needs to authenticate
   */
  @Autowired
  public HostOriginFilter(final Environment environment, final ApiAuthenticator authenticator) {
    this.enforced = authenticator.isOpen(Scope.MCP);
    this.hosts = lowerCase(list(environment, "yukta.allowed-hosts"));
    this.origins = lowerCase(list(environment, "yukta.allowed-origins"));
  }

  private static List<String> list(final Environment environment, final String name) {
    return Binder.get(environment).bind(name, Bindable.listOf(String.class)).orElseGet(List::of);
  }

  private static Set<String> lowerCase(final List<String> values) {
    return Set.copyOf(values.stream().map(v -> v.strip().toLowerCase(Locale.ROOT)).toList());
  }

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    if (!enforced) {
      return chain.filter(exchange);
    }
    final HttpHeaders headers = exchange.getRequest().getHeaders();
    final String host = headers.getFirst(HttpHeaders.HOST);
    if (host != null && !knownHost(host)) {
      return reject(exchange);
    }
    final String origin = headers.getFirst(HttpHeaders.ORIGIN);
    final boolean read =
        HttpMethod.GET.equals(exchange.getRequest().getMethod())
            || HttpMethod.HEAD.equals(exchange.getRequest().getMethod())
            || HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod());
    if (!read && origin != null && !sameSite(origin, host == null ? "" : host)) {
      return reject(exchange);
    }
    return chain.filter(exchange);
  }

  private boolean knownHost(final String hostHeader) {
    final String lower = hostHeader.toLowerCase(Locale.ROOT);
    final int colon = lower.lastIndexOf(':');
    final String name = colon > lower.lastIndexOf(']') ? lower.substring(0, colon) : lower;
    return LOOPBACK.contains(name) || hosts.contains(name) || hosts.contains(lower);
  }

  private boolean sameSite(final String origin, final String hostHeader) {
    final String lower = origin.toLowerCase(Locale.ROOT);
    if (origins.contains(lower)) {
      return true;
    }
    try {
      final String authority = URI.create(lower).getAuthority();
      return authority != null && authority.equals(hostHeader.toLowerCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static Mono<Void> reject(final ServerWebExchange exchange) {
    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
    return exchange.getResponse().setComplete();
  }
}
