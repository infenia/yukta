/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.RequestPath;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RequestLoggingFilterTest {

  private final RequestLoggingFilter filter = new RequestLoggingFilter();

  @Test
  void testConstructor() {
    RequestLoggingFilter instance = new RequestLoggingFilter();
    assertThat(instance).isNotNull();
  }

  @Test
  void testFilterLogsRequest() {
    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    ServerHttpResponse response = mock(ServerHttpResponse.class);
    WebFilterChain chain = mock(WebFilterChain.class);

    when(exchange.getRequest()).thenReturn(request);
    when(exchange.getResponse()).thenReturn(response);
    when(request.getMethod()).thenReturn(HttpMethod.GET);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/test", "/"));
    when(response.getStatusCode()).thenReturn(HttpStatus.OK);
    when(chain.filter(exchange)).thenReturn(Mono.empty());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    verify(chain).filter(exchange);
  }

  @Test
  void testFilterWithNullStatusCode() {
    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    ServerHttpResponse response = mock(ServerHttpResponse.class);
    WebFilterChain chain = mock(WebFilterChain.class);

    when(exchange.getRequest()).thenReturn(request);
    when(exchange.getResponse()).thenReturn(response);
    when(request.getMethod()).thenReturn(HttpMethod.POST);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/create", "/"));
    when(response.getStatusCode()).thenReturn(null);
    when(chain.filter(exchange)).thenReturn(Mono.empty());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    verify(chain).filter(exchange);
  }

  @Test
  void testFilterWithDifferentMethods() {
    for (HttpMethod method :
        new HttpMethod[] {HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE}) {
      ServerWebExchange exchange = mock(ServerWebExchange.class);
      ServerHttpRequest request = mock(ServerHttpRequest.class);
      ServerHttpResponse response = mock(ServerHttpResponse.class);
      WebFilterChain chain = mock(WebFilterChain.class);

      when(exchange.getRequest()).thenReturn(request);
      when(exchange.getResponse()).thenReturn(response);
      when(request.getMethod()).thenReturn(method);
      when(request.getPath()).thenReturn(RequestPath.parse("/api/resource", "/"));
      when(response.getStatusCode()).thenReturn(HttpStatus.OK);
      when(chain.filter(exchange)).thenReturn(Mono.empty());

      StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

      verify(chain).filter(exchange);
    }
  }

  @Test
  void testFilterWithDifferentStatusCodes() {
    HttpStatus[] statusCodes = {
      HttpStatus.OK,
      HttpStatus.CREATED,
      HttpStatus.BAD_REQUEST,
      HttpStatus.UNAUTHORIZED,
      HttpStatus.NOT_FOUND,
      HttpStatus.INTERNAL_SERVER_ERROR
    };

    for (HttpStatus statusCode : statusCodes) {
      ServerWebExchange exchange = mock(ServerWebExchange.class);
      ServerHttpRequest request = mock(ServerHttpRequest.class);
      ServerHttpResponse response = mock(ServerHttpResponse.class);
      WebFilterChain chain = mock(WebFilterChain.class);

      when(exchange.getRequest()).thenReturn(request);
      when(exchange.getResponse()).thenReturn(response);
      when(request.getMethod()).thenReturn(HttpMethod.GET);
      when(request.getPath()).thenReturn(RequestPath.parse("/api/test", "/"));
      when(response.getStatusCode()).thenReturn(statusCode);
      when(chain.filter(exchange)).thenReturn(Mono.empty());

      StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

      verify(chain).filter(exchange);
    }
  }

  @Test
  void testFilterContinuesChainOnError() {
    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    ServerHttpResponse response = mock(ServerHttpResponse.class);
    WebFilterChain chain = mock(WebFilterChain.class);

    when(exchange.getRequest()).thenReturn(request);
    when(exchange.getResponse()).thenReturn(response);
    when(request.getMethod()).thenReturn(HttpMethod.GET);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/error", "/"));
    when(response.getStatusCode()).thenReturn(HttpStatus.INTERNAL_SERVER_ERROR);
    when(chain.filter(exchange)).thenReturn(Mono.error(new RuntimeException("upstream error")));

    StepVerifier.create(filter.filter(exchange, chain))
        .expectError(RuntimeException.class)
        .verify();

    verify(chain).filter(exchange);
  }

  @Test
  void testFilterWithComplexPath() {
    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    ServerHttpResponse response = mock(ServerHttpResponse.class);
    WebFilterChain chain = mock(WebFilterChain.class);

    when(exchange.getRequest()).thenReturn(request);
    when(exchange.getResponse()).thenReturn(response);
    when(request.getMethod()).thenReturn(HttpMethod.GET);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/v1/resources/123/details", "/"));
    when(response.getStatusCode()).thenReturn(HttpStatus.OK);
    when(chain.filter(exchange)).thenReturn(Mono.empty());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    verify(chain).filter(exchange);
  }
}
