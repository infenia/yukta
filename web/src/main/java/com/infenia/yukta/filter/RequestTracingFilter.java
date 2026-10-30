// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import io.micrometer.context.ContextRegistry;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Gives every request an ID that is returned in {@code X-Request-ID}/{@code X-Correlation-ID},
 * exposed to the rest of the chain, and written into the Reactor context and the logging MDC (key
 * {@code requestId}) so log lines can be correlated. Client-supplied IDs are used only when they
 * match {@code [A-Za-z0-9-]{1,64}}. It runs first, so requests rejected by the host and key filters
 * carry an ID too.
 */
@Component
@NullMarked
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTracingFilter implements WebFilter {

  /** Header name for request ID. */
  public static final String X_REQUEST_ID = "X-Request-ID";

  /** Header name for correlation ID. */
  public static final String X_CORRELATION_ID = "X-Correlation-ID";

  /** Reactor context and MDC key holding the request ID. */
  public static final String REQUEST_ID_KEY = "requestId";

  /** Accepted shape of a client-supplied ID. */
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

  static {
    // With automatic context propagation (enabled in YuktaApplication) the context value is
    // restored into the MDC on whichever thread runs the operator.
    ContextRegistry.getInstance()
        .registerThreadLocalAccessor(
            REQUEST_ID_KEY,
            () -> MDC.get(REQUEST_ID_KEY),
            value -> MDC.put(REQUEST_ID_KEY, value),
            () -> MDC.remove(REQUEST_ID_KEY));
  }

  /** Default constructor. */
  public RequestTracingFilter() {
    super();
  }

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    final HttpHeaders requestHeaders = exchange.getRequest().getHeaders();
    final String requestId = safeOrNew(requestHeaders.getFirst(X_REQUEST_ID));
    final String correlationId = safeOrNew(requestHeaders.getFirst(X_CORRELATION_ID), requestId);

    final HttpHeaders responseHeaders = exchange.getResponse().getHeaders();
    responseHeaders.set(X_REQUEST_ID, requestId);
    responseHeaders.set(X_CORRELATION_ID, correlationId);

    final ServerWebExchange traced =
        exchange.mutate().request(request -> request.header(X_REQUEST_ID, requestId)).build();
    return chain.filter(traced).contextWrite(Context.of(REQUEST_ID_KEY, requestId));
  }

  private static String safeOrNew(final String candidate) {
    return safeOrNew(candidate, UUID.randomUUID().toString());
  }

  private static String safeOrNew(final String candidate, final String fallback) {
    return candidate != null && SAFE_ID.matcher(candidate).matches() ? candidate : fallback;
  }
}
