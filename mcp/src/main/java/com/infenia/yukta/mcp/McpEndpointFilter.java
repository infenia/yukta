// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.security.RequestPaths;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Guards the MCP endpoint ({@value #ENDPOINT}). When a key is configured, requests must carry it as
 * {@code Authorization: Bearer <key>} or {@code X-API-Key: <key>}. The keys are the REST keys
 * ({@code yukta.api-key}, {@code yukta.api-keys}) plus the optional {@code yukta.mcp.api-key}, so
 * setting only the REST key closes MCP too. The tools mutate runs and definitions and cannot be
 * told apart at this level, so only an {@code ADMIN} key that is not limited to sessions is
 * accepted; others get 403 and use the REST API. The path is matched after decoding, and a path
 * with parameters, encoded separators or dot segments is refused with 400. Also keeps the former
 * {@value #LEGACY_ENDPOINT} path working by rewriting it to the endpoint.
 */
@Component
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.OnlyOneReturn"})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class McpEndpointFilter implements WebFilter {

  /** Path of the streamable-HTTP MCP endpoint. */
  private static final String ENDPOINT = "/mcp";

  /** Former endpoint name, kept as an alias for clients that are already configured. */
  private static final String LEGACY_ENDPOINT = "/sse";

  /** Keys clients may present; empty disables the check. */
  private final ApiKeys keys;

  /**
   * Create the filter from the configuration.
   *
   * @param environment holds the REST keys and {@code yukta.mcp.api-key}
   */
  @Autowired
  public McpEndpointFilter(final Environment environment) {
    this.keys = ApiKeys.read(environment, "yukta.mcp.api-key");
  }

  /**
   * Create the filter with one key that may do everything.
   *
   * @param apiKey the required key, or empty to leave the endpoint open
   */
  public McpEndpointFilter(final String apiKey) {
    this.keys = ApiKeys.single(apiKey);
  }

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    final String raw = exchange.getRequest().getPath().value();
    final String path = RequestPaths.normalized(raw);
    final boolean legacy = LEGACY_ENDPOINT.equals(path);
    if (!legacy && !ENDPOINT.equals(path)) {
      return chain.filter(exchange);
    }
    if (keys.isEmpty()) {
      return chain.filter(rewritten(exchange, legacy));
    }
    final ApiKeys.Match match = keys.find(ApiKeys.presented(exchange.getRequest().getHeaders()));
    final HttpStatus refusal = refusal(raw, match);
    if (refusal != null) {
      return reject(exchange, refusal);
    }
    final Mono<Void> next = chain.filter(rewritten(exchange, legacy));
    return keys.named()
        ? next.contextWrite(ctx -> ctx.put(ControlActor.CONTEXT_KEY, "key:" + match.name()))
        : next;
  }

  /** Why a request to the endpoint is refused, or null if it may pass. */
  private static HttpStatus refusal(final String raw, final ApiKeys.Match match) {
    if (RequestPaths.ambiguous(raw)) {
      return HttpStatus.BAD_REQUEST;
    }
    if (match == null) {
      return HttpStatus.UNAUTHORIZED;
    }
    return match.role() == ApiRole.ADMIN && match.sessions().isEmpty()
        ? null
        : HttpStatus.FORBIDDEN;
  }

  private static ServerWebExchange rewritten(
      final ServerWebExchange exchange, final boolean legacy) {
    return legacy ? exchange.mutate().request(request -> request.path(ENDPOINT)).build() : exchange;
  }

  private static Mono<Void> reject(final ServerWebExchange exchange, final HttpStatus status) {
    exchange.getResponse().setStatusCode(status);
    return exchange.getResponse().setComplete();
  }
}
