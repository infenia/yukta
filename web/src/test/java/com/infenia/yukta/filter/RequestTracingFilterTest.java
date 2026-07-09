// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests for RequestTracingFilter. */
@NoArgsConstructor
class RequestTracingFilterTest {

  /** HTTP header name for request ID. */
  private static final String X_REQUEST_ID = "X-Request-ID";

  /** HTTP header name for correlation ID. */
  private static final String X_CORRELATION_ID = "X-Correlation-ID";

  /** Example existing request ID. */
  private static final String EXISTING_REQUEST_ID = "existing-request-id";

  /** Example existing correlation ID. */
  private static final String EXISTING_CORRELATION_ID = "existing-correlation-id";

  /** Filter instance for testing. */
  private final RequestTracingFilter filter = new RequestTracingFilter();

  @Test
  void testConstructor() {
    final RequestTracingFilter instance = new RequestTracingFilter();
    assertThat(instance).isNotNull();
  }

  @Test
  void testFilterGeneratesNewRequestIdWhenMissing() {
    final ServerWebExchange exchange = mock(ServerWebExchange.class);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    final ServerHttpResponse response = mock(ServerHttpResponse.class);
    final WebFilterChain chain = mock(WebFilterChain.class);

    final HttpHeaders requestHeaders = new HttpHeaders();
    final HttpHeaders responseHeaders = new HttpHeaders();

    when(exchange.getRequest()).thenReturn(request);
    when(exchange.getResponse()).thenReturn(response);
    when(request.getHeaders()).thenReturn(requestHeaders);
    when(response.getHeaders()).thenReturn(responseHeaders);
    when(chain.filter(exchange)).thenReturn(Mono.empty());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    verify(chain).filter(exchange);
    assertThat(responseHeaders.get(X_REQUEST_ID)).isNotNull();
    assertThat(responseHeaders.get(X_REQUEST_ID)).hasSize(1);
    assertThat(responseHeaders.get(X_CORRELATION_ID)).isNotNull();
    assertThat(responseHeaders.get(X_CORRELATION_ID)).hasSize(1);
  }

  @Test
  void testFilterUsesExistingRequestId() {
    final ServerWebExchange exchange = mock(ServerWebExchange.class);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    final ServerHttpResponse response = mock(ServerHttpResponse.class);
    final WebFilterChain chain = mock(WebFilterChain.class);

    final HttpHeaders requestHeaders = new HttpHeaders();
    requestHeaders.add(X_REQUEST_ID, EXISTING_REQUEST_ID);
    final HttpHeaders responseHeaders = new HttpHeaders();

    when(exchange.getRequest()).thenReturn(request);
    when(exchange.getResponse()).thenReturn(response);
    when(request.getHeaders()).thenReturn(requestHeaders);
    when(response.getHeaders()).thenReturn(responseHeaders);
    when(chain.filter(exchange)).thenReturn(Mono.empty());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    verify(chain).filter(exchange);
    assertThat(responseHeaders.getFirst(X_REQUEST_ID)).isEqualTo(EXISTING_REQUEST_ID);
    assertThat(responseHeaders.getFirst(X_CORRELATION_ID)).isEqualTo(EXISTING_REQUEST_ID);
  }

  @Test
  void testFilterUsesExistingCorrelationId() {
    final ServerWebExchange exchange = mock(ServerWebExchange.class);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    final ServerHttpResponse response = mock(ServerHttpResponse.class);
    final WebFilterChain chain = mock(WebFilterChain.class);

    final HttpHeaders requestHeaders = new HttpHeaders();
    requestHeaders.add(X_CORRELATION_ID, EXISTING_CORRELATION_ID);
    final HttpHeaders responseHeaders = new HttpHeaders();

    when(exchange.getRequest()).thenReturn(request);
    when(exchange.getResponse()).thenReturn(response);
    when(request.getHeaders()).thenReturn(requestHeaders);
    when(response.getHeaders()).thenReturn(responseHeaders);
    when(chain.filter(exchange)).thenReturn(Mono.empty());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    verify(chain).filter(exchange);
    assertThat(responseHeaders.getFirst(X_REQUEST_ID)).isNotNull();
    assertThat(responseHeaders.getFirst(X_CORRELATION_ID)).isEqualTo(EXISTING_CORRELATION_ID);
  }

  @Test
  void testFilterUsesBothExistingHeaders() {
    final ServerWebExchange exchange = mock(ServerWebExchange.class);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    final ServerHttpResponse response = mock(ServerHttpResponse.class);
    final WebFilterChain chain = mock(WebFilterChain.class);

    final HttpHeaders requestHeaders = new HttpHeaders();
    requestHeaders.add(X_REQUEST_ID, EXISTING_REQUEST_ID);
    requestHeaders.add(X_CORRELATION_ID, EXISTING_CORRELATION_ID);
    final HttpHeaders responseHeaders = new HttpHeaders();

    when(exchange.getRequest()).thenReturn(request);
    when(exchange.getResponse()).thenReturn(response);
    when(request.getHeaders()).thenReturn(requestHeaders);
    when(response.getHeaders()).thenReturn(responseHeaders);
    when(chain.filter(exchange)).thenReturn(Mono.empty());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    verify(chain).filter(exchange);
    assertThat(responseHeaders.getFirst(X_REQUEST_ID)).isEqualTo(EXISTING_REQUEST_ID);
    assertThat(responseHeaders.getFirst(X_CORRELATION_ID)).isEqualTo(EXISTING_CORRELATION_ID);
  }

  @Test
  void testFilterContinuesChain() {
    final ServerWebExchange exchange = mock(ServerWebExchange.class);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    final ServerHttpResponse response = mock(ServerHttpResponse.class);
    final WebFilterChain chain = mock(WebFilterChain.class);

    final HttpHeaders requestHeaders = new HttpHeaders();
    final HttpHeaders responseHeaders = new HttpHeaders();

    when(exchange.getRequest()).thenReturn(request);
    when(exchange.getResponse()).thenReturn(response);
    when(request.getHeaders()).thenReturn(requestHeaders);
    when(response.getHeaders()).thenReturn(responseHeaders);
    when(chain.filter(exchange)).thenReturn(Mono.error(new RuntimeException("test error")));

    StepVerifier.create(filter.filter(exchange, chain))
        .expectError(RuntimeException.class)
        .verify();

    verify(chain).filter(exchange);
  }
}
