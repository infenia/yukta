// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.SupervisorContract;
import com.infenia.yukta.security.CallRateLimiter;
import com.infenia.yukta.security.RequestPaths;
import com.infenia.yukta.service.supervisor.agent.AgentCredentials;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Guards {@code /api/agent/v1/**} with the credential of a supervisor and nothing else: a human API
 * key is not accepted here, and a credential is not accepted anywhere else ({@link ApiKeyFilter}
 * leaves this path to this filter). The supervisor it names is the caller, as kind {@code
 * SUPERVISOR}, never a person.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class AgentCredentialFilter implements WebFilter {

  /** The path of the agent API. */
  public static final String PATH = "/api/agent/v1";

  /** The exchange attribute that holds the id of the supervisor the credential belongs to. */
  public static final String SUPERVISOR_ATTRIBUTE = "yukta.agent.supervisor";

  private static final String BEARER = "Bearer ";

  /** Largest request body, in bytes, when the property is not set. */
  private static final int DEFAULT_MAX_BODY = 65_536;

  /** Requests per credential and minute when the property is not set. */
  private static final int DEFAULT_REQUESTS_PER_MINUTE = 600;

  /** Wrong credentials per address and minute when the property is not set. */
  private static final int DEFAULT_FAILURES_PER_MINUTE = 20;

  private final AgentCredentials credentials;

  /** Caps the requests of one credential. */
  private final CallRateLimiter requests =
      new CallRateLimiter(
          Integer.getInteger("yukta.agent.requests-per-minute", DEFAULT_REQUESTS_PER_MINUTE));

  /** Caps the wrong credentials one address may try. */
  private final CallRateLimiter failures =
      new CallRateLimiter(
          Integer.getInteger("yukta.agent.failures-per-minute", DEFAULT_FAILURES_PER_MINUTE));

  /**
   * Creates the filter.
   *
   * @param credentials the agent credentials
   */
  public AgentCredentialFilter(final AgentCredentials credentials) {
    this.credentials = credentials;
  }

  /**
   * Whether a path belongs to the agent API.
   *
   * @param path the decoded path
   * @return true if it does
   */
  public static boolean isAgentPath(final String path) {
    return PATH.equals(path) || path.startsWith(PATH + "/");
  }

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    final String raw = exchange.getRequest().getPath().value();
    if (RequestPaths.ambiguous(raw)) {
      // no spelling of the path may reach the agent API without its check
      return raw.startsWith("/api/agent")
          ? reject(exchange, HttpStatus.BAD_REQUEST, "Ambiguous request path")
          : chain.filter(exchange);
    }
    if (!isAgentPath(RequestPaths.decoded(raw))) {
      return chain.filter(exchange);
    }
    final String from = remote(exchange);
    if (failures.exceeded(from)) {
      exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "60");
      return reject(exchange, HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts");
    }
    final Optional<String> supervisor =
        credentials.authenticate(token(exchange.getRequest().getHeaders()), from);
    if (supervisor.isEmpty()) {
      failures.tryAcquire(from);
      exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
      return reject(exchange, HttpStatus.UNAUTHORIZED, "Missing or invalid agent credential");
    }
    if (exchange.getRequest().getHeaders().getContentLength()
        > Integer.getInteger("yukta.agent.max-body-bytes", DEFAULT_MAX_BODY)) {
      return reject(exchange, HttpStatus.CONTENT_TOO_LARGE, "The request body is too large");
    }
    if (!requests.tryAcquire(supervisor.get())) {
      exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "60");
      return reject(exchange, HttpStatus.TOO_MANY_REQUESTS, "Too many requests, slow down");
    }
    exchange.getAttributes().put(SUPERVISOR_ATTRIBUTE, supervisor.get());
    exchange
        .getResponse()
        .getHeaders()
        .set(SupervisorContract.VERSION_HEADER, String.valueOf(SupervisorContract.VERSION));
    return chain
        .filter(exchange)
        .contextWrite(
            ctx ->
                ctx.put(ControlActor.CONTEXT_KEY, supervisor.get())
                    .put(ControlActor.KIND_KEY, ControlActor.Kind.SUPERVISOR));
  }

  private static String remote(final ServerWebExchange exchange) {
    final InetSocketAddress address = exchange.getRequest().getRemoteAddress();
    return address == null ? "unknown" : address.getAddress().getHostAddress();
  }

  private static @Nullable String token(final HttpHeaders headers) {
    final String header = headers.getFirst(HttpHeaders.AUTHORIZATION);
    return header != null && header.startsWith(BEARER) ? header.substring(BEARER.length()) : null;
  }

  private static Mono<Void> reject(
      final ServerWebExchange exchange, final HttpStatus status, final String message) {
    exchange.getResponse().setStatusCode(status);
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
    final byte[] body =
        ("{\"status\":" + status.value() + ",\"message\":\"" + message + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    return exchange
        .getResponse()
        .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
  }
}
