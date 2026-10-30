// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.RequestPath;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests for RequestLoggingFilter. */
@NoArgsConstructor
class RequestLoggingFilterTest {

  /** Context path for request parsing. */
  private static final String CONTEXT_PATH = "/";

  /** Filter instance for testing. */
  private final RequestLoggingFilter filter = new RequestLoggingFilter();

  @Test
  void testConstructor() {
    final RequestLoggingFilter instance = new RequestLoggingFilter();
    assertThat(instance).isNotNull();
  }

  @Test
  void testFilterLogsRequest() {
    final ServerWebExchange exchange = mock(ServerWebExchange.class);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    final ServerHttpResponse response = mock(ServerHttpResponse.class);
    final WebFilterChain chain = mock(WebFilterChain.class);

    when(exchange.getRequest()).thenReturn(request);
    when(exchange.getResponse()).thenReturn(response);
    when(request.getMethod()).thenReturn(HttpMethod.GET);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/test", CONTEXT_PATH));
    when(response.getStatusCode()).thenReturn(HttpStatus.OK);
    when(chain.filter(exchange)).thenReturn(Mono.empty());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    verify(chain).filter(exchange);
  }

  @Test
  void testFilterWithNullStatusCode() {
    final ServerWebExchange exchange = mock(ServerWebExchange.class);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    final ServerHttpResponse response = mock(ServerHttpResponse.class);
    final WebFilterChain chain = mock(WebFilterChain.class);

    when(exchange.getRequest()).thenReturn(request);
    when(exchange.getResponse()).thenReturn(response);
    when(request.getMethod()).thenReturn(HttpMethod.POST);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/create", CONTEXT_PATH));
    when(response.getStatusCode()).thenReturn(null);
    when(chain.filter(exchange)).thenReturn(Mono.empty());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    verify(chain).filter(exchange);
  }

  @Test
  void testFilterWithDifferentMethods() {
    for (final HttpMethod method :
        new HttpMethod[] {HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE}) {
      final ServerWebExchange exchange = mock(ServerWebExchange.class);
      final ServerHttpRequest request = mock(ServerHttpRequest.class);
      final ServerHttpResponse response = mock(ServerHttpResponse.class);
      final WebFilterChain chain = mock(WebFilterChain.class);

      when(exchange.getRequest()).thenReturn(request);
      when(exchange.getResponse()).thenReturn(response);
      when(request.getMethod()).thenReturn(method);
      when(request.getPath()).thenReturn(RequestPath.parse("/api/resource", CONTEXT_PATH));
      when(response.getStatusCode()).thenReturn(HttpStatus.OK);
      when(chain.filter(exchange)).thenReturn(Mono.empty());

      StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

      verify(chain).filter(exchange);
    }
  }

  @Test
  void testFilterWithDifferentStatusCodes() {
    final HttpStatus[] statusCodes = {
      HttpStatus.OK,
      HttpStatus.CREATED,
      HttpStatus.BAD_REQUEST,
      HttpStatus.UNAUTHORIZED,
      HttpStatus.NOT_FOUND,
      HttpStatus.INTERNAL_SERVER_ERROR
    };

    for (final HttpStatus statusCode : statusCodes) {
      final ServerWebExchange exchange = mock(ServerWebExchange.class);
      final ServerHttpRequest request = mock(ServerHttpRequest.class);
      final ServerHttpResponse response = mock(ServerHttpResponse.class);
      final WebFilterChain chain = mock(WebFilterChain.class);

      when(exchange.getRequest()).thenReturn(request);
      when(exchange.getResponse()).thenReturn(response);
      when(request.getMethod()).thenReturn(HttpMethod.GET);
      when(request.getPath()).thenReturn(RequestPath.parse("/api/test", CONTEXT_PATH));
      when(response.getStatusCode()).thenReturn(statusCode);
      when(chain.filter(exchange)).thenReturn(Mono.empty());

      StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

      verify(chain).filter(exchange);
    }
  }

  @Test
  void testFilterContinuesChainOnError() {
    final ServerWebExchange exchange = mock(ServerWebExchange.class);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    final ServerHttpResponse response = mock(ServerHttpResponse.class);
    final WebFilterChain chain = mock(WebFilterChain.class);

    when(exchange.getRequest()).thenReturn(request);
    when(exchange.getResponse()).thenReturn(response);
    when(request.getMethod()).thenReturn(HttpMethod.GET);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/error", CONTEXT_PATH));
    when(response.getStatusCode()).thenReturn(HttpStatus.INTERNAL_SERVER_ERROR);
    when(chain.filter(exchange)).thenReturn(Mono.error(new RuntimeException("upstream error")));

    StepVerifier.create(filter.filter(exchange, chain))
        .expectError(RuntimeException.class)
        .verify();

    verify(chain).filter(exchange);
  }

  @Test
  void testFilterWithComplexPath() {
    final ServerWebExchange exchange = mock(ServerWebExchange.class);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    final ServerHttpResponse response = mock(ServerHttpResponse.class);
    final WebFilterChain chain = mock(WebFilterChain.class);

    when(exchange.getRequest()).thenReturn(request);
    when(exchange.getResponse()).thenReturn(response);
    when(request.getMethod()).thenReturn(HttpMethod.GET);
    when(request.getPath())
        .thenReturn(RequestPath.parse("/api/v1/resources/123/details", CONTEXT_PATH));
    when(response.getStatusCode()).thenReturn(HttpStatus.OK);
    when(chain.filter(exchange)).thenReturn(Mono.empty());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    verify(chain).filter(exchange);
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 400, 401, 403, 404, 429})
  void testFilterCompletesWithAnyStatusAndAnyRemoteAddress(final int status)
      throws UnknownHostException {
    for (final InetSocketAddress remote :
        new InetSocketAddress[] {
          null,
          InetSocketAddress.createUnresolved("unresolved.example", 1),
          new InetSocketAddress(InetAddress.getByAddress(new byte[] {10, 0, 0, 1}), 1)
        }) {
      final MockServerWebExchange exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.get("/api/x").remoteAddress(remote).build());
      final WebFilterChain chain =
          e -> {
            e.getResponse().setRawStatusCode(status);
            return Mono.empty();
          };

      StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
    }
  }

  @Test
  void testRunsInsideTracingAndOutsideTheRejectingFilters() {
    final int tracing = order(RequestTracingFilter.class);
    final int logging = order(RequestLoggingFilter.class);
    assertThat(tracing).isLessThan(logging);
    assertThat(logging).isLessThan(order(HostOriginFilter.class));
    assertThat(logging).isLessThan(order(ApiKeyFilter.class));
  }

  private static int order(final Class<?> type) {
    return java.util.Objects.requireNonNull(
            org.springframework.core.annotation.AnnotationUtils.findAnnotation(
                type, org.springframework.core.annotation.Order.class))
        .value();
  }
}
