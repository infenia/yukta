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
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Guards the MCP endpoint ({@value #ENDPOINT}). When a key is configured, requests must carry it as
 * {@code Authorization: Bearer <key>} or {@code X-API-Key: <key>}. The keys are the REST keys
 * ({@code yukta.api-key}, {@code yukta.api-keys}) plus the optional {@code yukta.mcp.api-key}, so
 * setting only the REST key closes MCP too. A key that is not an unrestricted {@code ADMIN} has the
 * JSON-RPC body read: a {@code tools/call} needs the role {@link McpToolAccess} lists for the tool
 * and, for a session-limited key, a {@code sessionId} of its own (403 otherwise). The path is
 * matched after decoding, and a path with parameters, encoded separators or dot segments is refused
 * with 400. Also keeps the former {@value #LEGACY_ENDPOINT} path working by rewriting it to the
 * endpoint.
 */
@Component
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.OnlyOneReturn"})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class McpEndpointFilter implements WebFilter {

  /** Path of the streamable-HTTP MCP endpoint. */
  private static final String ENDPOINT = "/mcp";

  /** Largest request body read to check the tools it calls. */
  private static final int MAX_BODY_BYTES = 1 << 20;

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
    final Mono<Void> next =
        match.role() == ApiRole.ADMIN && match.sessions().isEmpty()
            ? chain.filter(rewritten(exchange, legacy))
            : checked(exchange, chain, match, legacy);
    return keys.named()
        ? next.contextWrite(ctx -> ctx.put(ControlActor.CONTEXT_KEY, "key:" + match.name()))
        : next;
  }

  /**
   * Reads the JSON-RPC body of a limited key, refuses a tool call the key may not make with 403 and
   * hands the same body on.
   */
  private static Mono<Void> checked(
      final ServerWebExchange exchange,
      final WebFilterChain chain,
      final ApiKeys.Match match,
      final boolean legacy) {
    if (!HttpMethod.POST.equals(exchange.getRequest().getMethod())) {
      return chain.filter(rewritten(exchange, legacy));
    }
    return DataBufferUtils.join(exchange.getRequest().getBody(), MAX_BODY_BYTES)
        .map(
            buffer -> {
              final byte[] bytes = new byte[buffer.readableByteCount()];
              buffer.read(bytes);
              DataBufferUtils.release(buffer);
              return bytes;
            })
        .defaultIfEmpty(new byte[0])
        .flatMap(
            bytes ->
                bytes.length > 0 && !McpToolAccess.permitted(match, bytes)
                    ? reject(exchange, HttpStatus.FORBIDDEN)
                    : chain.filter(rewritten(withBody(exchange, bytes), legacy)))
        .onErrorResume(
            DataBufferLimitException.class, _ -> reject(exchange, HttpStatus.CONTENT_TOO_LARGE));
  }

  private static ServerWebExchange withBody(final ServerWebExchange exchange, final byte[] bytes) {
    final ServerHttpRequest request =
        new ServerHttpRequestDecorator(exchange.getRequest()) {
          @Override
          public Flux<DataBuffer> getBody() {
            return Flux.just(exchange.getResponse().bufferFactory().wrap(bytes));
          }
        };
    return exchange.mutate().request(request).build();
  }

  /** Why a request to the endpoint is refused before its body is read, or null. */
  private static HttpStatus refusal(final String raw, final ApiKeys.Match match) {
    if (RequestPaths.ambiguous(raw)) {
      return HttpStatus.BAD_REQUEST;
    }
    if (match == null) {
      return HttpStatus.UNAUTHORIZED;
    }
    return null;
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
